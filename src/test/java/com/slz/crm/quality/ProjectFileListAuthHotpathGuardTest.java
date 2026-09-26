package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * 项目文件列表鉴权热路径度量的安全反例与边界锁定（update-project-file-list-auth-hotpath 任务 1.3）。
 *
 * <p><b>默认执行的纯 JVM 守卫</b>：零 Docker 容器、零外发、零本案启动。锁定五条边界：
 *
 * <ol>
 *   <li>度量入口类名不满足 surefire/failsafe 任何默认发现规则——默认 {@code mvn test} / {@code mvn verify} 根本发现不到它；
 *   <li>独立 opt-in 开关缺失时入口 fail closed（在任何容器/装配动作之前抛错）；
 *   <li>镜像只读预检对不存在的镜像报告缺失（不触发拉取；真实度量入口在容器启动前就失败）；
 *   <li>钉扎镜像不是 latest 等漂移标签；
 *   <li>期望可读性/分页语义（超管直通、冻结/离职零可见、部分可见隔行、全无权整组零可见、空页但 total 非零）为纯 JVM 可判定的确定性口径，且 ThreadLocal
 *       清理断言在无残留时可调用。
 * </ol>
 */
class ProjectFileListAuthHotpathGuardTest {

  /** 反例 1：入口类名必须逃过 surefire（*Test/*Tests）与 failsafe（*IT/*IntegrationTest）全部默认发现规则。 */
  @Test
  void benchmarkClassNameMustEscapeDefaultDiscovery() {
    String simpleName = ProjectFileListAuthHotpathBenchmark.class.getSimpleName();
    for (String suffix : List.of("Test", "Tests", "IT", "IntegrationTest")) {
      assertTrue(!simpleName.endsWith(suffix), "度量入口类名以 " + suffix + " 结尾会被默认测试发现，违反「默认测试发现不到」规格");
    }
    assertEquals("ProjectFileListAuthHotpathBenchmark", simpleName);
  }

  /** 反例 2：独立 opt-in 缺失 → 在任何容器/装配之前 fail closed（本测试进程未设置该开关）。 */
  @Test
  void missingOptInMustFailClosedBeforeAnySetup() {
    Assumptions.assumeTrue(
        !"1".equals(System.getenv(ProjectFileListAuthHotpathBenchmark.OPT_IN_ENV)),
        "本机已设置 " + ProjectFileListAuthHotpathBenchmark.OPT_IN_ENV + "=1，跳过「缺开关 fail closed」反例");
    try {
      ProjectFileListAuthHotpathBenchmark.requireOptIn();
      throw new AssertionError("缺独立 opt-in 时度量入口必须拒绝运行，而不是放行");
    } catch (IllegalStateException expected) {
      assertTrue(
          expected.getMessage().contains(ProjectFileListAuthHotpathBenchmark.OPT_IN_ENV),
          "fail-closed 信息必须指名独立开关，而不是含糊其辞");
      assertTrue(expected.getMessage().contains("未测"), "fail-closed 必须显式报告「未测」，不得假装成功");
    }
  }

