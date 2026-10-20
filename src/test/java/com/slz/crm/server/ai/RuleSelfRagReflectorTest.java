package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.SourceReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 规则反思层（R 档）单测（add-self-rag-reflection 任务 1.2）。
 *
 * <p>测试四类 case：引用编号越界、指向空块、引用与命中源不匹配、全剥除后空引用边界（D16 语义不破坏）。 均为纯 JVM 确定性测试，零外呼、零 Spring 依赖。
 */
class RuleSelfRagReflectorTest {

  private final RuleSelfRagReflector reflector = new RuleSelfRagReflector();

  private static SourceReference src(String excerpt) {
    return new SourceReference(
        "md", "vector", "doc.md", "doc-1", "chunk-1", 0, 1, null, excerpt, 0.9d);
  }

  @Test
  @DisplayName("case 1 越界：引用编号超出 sources 范围时确定性剥除并软降级")
  void outOfBoundsCitationStripped() {
    List<SourceReference> sources = List.of(src("知识库检索包含向量召回与重排"), src("混合检索融合支持 RRF 算法"));
    String answer = "知识库检索包含向量召回与重排[1]，另外系统还支持分布式分片[5]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    // 编号 5 越界被剥除，仅保留有效编号 1
    assertThat(result.citations()).containsExactly(1);
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
    assertThat(result.answer()).startsWith("知识库检索包含向量召回与重排[1]");
  }

  @Test
  @DisplayName("case 2 空块：引用指向空 excerpt 时确定性剥除并软降级")
  void emptyChunkCitationStripped() {
    List<SourceReference> sources = List.of(src("   "), src("混合检索融合支持 RRF 算法"));
    String answer = "系统支持空块检索[1]，混合检索融合支持 RRF 算法[2]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    // 编号 1 指向空白块被剥除，保留编号 2
    assertThat(result.citations()).containsExactly(2);
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
  }

  @Test
  @DisplayName("case 3 不匹配：引用断言与命中源无支撑关系时确定性剥除")
  void mismatchedCitationStripped() {
    List<SourceReference> sources = List.of(src("服务器基础环境配置为 16 核 64G 内存"));
    String answer = "公司今天全员放假三天并举办年会活动[1]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    // 编号 1 与 sources[0] 毫不相关，支撑分低于阈值被剥除
    assertThat(result.citations()).isEmpty();
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
  }

  @Test
  @DisplayName("case 4 全剥除后空引用边界：所有引用被剥除后 citations 为空，保留文本，D16 语义不破坏")
  void allStrippedBoundaryPreservesTextAndEmptyCitations() {
    // [1] 指向空块，[3] 越界
    List<SourceReference> sources = List.of(src(""), src("数据库采用 MySQL 8.0 引擎"));
    String answer = "这里引用了空块[1]，这里引用了越界块[3]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    assertThat(result.citations()).isEmpty();
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
    // 答案原本正文保留
    assertThat(result.answer()).contains("这里引用了空块[1]，这里引用了越界块[3]。");
  }

