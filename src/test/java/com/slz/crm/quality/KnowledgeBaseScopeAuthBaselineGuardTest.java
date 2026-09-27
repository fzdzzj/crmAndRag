package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * 单库 scope 授权基线的安全反例与边界锁定（add-knowledge-base-scope-auth-baseline 阶段 2 任务 2.3）。
 *
 * <p><b>默认执行的纯 JVM 守卫</b>：零 Docker 容器、零外发、零本案启动。锁定六条边界：
 *
 * <ol>
 *   <li>度量入口类名不满足 surefire/failsafe 任何默认发现规则——默认 {@code mvn test} / {@code mvn verify} 发现不到它；
 *   <li>独立 opt-in 开关缺失时入口 fail closed（在任何容器/装配动作之前抛错）；
 *   <li>镜像只读预检对不存在的镜像报告缺失（不触发拉取）；
 *   <li>钉扎镜像不是 latest 等漂移标签；
 *   <li>scope 解析与求交的冻结语义：重复/非数字/溢出/负数/空/null 的确定性口径为纯 JVM 可判定；
 *   <li>无残留时 ThreadLocal 清理断言可调用。
 * </ol>
 */
class KnowledgeBaseScopeAuthBaselineGuardTest {

  /** 反例 1：入口类名必须逃过 surefire（*Test/*Tests）与 failsafe（*IT/*IntegrationTest）全部默认发现规则。 */
  @Test
  void benchmarkClassNameMustEscapeDefaultDiscovery() {
    String simpleName = KnowledgeBaseScopeAuthBaselineBenchmark.class.getSimpleName();
    for (String suffix : List.of("Test", "Tests", "IT", "IntegrationTest")) {
      assertFalse(simpleName.endsWith(suffix), "度量入口类名以 " + suffix + " 结尾会被默认测试发现，违反「默认测试发现不到」规格");
    }
    assertEquals("KnowledgeBaseScopeAuthBaselineBenchmark", simpleName);
  }

  /** 反例 2：独立 opt-in 缺失 → 在任何容器/装配之前 fail closed（本测试进程未设置该开关）。 */
  @Test
  void missingOptInMustFailClosedBeforeAnySetup() {
    Assumptions.assumeTrue(
        !"1".equals(System.getenv(KnowledgeBaseScopeAuthBaselineBenchmark.OPT_IN_ENV)),
        "本机已设置 " + KnowledgeBaseScopeAuthBaselineBenchmark.OPT_IN_ENV + "=1，跳过「缺开关 fail closed」反例");
    try {
      KnowledgeBaseScopeAuthBaselineBenchmark.requireOptIn();
      throw new AssertionError("缺独立 opt-in 时度量入口必须拒绝运行，而不是放行");
    } catch (IllegalStateException expected) {
      assertTrue(
          expected.getMessage().contains(KnowledgeBaseScopeAuthBaselineBenchmark.OPT_IN_ENV),
          "fail-closed 信息必须指名独立开关，而不是含糊其辞");
      assertTrue(expected.getMessage().contains("未测"), "fail-closed 必须显式报告「未测」，不得假装成功");
    }
  }

  /** 反例 3：镜像缺失必须被只读预检点名（不触发拉取）。 */
  @Test
  void missingImageMustBeReportedNotPulled() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过镜像预检反例（该路径由真实度量入口的 fail closed 覆盖）");
    String phantom = "kbscope-phantom-image:does-not-exist";
    assertEquals(
        List.of(phantom),
        KnowledgeBaseScopeAuthBaselineBenchmark.missingLocalImages(List.of(phantom)),
        "不存在的镜像必须被报告为缺失（只读 inspect，不触发拉取）");
  }

  /** 反例 4：钉扎镜像不能是 latest 等漂移标签。 */
  @Test
  void pinnedImageMustBeImmutableReference() {
    assertEquals("mysql:8.0", KnowledgeBaseScopeAuthBaselineBenchmark.DEFAULT_MYSQL_IMAGE);
    assertFalse(
        KnowledgeBaseScopeAuthBaselineBenchmark.DEFAULT_MYSQL_IMAGE.endsWith(":latest"),
        "钉扎镜像不允许 latest 漂移标签：" + KnowledgeBaseScopeAuthBaselineBenchmark.DEFAULT_MYSQL_IMAGE);
  }

  /** 佐证 5：scope 解析的冻结语义——非数字/溢出被忽略，重复去重，负数仍解析为合法 long。 */
  @Test
  void scopeParsingMatchesFrozenSemantics() {
    assertEquals(
        Set.of(7L, 9L),
        KnowledgeBaseScopeAuthBaselineBenchmark.parseScopes(List.of("7", "7", "9")),
        "重复 scope 必须去重且保序语义由 LinkedHashSet 承担");
    assertEquals(
        Set.of(7L),
        KnowledgeBaseScopeAuthBaselineBenchmark.parseScopes(List.of("7", "abc")),
        "非数字 scope 必须被忽略，不放大授权");
    assertTrue(
        KnowledgeBaseScopeAuthBaselineBenchmark.parseScopes(
                List.of(KnowledgeBaseScopeAuthBaselineBenchmark.OVERFLOW_SCOPE))
            .isEmpty(),
        "溢出 long 的 scope 必须被忽略（NumberFormatException 分支）");
    assertEquals(
        Set.of(-1L),
        KnowledgeBaseScopeAuthBaselineBenchmark.parseScopes(List.of("-1")),
        "负数可被 Long.valueOf 解析为合法 long，是否放行取决于是否在可见集中");
    assertTrue(
        KnowledgeBaseScopeAuthBaselineBenchmark.parseScopes(List.of()).isEmpty(),
        "空 scope 列表解析为空集");
    assertTrue(
        KnowledgeBaseScopeAuthBaselineBenchmark.parseScopes(null).isEmpty(),
        "null scope 解析为空集（与生产 null 分支一致）");
  }

  /** 佐证 6：空 scope 返回全部可见集且保序；非空 scope 与可见集求交并保持可见集顺序。 */
  @Test
  void authorizationIntersectionMatchesFrozenSemantics() {
    List<Long> visible = List.of(101L, 205L, 309L, 412L);
    assertEquals(
        visible,
        KnowledgeBaseScopeAuthBaselineBenchmark.expectedAuthorized(visible, List.of()),
        "空 scope 必须返回全部可见集且保序");
    assertEquals(
        visible,
        KnowledgeBaseScopeAuthBaselineBenchmark.expectedAuthorized(visible, null),
        "null scope 必须返回全部可见集且保序");
    assertEquals(
        List.of(205L, 412L),
        KnowledgeBaseScopeAuthBaselineBenchmark.expectedAuthorized(
            visible, List.of("412", "205", "999")),
        "求交必须保持可见集顺序，且不含于可见集的 999 被剔除");
    assertEquals(
        List.of(),
        KnowledgeBaseScopeAuthBaselineBenchmark.expectedAuthorized(
            visible, List.of("abc", KnowledgeBaseScopeAuthBaselineBenchmark.OVERFLOW_SCOPE)),
        "全无效 scope 必须收敛为空集，绝不放大授权");
  }

  /** 佐证 7：无残留时 ThreadLocal 清理断言可正常通过（纯 JVM，不启动容器）。 */
  @Test
  void threadLocalCleanAssertionPassesWhenNothingResidual() {
    KnowledgeBaseScopeAuthBaselineBenchmark.assertThreadLocalClean();
  }
}
