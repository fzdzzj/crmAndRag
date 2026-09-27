package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.knowledge.retrieval.TokenEstimator;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * ContextBuilder 邻居拼装单测（add-context-compression-and-enrichment 任务 1.1/1.3/1.4）：
 * 前/后邻居拼装、首末块与跨文档边界、开关关闭回退升级前行为、DB 异常降级、同页优先。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextBuilderTest {
  private static final String DOC_ID = "doc-1";
  private static final String NEIGHBORS_KEY = "rag.context.neighbors";

  @Mock private DocumentVectorChunkMapper chunkMapper;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private DynamicConfigService dynamicConfigService;

  @BeforeEach
  void setUp() {
    // 缺省：配置可用且未设置邻居键 → DynamicConfigService 返回调用方默认值 1（默认开启）
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
  }

  /** 拼装：命中块前后都有邻居 → （前文承接）… 命中 …（后文承接）…，编号与候选下标一致。 */
  @Test
  void shouldAssemblePrevAndNextNeighbors() {
    stubNeighborRows(row(1, "前一页结尾内容", 2L), row(3, "后一页开头内容", 3L));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);

    String context = builder.build(List.of(candidate("c2", 2, "命中块正文", 3L)));

    assertThat(context).isEqualTo("[1] （前文承接）前一页结尾内容\n命中块正文\n（后文承接）后一页开头内容");
  }

  /** 边界（任务 1.3）：文档首块无前置邻居，只拼后置。 */
  @Test
  void firstChunkShouldHaveNoPrevNeighbor() {
    stubNeighborRows(row(1, "第二块内容", 1L));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);

    String context = builder.build(List.of(candidate("c0", 0, "首块正文", 1L)));

    assertThat(context).doesNotContain("前文承接");
    assertThat(context).startsWith("[1] 首块正文");
    assertThat(context).contains("（后文承接）第二块内容");
  }

  /** 边界（任务 1.3）：文档末块无后置邻居（快照表查不到 index+1 行），只拼前置。 */
  @Test
  void lastChunkShouldHaveNoNextNeighbor() {
    stubNeighborRows(row(4, "倒数第二块内容", 2L));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);

    String context = builder.build(List.of(candidate("c5", 5, "末块正文", 2L)));

    assertThat(context).doesNotContain("后文承接");
    assertThat(context).contains("（前文承接）倒数第二块内容");
    assertThat(context).endsWith("末块正文");
  }

  /** 边界（任务 1.3）：快照表返回其他文档的切片行时不得拼入（跨文档不取邻居，双保险过滤）。 */
  @Test
  void neighborFromOtherDocumentMustBeIgnored() {
    DocumentVectorChunkEntity foreign = row(1, "其他文档内容", 1L);
    foreign.setDocumentId("doc-OTHER");
    when(chunkMapper.selectList(any())).thenReturn(List.of(foreign));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);

    String context = builder.build(List.of(candidate("c2", 2, "命中块正文", 1L)));

    assertThat(context).doesNotContain("其他文档内容");
    assertThat(context).doesNotContain("前文承接");
    assertThat(context).isEqualTo("[1] 命中块正文");
  }

  /** 开关回退（任务 1.4）：rag.context.neighbors=0 时输出与升级前纯拼接逐字一致。 */
  @Test
  void switchOffShouldFallBackToPlainNumberedContext() {
    when(dynamicConfigService.get(NEIGHBORS_KEY, Integer.class, 1)).thenReturn(0);
    stubNeighborRows(row(1, "前一页结尾内容", 1L), row(3, "后一页开头内容", 2L));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);
    List<RetrievalCandidate> candidates =
        List.of(candidate("c2", 2, "命中块正文", 2L), candidate("c9", 9, "  另一块正文  ", 3L));

    String context = builder.build(candidates);

    assertThat(context).isEqualTo("[1] 命中块正文\n[2] 另一块正文");
  }

  /** 同页优先（任务 1.1）：同序号数据异常出现两行时，取与命中块同页的行。 */
  @Test
  void samePageNeighborShouldBePreferredOnDuplicateIndex() {
    DocumentVectorChunkEntity crossPage = row(1, "跨页邻居", 7L);
    crossPage.setId(1L);
    DocumentVectorChunkEntity samePage = row(1, "同页邻居", 2L);
    samePage.setId(2L);
    when(chunkMapper.selectList(any())).thenReturn(List.of(crossPage, samePage));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);

    String context = builder.build(List.of(candidate("c2", 2, "命中块正文", 2L)));

    assertThat(context).contains("同页邻居").doesNotContain("跨页邻居");
  }

  /** 失败边界：快照表查询异常 → 无邻居降级，命中块照常输出，不抛错。 */
  @Test
  void dbFailureShouldDegradeToNoNeighbor() {
    when(chunkMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);

    String context = builder.build(List.of(candidate("c2", 2, "命中块正文", 1L)));

    assertThat(context).isEqualTo("[1] 命中块正文");
  }

  /**
   * 无效索引守卫（fix-neighbor-missing-chunk-index-fallback，spec-delta 场景一）：metadata 缺失 chunkIndex 时
   * 不得拆箱空值抛 NPE；该命中保留原 [n] 编号与正文，且不产生邻居查询目标。
   */
  @Test
  void missingChunkIndexMetadataKeepsHitTextWithoutNeighborQuery() {
    when(chunkMapper.selectList(any())).thenReturn(List.of());
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);
    RetrievalCandidate candidate = candidateWithMetadata("benchdoc-7", "缺失索引命中", metadataOf(null));

    String context = builder.build(List.of(candidate));

    assertThat(context).isEqualTo("[1] 缺失索引命中");
    verify(chunkMapper, never()).selectList(any());
  }

  /** 无效索引守卫（spec-delta 场景一）：chunkIndex 字段为非 Number（此处为字符串）时同样按无效索引降级， 不抛 NPE、不拼邻居。 */
  @Test
  void nonNumericChunkIndexMetadataKeepsHitTextWithoutNeighborQuery() {
    when(chunkMapper.selectList(any())).thenReturn(List.of());
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);
    RetrievalCandidate candidate = candidateWithMetadata("benchdoc-8", "非数字索引命中", metadataOf("2"));

    String context = builder.build(List.of(candidate));

    assertThat(context).isEqualTo("[1] 非数字索引命中");
    verify(chunkMapper, never()).selectList(any());
  }

  /**
   * 负索引守卫（spec-delta 场景二）：同文档一条负索引命中与一条有效索引命中同批，后者预取行含索引 0； 负索引命中只有自身文本，不得借用索引 0
   * 作为后置邻居，有效命中的前后邻居不受污染。
   */
  @Test
  void negativeChunkIndexHitMustNotBorrowIndexZeroNeighborFromSameBatch() {
    stubNeighborRows(row(0, "第零块", 1L), row(2, "第二块", 1L));
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);
    RetrievalCandidate negativeHit = candidateWithMetadata("benchdoc-neg", "负索引命中", metadataOf(-1));
    RetrievalCandidate validHit = candidateWithMetadata("benchdoc-pos", "有效命中", metadataOf(1));

    String context = builder.build(List.of(negativeHit, validHit));

    assertThat(context).isEqualTo("[1] 负索引命中\n[2] （前文承接）第零块\n有效命中\n（后文承接）第二块");
  }

  /** 多命中块编号独立：编号只与候选顺序绑定，邻居缺失不影响后续块编号。 */
  @Test
  void multipleHitsKeepIndependentNumbering() {
    when(chunkMapper.selectList(any())).thenReturn(List.of());
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);

    String context =
        builder.build(List.of(candidate("c2", 2, "命中块A", 1L), candidate("c7", 7, "命中块B", 1L)));

    assertThat(context).isEqualTo("[1] 命中块A\n[2] 命中块B");
  }

  /** 预算触发（任务 2.4）：拼装结果超预算时经规则压缩收敛到预算内；编号仍完整。 */
  @Test
  void overBudgetContextShouldTriggerCompression() {
    when(chunkMapper.selectList(any())).thenReturn(List.of());
    ContextBuilder builder =
        new ContextBuilder(chunkMapper, dynamicConfigProvider, new RuleContextCompressor(), null);
    String text1 = "首句含数字金额15万元。填充内容一句。填充内容二句。填充内容三句。填充内容四句。末句收尾。";
    String text2 = "第二段首句。第二段填充一句。第二段填充二句。第二段填充三句。第二段填充四句。第二段末句。";
    List<RetrievalCandidate> candidates =
        List.of(candidate("c1", 1, text1, 1L), candidate("c2", 2, text2, 1L));
    int plainTokens = TokenEstimator.estimate("[1] " + text1 + "\n[2] " + text2);
    int budget = plainTokens / 2;
    when(dynamicConfigService.get("rag.context.token-budget", Integer.class, 4096))
        .thenReturn(budget);

    String context = builder.build(candidates);

    assertThat(TokenEstimator.estimate(context)).isLessThanOrEqualTo(budget);
    assertThat(context).contains("[1]").contains("[2]");
  }

  private void stubNeighborRows(DocumentVectorChunkEntity... rows) {
    when(chunkMapper.selectList(any())).thenReturn(List.of(rows));
  }

  /** 构造 metadata：filename/pageNo 固定；chunkIndex 允许缺失（null）、非 Number 或负数。 */
  private Map<String, Object> metadataOf(Object chunkIndex) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("filename", "sales.txt");
    metadata.put("pageNo", 1);
    if (chunkIndex != null) {
      metadata.put("chunkIndex", chunkIndex);
    }
    return metadata;
  }

  private RetrievalCandidate candidateWithMetadata(
      String chunkId, String text, Map<String, Object> metadata) {
    return new RetrievalCandidate(new VectorSearchHit(chunkId, DOC_ID, 0.8d, text, metadata), 0.8d);
  }

  private RetrievalCandidate candidate(String chunkId, int chunkIndex, String text, long pageNo) {
    Map<String, Object> metadata =
        Map.of(
            "chunkIndex", chunkIndex,
            "filename", "sales.txt",
            "pageNo", pageNo);
    return new RetrievalCandidate(new VectorSearchHit(chunkId, DOC_ID, 0.8d, text, metadata), 0.8d);
  }

  private DocumentVectorChunkEntity row(int chunkIndex, String text, long pageNo) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId((long) chunkIndex);
    entity.setDocumentId(DOC_ID);
    entity.setChunkIndex(chunkIndex);
    entity.setChunkText(text);
    entity.setPageNo((int) pageNo);
    return entity;
  }
}