  @Test
  @DisplayName("正向 case：所有引用均有充分支撑时原样保留，不加尾注")
  void wellSupportedCitationsRetainedWithoutNote() {
    List<SourceReference> sources = List.of(src("知识库检索包含向量召回与重排机制"), src("双路混合检索融合支持 RRF 算法规则"));
    String answer = "知识库检索包含向量召回与重排机制[1]，双路混合检索融合支持 RRF 算法规则[2]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    assertThat(result.citations()).containsExactly(1, 2);
    assertThat(result.answer()).doesNotContain(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
    assertThat(result.answer()).isEqualTo(answer);
  }

  // ==================== ⑫ 阈值注入（fix-backlog-batch2 任务 3.1） ====================

  /** 部分支撑的样本：默认 keepMin(0.12) 下保留，抬到 0.99 后可被剥除。 */
  private static final String PARTIAL_ANSWER = "系统支持混合检索融合[1]。";

  private static List<SourceReference> partialSources() {
    return List.of(src("混合检索融合支持 RRF 算法规则"));
  }

  @Test
  @DisplayName("⑫ 注入生效：keepMin 抬到 0.99 → 原本保留的部分支撑引用被剥除")
  void injectedHigherKeepMin_stripsWeakCitation() {
    RuleSelfRagReflector strict =
        new RuleSelfRagReflector(() -> new CitationThresholds(0.99d, 0.22d, 0.08d));

    // 默认阈值下同一输入是保留的（见下一个断言，锁定对照基线）
    assertThat(reflector.reflect(PARTIAL_ANSWER, partialSources()).citations()).containsExactly(1);
    SelfRagResult strictResult = strict.reflect(PARTIAL_ANSWER, partialSources());

    assertThat(strictResult.citations()).isEmpty();
    assertThat(strictResult.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
  }

  @Test
  @DisplayName("⑫ 注入生效：keepMin 降到 0 → 默认判为不匹配的引用被保留")
  void injectedLowerKeepMin_keepsWeakCitation() {
    List<SourceReference> sources = List.of(src("服务器基础环境配置为 16 核 64G 内存"));
    String answer = "公司今天全员放假三天并举办年会活动[1]。";
    RuleSelfRagReflector lenient =
        new RuleSelfRagReflector(() -> new CitationThresholds(0.0d, 0.22d, 0.08d));

    // 默认阈值下该引用被剥除（既有 case 3），阈值降到 0 后保留
    assertThat(reflector.reflect(answer, sources).citations()).isEmpty();
    assertThat(lenient.reflect(answer, sources).citations()).containsExactly(1);
  }

  @Test
  @DisplayName("⑫ 默认回归：Supplier 返回 null 时回落 defaults()，与无参构造逐字等价")
  void nullSupplierFallsBackToDefaults() {
    RuleSelfRagReflector nullSupplier = new RuleSelfRagReflector(() -> null);

    assertThat(nullSupplier.reflect(PARTIAL_ANSWER, partialSources()).citations())
        .containsExactly(1);
    assertThat(nullSupplier.reflect(PARTIAL_ANSWER, partialSources()).answer())
        .isEqualTo(reflector.reflect(PARTIAL_ANSWER, partialSources()).answer());
  }

  @Test
  @DisplayName("⑫ 默认回归：动态配置服务存在但三键未配置 → 走解析器仍与 defaults() 等价")
  void resolverWithEmptyConfig_matchesDefaults() {
    RuleSelfRagReflector resolved = reflectorWith(new FakeConfig(Map.of()));

    assertThat(resolved.reflect(PARTIAL_ANSWER, partialSources()).citations()).containsExactly(1);
    assertThat(resolved.reflect(PARTIAL_ANSWER, partialSources()).answer())
        .isEqualTo(reflector.reflect(PARTIAL_ANSWER, partialSources()).answer());
  }

  @Test
  @DisplayName("⑫ 注入生效：动态配置 keepMin=0.99 经解析器生效（同一入口热改即变）")
  void resolverWithConfiguredKeepMin_changesBehaviour() {
    RuleSelfRagReflector resolved =
        reflectorWith(new FakeConfig(Map.of(CitationThresholdsResolver.KEY_KEEP_MIN, 0.99d)));

    SelfRagResult result = resolved.reflect(PARTIAL_ANSWER, partialSources());

    assertThat(result.citations()).isEmpty();
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
  }

  @Test
  @DisplayName("⑫ 越界配置逐键回落：keepMin=1.5 → 解析器回落默认，仍保留部分支撑引用")
  void resolverWithOutOfRangeKeepMin_fallsBackToDefault() {
    RuleSelfRagReflector resolved =
        reflectorWith(new FakeConfig(Map.of(CitationThresholdsResolver.KEY_KEEP_MIN, 1.5d)));

    assertThat(resolved.reflect(PARTIAL_ANSWER, partialSources()).citations()).containsExactly(1);
  }

  /** 走 Spring 装配构造（ObjectProvider 形态），锁定生产注入路径。 */
  @SuppressWarnings("unchecked")
  private static RuleSelfRagReflector reflectorWith(DynamicConfigService config) {
    ObjectProvider<DynamicConfigService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(config);
    return new RuleSelfRagReflector(provider);
  }

  /** 确定性 fake：只按 key 命中，未命中返回调用方默认值（与真实实现的「未配置」语义一致）。 */
  private static final class FakeConfig implements DynamicConfigService {

    private final Map<String, Double> values;

    private FakeConfig(Map<String, Double> values) {
      this.values = values;
    }

    @Override
    @SuppressWarnings("unchecked") // fake：仅回填 Double 型键，调用方只会按 Double.class 读取
    public <T> T get(String key, Class<T> type, T defaultValue) {
      Double stored = values.get(key);
      return stored == null ? defaultValue : (T) stored;
    }
  }
}
