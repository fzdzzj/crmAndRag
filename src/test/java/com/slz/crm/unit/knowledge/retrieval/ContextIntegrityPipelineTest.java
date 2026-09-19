package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.LlmContextCompressor;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 引用编号完整性管线测试（add-context-compression-and-enrichment 任务 3.1）。
 *
 * <p>按基准五类（TEXT/TABLE/IMAGE/LEXICAL/EDGE，见 RagBenchmarkSuite）构造命中数据， 断言邻居拼装与超预算压缩两种形态下：上下文行首 {@code
 * [n]} 编号与 sources 下标一一对应 （编号段能唯一定位来源条目，且段内承重内容与来源一致）、邻居内容绝不进入 sources （citationPrecision
 * 判定依据不受污染）。EDGE 类对应零命中：无编号即无映射。
 *
 * <p>命中块顺序经重排/融合后对测试不可预知，因此映射断言全部以 {@code sources[i].excerpt()} 为锚做次序无关校验（第 i 段内容必须对应第 i 条来源）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextIntegrityPipelineTest {

  private static final Pattern SECTION_HEADER = Pattern.compile("^\\[(\\d{1,4})\\]\\s?");

  @Mock private KnowledgeBaseAuthorizationService authorizationService;

  @Mock private EmbeddingService embeddingService;

  @Mock private com.slz.crm.platform.contract.CrmVectorStore vectorStore;

  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private DynamicConfigService dynamicConfigService;

  @Mock private DocumentVectorChunkMapper chunkMapper;

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  /** TEXT 类（T-01 风格）：两命中块带邻居。 */
  @Test
  @DisplayName("TEXT：编号与 sources 下标一一对应")
  void textCategoryNumberingIntegrity() {
    runIntegrity(
        "销售合同审批流程是怎样的",
        List.of(
            new HitDef("c2", "doc-text", 2, 3L, "提交申请后进入部门审批环节，金额15万元需总监复核（锚点A1）。后续由财务归档留存。"),
            new HitDef("c7", "doc-text", 7, 6L, "归档要求纸质件与电子件双轨保存，保存期限5年（锚点B2）。到期后由行政统一销毁。")),
        List.of(
            neighbor(1, "doc-text", "提交前的准备材料清单包括营业执照与授权书。", 3L),
            neighbor(3, "doc-text", "审批通过后进入用印环节。", 3L),
            neighbor(6, "doc-text", "归档前需核对合同编号。", 6L),
            neighbor(8, "doc-text", "电子件存入档案系统。", 6L)));
  }

  /** TABLE 类（TB-01 风格）：表格行切片带页码锚点。 */
  @Test
  @DisplayName("TABLE：行锚点切片编号对应")
  void tableCategoryNumberingIntegrity() {
    runIntegrity(
        "第三季度各区域销售额",
        List.of(
            new HitDef("c4", "doc-table", 4, 1L, "第三季度华东区域销售额1200万元，环比增长8%（锚点A1）。华北区域销售额950万元。")),
        List.of(
            neighbor(3, "doc-table", "第二季度华东区域销售额1110万元。", 1L),
            neighbor(5, "doc-table", "第三季度华南区域达成率92%。", 1L)));
  }

  /** IMAGE 类（I-01 风格）：图注文本切片。 */
  @Test
  @DisplayName("IMAGE：图注切片编号对应")
  void imageCategoryNumberingIntegrity() {
    runIntegrity(
        "这张架构图里的数据流走向",
        List.of(new HitDef("c1", "doc-image", 1, 2L, "架构图数据流自采集层流向计算层，延迟10秒以内（锚点A1）。存储层位于链路末端。")),
        List.of(
            neighbor(0, "doc-image", "架构图标题与版本号v2.1。", 2L),
            neighbor(2, "doc-image", "告警链路自计算层回流监控层。", 2L)));
  }

  /** LEXICAL 类（L-05 风格）：型号/编号精确型切片，两命中跨文档且邻居不跨文档取。 */
  @Test
  @DisplayName("LEXICAL：跨文档命中的编号各自独立")
  void lexicalCategoryNumberingIntegrity() {
    runIntegrity(
        "KQ-9000 固件升级与 BK-2024 代号",
        List.of(
            new HitDef(
                "c11", "doc-kq", 1, 4L, "KQ-9000 的固件应该升级到 3.4.1 版本（锚点A1），升级耗时约20分钟。旧版本3.2.0将停止维护。"),
            new HitDef("c22", "doc-bk", 6, 9L, "内部代号 BK-2024 对应蓝鲸计划（锚点B2），由华北交付组实施。项目周期为6个月。")),
        List.of(
            neighbor(0, "doc-kq", "KQ-9000 设备开箱检查清单。", 4L),
            neighbor(2, "doc-kq", "固件升级前的数据备份要求。", 4L),
            neighbor(5, "doc-bk", "蓝鲸计划立项背景说明。", 9L),
            neighbor(7, "doc-bk", "华北交付组人员构成清单。", 9L)));
  }

  /** EDGE 类（E-02 风格）：KB ON 零命中——无编号即无映射，context/sources 两端一致为空。 */
  @Test
  @DisplayName("EDGE：零命中无编号即无映射")
  void edgeCategoryZeroHitHasNoMappingToBreak() {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(dynamicConfigService.get("rag.retrieval.query-rewrite.enabled", Boolean.class, true))
        .thenReturn(false);
    when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(1L));
    when(embeddingService.embed("关于虚构主题 ZYX-9 的规定")).thenReturn(new float[] {1f, 0f});
    when(vectorStore.search(any(VectorSearchRequest.class))).thenReturn(List.of());
    KnowledgeRetrievalPort service = newService();

    KnowledgeRetrievalPort.RetrievalResult result =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                "关于虚构主题 ZYX-9 的规定", 1L, List.of("1"), 5, null, null));

    assertThat(result.hitCount()).isZero();
    assertThat(result.sources()).isEmpty();
    assertThat(result.context()).isEmpty();
  }

  // ---------------------------------------------------------------- 公共编排

  /**
   * 双预算域断言：未超预算（excerpt 逐字落在对应编号段）与超预算（规则压缩后行首编号 序列仍与 sources 一一对应、编号段内容可反向定位到来源）两种形态下，
   * "邻居不进引用"恒成立。
   */
  private void runIntegrity(
      String query, List<HitDef> hits, List<DocumentVectorChunkEntity> neighbors) {
    UserContext user = new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user");
    UserContextHolder.set(user);
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(dynamicConfigService.get("rag.retrieval.query-rewrite.enabled", Boolean.class, true))
        .thenReturn(false);
    when(authorizationService.authorizedKnowledgeBaseIds(user, List.of("1")))
        .thenReturn(List.of(1L));
    when(embeddingService.embed(query)).thenReturn(new float[] {1f, 0f});
    List<String> hitTexts = hits.stream().map(HitDef::text).toList();
    when(vectorStore.search(any(VectorSearchRequest.class)))
        .thenReturn(
            hits.stream()
                .map(
                    hit ->
                        new VectorSearchHit(
                            hit.chunkId(),
                            hit.docId(),
                            0.9d,
                            hit.text(),
                            Map.of(
                                "chunkIndex", hit.chunkIndex(),
                                "filename", "corpus.txt",
                                "pageNo", hit.pageNo())))
                .toList());
    when(chunkMapper.selectList(any())).thenReturn(neighbors);

    KnowledgeRetrievalPort service = newService();

    // 形态1：预算宽裕——不触发压缩，sources[i] 的 excerpt 逐字落在第 i+1 编号段
    KnowledgeRetrievalPort.RetrievalResult intact =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                query, 1L, List.of("1"), hits.size(), null, null));
    assertThat(intact.hitCount()).isEqualTo(hits.size());
    assertThat(intact.sources())
        .extracting(SourceReference::excerpt)
        .containsExactlyInAnyOrderElementsOf(hitTexts);
    List<String> intactSections = numberedSections(intact.context());
    assertThat(intactSections).hasSize(hits.size());
    for (int index = 0; index < intact.sources().size(); index++) {
      assertThat(intactSections.get(index)).contains(intact.sources().get(index).excerpt());
    }
    for (DocumentVectorChunkEntity neighbor : neighbors) {
      assertThat(intact.context()).contains(neighbor.getChunkText().strip());
      assertThat(intact.sources())
          .extracting(SourceReference::excerpt)
          .doesNotContain(neighbor.getChunkText().strip());
    }

    // 形态2：预算紧张——触发规则压缩，编号段数与 sources 条数仍一致，段内容可反向定位来源
    when(dynamicConfigService.get("rag.context.token-budget", Integer.class, 4096)).thenReturn(12);
    KnowledgeRetrievalPort.RetrievalResult compressed =
        service.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                query, 1L, List.of("1"), hits.size(), null, null));
    assertThat(compressed.sources())
        .extracting(SourceReference::excerpt)
        .containsExactlyInAnyOrderElementsOf(hitTexts);
    List<String> compressedSections = numberedSections(compressed.context());
    assertThat(compressedSections).hasSize(compressed.sources().size());
    for (int index = 0; index < compressedSections.size(); index++) {
      assertThat(compressedSections.get(index)).isNotBlank();
      for (HitDef hit : hits) {
        if (compressedSections.get(index).contains(hit.marker())) {
          assertThat(compressed.sources().get(index).excerpt()).contains(hit.marker());
        }
      }
    }
    for (DocumentVectorChunkEntity neighbor : neighbors) {
      assertThat(compressed.sources())
          .extracting(SourceReference::excerpt)
          .doesNotContain(neighbor.getChunkText().strip());
    }
  }

  private KnowledgeRetrievalServiceImpl newService() {
    return new KnowledgeRetrievalServiceImpl(
        authorizationService,
        embeddingService,
        vectorStore,
        new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider),
        dynamicConfigProvider,
        null,
        null,
        new DefaultWeightedReranker(new Bm25Scorer(), dynamicConfigProvider),
        null,
        new ContextBuilder(
            chunkMapper,
            dynamicConfigProvider,
            new RuleContextCompressor(),
            (LlmContextCompressor) null));
  }

  /** 按行首递增编号切分上下文段：返回第 n 段内容（含段头与其后正文行），序号必须从 1 连续递增。 */
  private List<String> numberedSections(String context) {
    List<StringBuilder> builders = new ArrayList<>();
    StringBuilder current = null;
    int expected = 1;
    for (String line : context.split("\n", -1)) {
      Matcher matcher = SECTION_HEADER.matcher(line);
      if (matcher.find() && Integer.parseInt(matcher.group(1)) == expected) {
        current = new StringBuilder(line);
        builders.add(current);
        expected++;
      } else if (current != null) {
        current.append('\n').append(line);
      }
    }
    List<String> sections = new ArrayList<>(builders.size());
    for (StringBuilder builder : builders) {
      sections.add(builder.toString());
    }
    return sections;
  }

  private DocumentVectorChunkEntity neighbor(int index, String docId, String text, long pageNo) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId((long) index);
    entity.setDocumentId(docId);
    entity.setChunkIndex(index);
    entity.setChunkText(text);
    entity.setPageNo((int) pageNo);
    return entity;
  }

  /** 命中块构造定义（模拟基准用例的语料形态）：marker 为含数字的唯一锚点词， 随命中文本进入上下文与来源摘录，用于压缩后的双向定位断言。 */
  private record HitDef(String chunkId, String docId, int chunkIndex, long pageNo, String text) {

    /** 唯一锚点词（含数字，承重句口径，压缩后大概率保留）。 */
    private String marker() {
      int at = text.indexOf("（锚点");
      int end = text.indexOf("）", at);
      return text.substring(at + 1, end);
    }
  }
}
