package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.platform.contract.SourceReference;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
