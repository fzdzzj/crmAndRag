package com.slz.crm.knowledge.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.qdrant.client.grpc.Points.ScoredPoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Qdrant 分数映射回归的纯 JVM 契约锁定（fix-qdrant-search-score-conversion）。
 *
 * <p><b>背景</b>：生产 {@link QdrantVectorStore#search} 曾把反射返回的 {@code ScoredPoint.getScore()}（client
 * 1.13.0 返回 原始 {@code float}，反射装箱为 {@code Float}）直接 {@code (Double)} 强转——任何非空命中必抛 {@code
 * ClassCastException}， 真实 Qdrant 首次暴露于 2026-09-25 本机隔离度量（docs/representative-hotpath-measurement.md
 * §5）。本测试零 Docker、零模型调用， 锁定三层不变量：
 *
 * <ol>
 *   <li>gRPC 契约：{@code getScore()} 是原始 {@code float}——任何把反射结果当 {@code Double} 的写法都类型不成立；
 *   <li>装箱反例：{@code Float} 实例 {@code (Double)} 强转必抛（防"换个客户端就好了"的误判）；
 *   <li>源码 tripwire：生产 search 不得再把反射 score 结果写成 {@code (Double)} 强转（防回归写回）。
 * </ol>
 *
 * <p>真实 Qdrant 端到端非空命中回归见 {@code QdrantVectorStoreSearchRealIT}（failsafe，Docker+镜像守卫）。
 */
class QdrantScoreMappingContractTest {

  /** 生产源文件相对 surefire 工作目录（= 仓库根）的路径。 */
  static final String STORE_SOURCE =
      "src/main/java/com/slz/crm/knowledge/vector/QdrantVectorStore.java";

  /** 旧缺陷表达式的特征片段（修复后不得再出现）。 */
  static final String FORBIDDEN_CAST = "(Double) SCORED_POINT_CLASS.getMethod(\"getScore\")";

  /** 契约 1：client 1.13.0 的 ScoredPoint.getScore() 返回原始 float——反射装箱后是 Float 不是 Double。 */
  @Test
  void scoredPointGetScoreIsPrimitiveFloat() throws Exception {
    assertEquals(
        float.class,
        ScoredPoint.class.getMethod("getScore").getReturnType(),
        "Qdrant client 的 getScore() 若改为 double，本案装箱前提变化，需重审映射写法");
  }

  /** 契约 2（反例）：装箱 Float 对 (Double) 引用强转必抛 ClassCastException——旧表达式对任何命中都不可能成立。 */
  @Test
  void boxedFloatCannotBeCastToDouble() {
    Object reflectedScore = Float.valueOf(0.75f);
    assertThrows(
        ClassCastException.class,
        () -> {
          Double unused = (Double) reflectedScore;
        },
        "Float→Double 引用强转必须失败；若此断言翻红说明 JVM 语义或类型前提高级，需重审修复口径");
  }

  /** 修复语义：Number.doubleValue 是数值转换，0.75f → 0.75d 精确保真。 */
  @Test
  void numberDoubleValueConvertsFloatWithoutLoss() {
    Object reflectedScore = Float.valueOf(0.75f);
    double score = ((Number) reflectedScore).doubleValue();
    assertEquals(0.75d, score, 0.0d, "float 0.75f 经 doubleValue 应精确等于 double 0.75");
  }

  /** 契约 3（tripwire）：生产 search 源码不得再把反射 score 写回 (Double) 强转。 */
  @Test
  void productionSearchMustNotCastReflectedScoreToDouble() throws Exception {
    Path source = Path.of(STORE_SOURCE);
    assertTrue(
        Files.isRegularFile(source), "生产 QdrantVectorStore 源文件应在位：" + source.toAbsolutePath());
    String content = Files.readString(source, StandardCharsets.UTF_8);
    assertNotNull(content);
    assertFalse(
        content.contains(FORBIDDEN_CAST),
        "生产 search 不得再把反射 score 结果 (Double) 强转——真实 Qdrant 非空命中会抛 ClassCastException（本测试防止回归写回）");
  }
}