  /** 反例 3：镜像缺失必须被只读预检点名（不触发拉取——入口在容器启动前就失败）。 */
  @Test
  void missingImageMustBeReportedNotPulled() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过镜像预检反例（该路径由真实度量入口的 fail closed 覆盖）");
    String phantom = "pflhot-phantom-image:does-not-exist";
    List<String> missing = ProjectFileListAuthHotpathBenchmark.missingLocalImages(List.of(phantom));
    assertEquals(List.of(phantom), missing, "不存在的镜像必须被报告为缺失（只读 inspect，不触发拉取）");
  }

  /** 反例 4：钉扎镜像不能是 latest 等漂移标签。 */
  @Test
  void pinnedImageMustBeImmutableReference() {
    assertEquals("mysql:8.0", ProjectFileListAuthHotpathBenchmark.DEFAULT_MYSQL_IMAGE);
    assertTrue(
        !ProjectFileListAuthHotpathBenchmark.DEFAULT_MYSQL_IMAGE.endsWith(":latest"),
        "钉扎镜像不允许 latest 漂移标签：" + ProjectFileListAuthHotpathBenchmark.DEFAULT_MYSQL_IMAGE);
  }

  /** 反例 5：无残留时 ThreadLocal 清理断言可正常通过（纯 JVM，不启动容器）。 */
  @Test
  void threadLocalCleanAssertionPassesWhenNothingResidual() {
    ProjectFileListAuthHotpathBenchmark.assertThreadLocalClean();
  }

  /** 佐证 6：冻结口径的期望可读性——超管整组可见、部分可见隔行、全无权整组不可见、冻结/离职零可见。 */
  @Test
  void expectedVisibilityMatchesFrozenSemantics() {
    ProjectFileListAuthHotpathBenchmark.Condition all =
        ProjectFileListAuthHotpathBenchmark.condition(
            "ALL-100", "ALL", ProjectFileListAuthHotpathBenchmark.SALES_USER_ID, 100);
    List<Long> allPage2 = ProjectFileListAuthHotpathBenchmark.expectedIds(all, 2);
    assertEquals(20, allPage2.size(), "全可见：100 页第 2 页应为 120-100=20 条");
    assertEquals(1019L, allPage2.get(0), "全可见：首页应为最大 uploadTime 的 ID，按倒序");
    assertEquals(1018L, allPage2.get(1), "全可见：第二条应为紧邻的次大 uploadTime 的 ID");
    assertEquals(1000L, allPage2.get(19), "全可见：第 2 页末条应为本组最小 uploadTime 的 ID");
    assertTrue(ProjectFileListAuthHotpathBenchmark.readable(all, 0), "全可见组任意索引应可读");

    ProjectFileListAuthHotpathBenchmark.Condition part =
        ProjectFileListAuthHotpathBenchmark.condition(
            "PART-10", "PART", ProjectFileListAuthHotpathBenchmark.SALES_USER_ID, 10);
    assertEquals(
        List.of(2118L, 2116L, 2114L, 2112L, 2110L),
        ProjectFileListAuthHotpathBenchmark.expectedIds(part, 1),
        "部分可见：隔行可读（i%2==0），首页应只含偶数索引");
    assertTrue(ProjectFileListAuthHotpathBenchmark.readable(part, 0), "偶数索引应可读");
    assertFalse(ProjectFileListAuthHotpathBenchmark.readable(part, 1), "奇数索引应不可读");

    ProjectFileListAuthHotpathBenchmark.Condition admin =
        ProjectFileListAuthHotpathBenchmark.condition(
            "ADMIN-10", "PART", ProjectFileListAuthHotpathBenchmark.ADMIN_USER_ID, 10);
    assertEquals(
        10, ProjectFileListAuthHotpathBenchmark.expectedIds(admin, 1).size(), "超管直通：部分可见组对超管应整页可见");

    ProjectFileListAuthHotpathBenchmark.Condition frozen =
        ProjectFileListAuthHotpathBenchmark.condition(
            "FROZEN-10", "ALL", ProjectFileListAuthHotpathBenchmark.FROZEN_USER_ID, 10);
    ProjectFileListAuthHotpathBenchmark.Condition leaver =
        ProjectFileListAuthHotpathBenchmark.condition(
            "LEAVER-10", "ALL", ProjectFileListAuthHotpathBenchmark.LEAVER_USER_ID, 10);
    assertTrue(
        ProjectFileListAuthHotpathBenchmark.expectedIds(frozen, 1).isEmpty(), "冻结用户：即便全可见组也应零可见");
    assertTrue(
        ProjectFileListAuthHotpathBenchmark.expectedIds(leaver, 1).isEmpty(), "离职用户：即便全可见组也应零可见");
  }

  /** 佐证 7：空页但 total 非零——全无权组第二页 records 为空，而数据库条件总数仍为整组大小。 */
  @Test
  void emptyPageKeepsDatabaseTotal() {
    ProjectFileListAuthHotpathBenchmark.Condition none =
        ProjectFileListAuthHotpathBenchmark.condition(
            "NONE-100", "NONE", ProjectFileListAuthHotpathBenchmark.SALES_USER_ID, 100);
    assertEquals(
        ProjectFileListAuthHotpathBenchmark.GROUP_SIZE,
        none.expectedTotal(),
        "total 必须保留数据库条件总数（不是筛后的可读条数）");
    assertTrue(
        ProjectFileListAuthHotpathBenchmark.expectedIds(none, 2).isEmpty(),
        "全无权组：second page records 允许为空，total 仍非零");
    assertTrue(
        ProjectFileListAuthHotpathBenchmark.expectedIds(none, 1).isEmpty(),
        "全无权组：first page records 也应为空");
  }

  /** 佐证 8：组基址与组名一一对应，避免不同组 ID 混淆。 */
  @Test
  void groupBasesAreDistinct() {
    assertEquals(
        ProjectFileListAuthHotpathBenchmark.ALL_BASE,
        ProjectFileListAuthHotpathBenchmark.baseOf("ALL"));
    assertEquals(
        ProjectFileListAuthHotpathBenchmark.PART_BASE,
        ProjectFileListAuthHotpathBenchmark.baseOf("PART"));
    assertEquals(
        ProjectFileListAuthHotpathBenchmark.NONE_BASE,
        ProjectFileListAuthHotpathBenchmark.baseOf("NONE"));
    assertEquals(
        ProjectFileListAuthHotpathBenchmark.PROBE_BASE,
        ProjectFileListAuthHotpathBenchmark.baseOf("PROBE"));
  }

  /** 佐证 9：探针组可读分布与枚举逐位对齐（供阶段 3/5 的权限反例复用）。 */
  @Test
  void probeReadableDistributionIsDeterministic() {
    ProjectFileListAuthHotpathBenchmark.Condition probe =
        ProjectFileListAuthHotpathBenchmark.probeCondition();
    assertEquals(
        ProjectFileListAuthHotpathBenchmark.PROBE_COUNT, probe.expectedTotal(), "探针组 total 应为探针条数");
    int readableCount = 0;
    for (int i = 0; i < ProjectFileListAuthHotpathBenchmark.PROBE_COUNT; i++) {
      if (ProjectFileListAuthHotpathBenchmark.readable(probe, i)) {
        readableCount++;
      }
    }
    assertEquals(
        ProjectFileListAuthHotpathBenchmark.PROBE_SPEC.length,
        ProjectFileListAuthHotpathBenchmark.PROBE_COUNT,
        "探针规格行数必须与探针条数一致");
    assertEquals(
        readableCount,
        ProjectFileListAuthHotpathBenchmark.expectedIds(probe, 1).size(),
        "探针组可读条数必须等于逐位判定的可读个数");
  }
}
