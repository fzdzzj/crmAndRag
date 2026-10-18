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
 * 单副本架构假设静态守卫（卡 P-d 引入，harden-single-replica-contract 扩容）：纯 JVM 文本/源码扫描（无 Docker
 * 无外网），固化"本系统按单副本单体设计" 的架构假设（L-11）。
 *
 * <p>五道闸：① 两份 application*.yml 必须显式声明 {@code platform.architecture.single-replica: true}
 * （配置即文档，删声明即红）；② {@code ProductionConfigurationGuard} 源码必须保留对 single-replica 的
 * 启动期校验与失败文案（防守卫被静默摘除）；③ 关键单机组件登记表（8 大组件）——组件源码必须仍含进程内状态标记， 换成分布式实现（如 Redis
 * 共享状态）即红，强制重新定夺架构假设；单机假设声明文本登记在本守卫登记表内； ④ 6 大 {@code @Scheduled} 定时任务类源码必须保留单副本/单机无锁调度假设声明；⑤
 * deploy/docker-compose.yml 必须显式锁定 {@code mode: replicated} + {@code replicas: 1} 且注入
 * PLATFORM_ARCHITECTURE_SINGLE_REPLICA， .env.example 必须保留同名变量声明（编排与环境契约，放开即红）。
 */
class SingleReplicaArchitectureAssumptionGuardTest {

  private static final Path APPLICATION_YML = Paths.get("src/main/resources/application.yml");

  private static final Path APPLICATION_PROD_YML =
      Paths.get("src/main/resources/application-prod.yml");

  private static final Path PRODUCTION_GUARD_JAVA =
      Paths.get("src/main/java/com/slz/crm/common/config/ProductionConfigurationGuard.java");

  private static final Path DEPLOY_COMPOSE_YML = Paths.get("deploy/docker-compose.yml");

  private static final Path ENV_EXAMPLE = Paths.get(".env.example");

  /**
   * 关键单机组件登记表（harden-single-replica-contract 扩至全量 8 大组件）：组件源文件 → 进程内状态标记 +
   * 单机假设声明。改组件实现使标记消失、或搬迁/改名文件， 都必须先修订本登记表并经 owner 拍板（同 AsyncExecutorGovernanceGuardTest 的
   * ALLOWLIST 纪律）。
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
              "单副本假设：动态配置读缓存是进程内 ConcurrentHashMap 快照，写后逐键失效只对同实例即时生效；其余实例只能等有界全量刷新兜底"),
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/server/ai/AiRateLimiter.java",
              "ConcurrentHashMap",
              "单副本假设：用户滑动窗口计数落在进程内 ConcurrentHashMap，无跨实例共享；多副本下限流上限按副本数放大，形同虚设"),
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/platform/resilience/DependencyResilienceExecutor.java",
              "ConcurrentHashMap",
              "单副本假设：熔断三态与故障计数落在进程内 ConcurrentHashMap，无跨实例共享；多副本下熔断状态分裂，半开探测配额被放大"),
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/common/untils/NumberGenerator.java",
              "AtomicInteger",
              "单副本假设：每日序号与日期缓存是进程内 AtomicInteger 与静态字段，无跨实例协调；多副本部署必然产生重复业务单号"),
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/server/service/support/LoginAttemptService.java",
              "Caffeine",
              "单副本假设：登录防爆破计数在本地 Caffeine（Email+IP 双维），无跨实例共享；多副本下攻击者换副本即可绕过锁定"),
          new SingleMachineComponent(
              "src/main/java/com/slz/crm/server/service/support/TokenBlacklistService.java",
              "Caffeine",
              "单副本假设：登出 Token 黑名单在本地 Caffeine，无跨实例共享；多副本下已登出 Token 在其他副本仍然有效"));

  /**
   * 6 大 @Scheduled 定时任务类登记表（harden-single-replica-contract 任务 2.2）：全部无分布式锁单机触发，
   * 类源码必须保留登记表内的单副本调度假设声明（按空白归一化比对，容忍 Javadoc 换行重排）。
   */
  private static final List<ScheduledTaskComponent> SCHEDULED_TASK_COMPONENTS =
      List.of(
          new ScheduledTaskComponent(
              "src/main/java/com/slz/crm/knowledge/document/IngestionReplayScheduler.java",
              "单副本假设：无锁单机调度（Spring @Scheduled，无分布式锁），多副本部署会重复重放同批 PENDING 文档"),
          new ScheduledTaskComponent(
              "src/main/java/com/slz/crm/server/ai/AiMemoryOrchestrator.java",
              "单副本假设：无锁单机调度（Spring @Scheduled，无分布式锁），多副本部署会重复清理与重复加工会话记忆"),
          new ScheduledTaskComponent(
              "src/main/java/com/slz/crm/server/ai/AiPendingActionExpireTask.java",
              "单副本假设：无锁单机调度（Spring @Scheduled，无分布式锁），多副本部署会重复扫描置 EXPIRED（幂等但浪费）"),
          new ScheduledTaskComponent(
              "src/main/java/com/slz/crm/server/schedule/ChurnWarningScheduler.java",
              "单副本假设：无锁单机调度（Spring @Scheduled，无分布式锁），多副本部署会并发触发重复派发流失预警待办"),
          new ScheduledTaskComponent(
              "src/main/java/com/slz/crm/server/schedule/ContractWarningScheduler.java",
              "单副本假设：无锁单机调度（Spring @Scheduled，无分布式锁），多副本部署会并发触发重复派发合同预警待办"),
          new ScheduledTaskComponent(
              "src/main/java/com/slz/crm/server/schedule/CustomerPoolRecycleScheduler.java",
              "单副本假设：无锁单机调度（Spring @Scheduled，无分布式锁），多副本部署会并发触发重复回收扫描"));

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

