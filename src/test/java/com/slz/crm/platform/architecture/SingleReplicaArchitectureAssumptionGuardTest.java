package com.slz.crm.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 单副本架构假设静态守卫（卡 P-d）：纯 JVM 文本/源码扫描（无 Docker 无外网），固化"本系统按单副本单体设计" 的架构假设（L-11）。
 *
 * <p>三道闸：① 两份 application*.yml 必须显式声明 {@code platform.architecture.single-replica: true}
 * （配置即文档，删声明即红）；② {@code ProductionConfigurationGuard} 源码必须保留对 single-replica 的
 * 启动期校验与失败文案（防守卫被静默摘除）；③ 关键单机组件登记表——组件源码必须仍含进程内状态标记， 换成分布式实现（如 Redis
 * 共享状态）即红，强制重新定夺架构假设；单机假设声明文本登记在本守卫登记表内。
 */
class SingleReplicaArchitectureAssumptionGuardTest {

  private static final Path APPLICATION_YML = Paths.get("src/main/resources/application.yml");

  private static final Path APPLICATION_PROD_YML =
      Paths.get("src/main/resources/application-prod.yml");

  private static final Path PRODUCTION_GUARD_JAVA =
      Paths.get("src/main/java/com/slz/crm/common/config/ProductionConfigurationGuard.java");

  /**
   * 关键单机组件登记表：组件源文件 → 进程内状态标记 + 单机假设声明。改组件实现使标记消失、或搬迁/改名文件， 都必须先修订本登记表并经 owner 拍板（同
   * AsyncExecutorGovernanceGuardTest 的 ALLOWLIST 纪律）。
   */
  private static final List<SingleMachineComponent> SINGLE_MACHINE_COMPONENTS =
      List.of(
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/platform/quota/RequestQuotaService.java",
              "Caffeine",
              "单副本假设：四层固定窗口配额计数在本地 Caffeine 进程内缓存，无跨实例共享存储；多副本部署各算各的，配额形同虚设"),
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/server/ai/AiChatStreamLifecycle.java",
              "AiStreamRegistry",
              "单副本假设：SSE 活跃流状态机（订阅/中止/取消/心跳/终态）落在进程内 AiStreamRegistry，状态不落库；多副本下取消与清理打不到持有流的实例"),
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/platform/config/DynamicConfigCache.java",
              "ConcurrentHashMap",
              "单副本假设：动态配置读缓存是进程内 ConcurrentHashMap 快照，写后逐键失效只对同实例即时生效；其余实例只能等有界全量刷新兜底"));

  @Test
  void applicationYamlExplicitlyDeclaresSingleReplicaTrue() throws IOException {
    String content = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
    assertTrue(
        content.contains("single-replica: true"), "application.yml 必须显式声明 single-replica: true");
    assertTrue(content.contains("部署架构假设"), "application.yml 必须保留部署架构假设的注释声明（配置即文档）");
  }

  @Test
  void prodProfileExplicitlyDeclaresSingleReplicaTrue() throws IOException {
    String content = Files.readString(APPLICATION_PROD_YML, StandardCharsets.UTF_8);
    assertTrue(
        content.contains("single-replica: true"),
        "application-prod.yml 必须显式声明 single-replica: true");
    assertTrue(content.contains("部署架构假设"), "application-prod.yml 必须保留部署架构假设的注释声明（配置即文档）");
  }

  @Test
  void productionGuardStillValidatesSingleReplica() throws IOException {
    String source = Files.readString(PRODUCTION_GUARD_JAVA, StandardCharsets.UTF_8);
    assertTrue(
        source.contains("platform.architecture.single-replica"),
        "生产配置守卫必须保留 single-replica 配置校验，不得静默摘除");
    assertTrue(source.contains("单副本单体架构"), "生产配置守卫必须保留单副本架构假设的失败文案");
  }

  @Test
  void keySingleMachineComponentsCarryRegisteredAssumptions() throws IOException {
    for (SingleMachineComponent component : SINGLE_MACHINE_COMPONENTS) {
      Path path = Paths.get(component.relativePath());
      String source = Files.readString(path, StandardCharsets.UTF_8);
      assertTrue(
          source.contains(component.processLocalMarker()),
          component.relativePath()
              + " 不再包含进程内状态标记 "
              + component.processLocalMarker()
              + "——组件可能已被改成跨实例共享实现，必须重新定夺单机假设声明");
      assertTrue(
          component.assumption().contains("单机") || component.assumption().contains("单副本"),
          component.relativePath() + " 的登记声明必须写明单机/单副本假设");
    }
  }

  /** 登记表条目：相对路径 + 进程内状态标记 + 单机假设声明。 */
  private record SingleMachineComponent(
      String relativePath, String processLocalMarker, String assumption) {}
}
