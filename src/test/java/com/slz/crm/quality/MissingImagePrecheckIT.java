package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * 镜像只读预检反例（从 surefire 轨迁入：镜像预检需要真 Docker 客户端，surefire 轨按 AGENTS.md 必须与 Docker 无关，见 F-4）。
 *
 * <p>三个用例的断言与消息逐字保留自 {@code KnowledgeBaseScopeAuthBaselineGuardTest} / {@code
 * ProjectFileListAuthHotpathGuardTest} / {@code RepresentativeHotpathBenchmarkGuardTest} 的 {@code
 * missingImageMustBeReportedNotPulled()}；{@code missingLocalImages} 是 {@code com.slz.crm.quality}
 * 包内 package-private static，故本类必须与三个 Benchmark 同包（不许为此把它改成 public）。
 */
class MissingImagePrecheckIT {

  /** kbscope：镜像缺失必须被只读预检点名（不触发拉取）。 */
  @Test
  void kbscopeMissingImageMustBeReportedNotPulled() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过镜像预检反例（该路径由真实度量入口的 fail closed 覆盖）");
    String phantom = "kbscope-phantom-image:does-not-exist";
    assertEquals(
        List.of(phantom),
        KnowledgeBaseScopeAuthBaselineBenchmark.missingLocalImages(List.of(phantom)),
        "不存在的镜像必须被报告为缺失（只读 inspect，不触发拉取）");
  }

  /** pflhot：镜像缺失必须被只读预检点名（不触发拉取——入口在容器启动前就失败）。 */
  @Test
  void pflhotMissingImageMustBeReportedNotPulled() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过镜像预检反例（该路径由真实度量入口的 fail closed 覆盖）");
    String phantom = "pflhot-phantom-image:does-not-exist";
    List<String> missing = ProjectFileListAuthHotpathBenchmark.missingLocalImages(List.of(phantom));
    assertEquals(List.of(phantom), missing, "不存在的镜像必须被报告为缺失（只读 inspect，不触发拉取）");
  }

  /** rephot：镜像缺失必须被只读预检点名（不触发拉取——入口在容器启动前就失败）。 */
  @Test
  void rephotMissingImageMustBeReportedNotPulled() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过镜像预检反例（该路径由真实度量入口的 fail closed 覆盖）");
    String phantom = "rephot-phantom-image:does-not-exist";
    List<String> missing = RepresentativeHotpathBenchmark.missingLocalImages(List.of(phantom));
    assertEquals(List.of(phantom), missing, "不存在的镜像必须被报告为缺失（只读 inspect，不触发拉取）");
  }
}