  @Test
  void scheduledTaskClassesDeclareSingleReplicaAssumption() throws IOException {
    for (ScheduledTaskComponent component : SCHEDULED_TASK_COMPONENTS) {
      Path path = Paths.get(component.relativePath());
      String source = Files.readString(path, StandardCharsets.UTF_8);
      assertTrue(
          source.contains("@Scheduled"),
          component.relativePath() + " 不再包含 @Scheduled——已不是定时任务类，必须重新定夺登记表");
      assertTrue(
          compact(source).contains(compact(component.declaration())),
          component.relativePath() + " 必须在类注释中保留单副本调度假设声明：" + component.declaration());
    }
  }

  @Test
  void dockerComposePinsSingleReplicaDeployment() throws IOException {
    String compose = Files.readString(DEPLOY_COMPOSE_YML, StandardCharsets.UTF_8);
    assertTrue(
        compact(compose).contains("mode:replicated"),
        "deploy/docker-compose.yml 必须显式声明 deploy.mode: replicated");
    assertTrue(
        compact(compose).contains("replicas:1"),
        "deploy/docker-compose.yml 必须显式锁定 deploy.replicas: 1（单副本部署契约）");
    assertTrue(
        compose.contains("PLATFORM_ARCHITECTURE_SINGLE_REPLICA"),
        "deploy/docker-compose.yml 必须注入 PLATFORM_ARCHITECTURE_SINGLE_REPLICA 环境变量");
    String envExample = Files.readString(ENV_EXAMPLE, StandardCharsets.UTF_8);
    assertTrue(
        envExample.contains("PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true"),
        ".env.example 必须保留 PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true 声明");
  }

  /** 归一化空白：容忍源码/Javadoc 的换行与缩进重排，只比对声明内容本身。 */
  private static String compact(String text) {
    return text.replaceAll("\\s+", "");
  }

  /** 登记表条目：相对路径 + 进程内状态标记 + 单机假设声明。 */
  private record SingleMachineComponent(
      String relativePath, String processLocalMarker, String assumption) {}

  /** 登记表条目：调度类相对路径 + 必须保留在类源码中的单副本调度假设声明。 */
  private record ScheduledTaskComponent(String relativePath, String declaration) {}
}
