package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.platform.contract.ModelProvider;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 代表性热路径度量的安全反例与边界锁定（add-representative-hotpath-measurement 任务 4.2）。
 *
 * <p><b>默认执行的纯 JVM 守卫</b>：零 Docker 容器、零外发、零本案启动。锁定三条边界（镜像只读预检反例已迁 failsafe 轨 {@link
 * MissingImagePrecheckIT}，F-4）：
 *
 * <ol>
 *   <li>度量入口类名不满足 surefire/failsafe 任何默认发现规则——默认 {@code mvn test} / {@code mvn verify} 根本发现不到它；
 *   <li>独立 opt-in 开关缺失时入口 fail closed（在任何容器/装配动作之前抛错）；
 *   <li>模型端口硬绑定本地桩：即使环境留有真实 Provider 的开关/密钥样式的属性，装配层得到的仍是 {@code representative-hotpath-stub}，绝不是
 *       {@code ModelProviderImpl}。
 * </ol>
 */
class RepresentativeHotpathBenchmarkGuardTest {

  /** 反例 1：入口类名必须逃过 surefire（*Test/*Tests）与 failsafe（*IT/*IntegrationTest）全部默认发现规则。 */
  @Test
  void benchmarkClassNameMustEscapeDefaultDiscovery() {
    String simpleName = RepresentativeHotpathBenchmark.class.getSimpleName();
    for (String suffix : List.of("Test", "Tests", "IT", "IntegrationTest")) {
      assertTrue(!simpleName.endsWith(suffix), "度量入口类名以 " + suffix + " 结尾会被默认测试发现，违反「默认测试发现不到」规格");
    }
    assertEquals("RepresentativeHotpathBenchmark", simpleName);
  }

  /** 反例 2：独立 opt-in 缺失 → 在任何容器/装配之前 fail closed（本测试进程未设置该开关）。 */
  @Test
  void missingOptInMustFailClosedBeforeAnySetup() {
    Assumptions.assumeTrue(
        !"1".equals(System.getenv(RepresentativeHotpathBenchmark.OPT_IN_ENV)),
        "本机已设置 " + RepresentativeHotpathBenchmark.OPT_IN_ENV + "=1，跳过「缺开关 fail closed」反例");
    try {
      RepresentativeHotpathBenchmark.requireOptIn();
      throw new AssertionError("缺独立 opt-in 时度量入口必须拒绝运行，而不是放行");
    } catch (IllegalStateException expected) {
      assertTrue(
          expected.getMessage().contains(RepresentativeHotpathBenchmark.OPT_IN_ENV),
          "fail-closed 信息必须指名独立开关，而不是含糊其辞");
    }
  }

  /** 反例 3：真实 Provider 痕迹不影响装配——模型端口始终是本地确定性桩。 */
  @Test
  void modelStubMustBeHardwiredRegardlessOfRealProviderTraces() {
    String originalKey = System.getProperty("spring.ai.dashscope.api-key");
    String originalEnvSwitch = System.getProperty("DASHSCOPE_API_KEY");
    System.setProperty("spring.ai.dashscope.api-key", "sk-guard-test-do-not-call");
    System.setProperty("DASHSCOPE_API_KEY", "sk-guard-test-do-not-call");
    try {
      RepresentativeHotpathBenchmark.assertStubProviderHardwired();
      RepresentativeHotpathBenchmark.StubModelProvider stub =
          new RepresentativeHotpathBenchmark.StubModelProvider(
              new RepresentativeHotpathBenchmark.Counters());
      assertEquals("representative-hotpath-stub", stub.provider(), "provider 标识必须是本地桩");
      ModelProvider port = stub;
      assertTrue(
          port.getClass() != com.slz.crm.platform.model.ModelProviderImpl.class,
          "本地桩运行时类绝不可能是 ModelProviderImpl");
    } finally {
      restore("spring.ai.dashscope.api-key", originalKey);
      restore("DASHSCOPE_API_KEY", originalEnvSwitch);
    }
  }

  /** 反例 5：钉扎镜像不能是 latest 等漂移标签，且默认清单固定为两件（MySQL + Qdrant）。 */
  @Test
  void pinnedImagesMustBeImmutableReferences() {
    assertEquals("mysql:8.0", RepresentativeHotpathBenchmark.DEFAULT_MYSQL_IMAGE);
    assertEquals("qdrant/qdrant:v1.18.3", RepresentativeHotpathBenchmark.DEFAULT_QDRANT_IMAGE);
    for (String image :
        List.of(
            RepresentativeHotpathBenchmark.DEFAULT_MYSQL_IMAGE,
            RepresentativeHotpathBenchmark.DEFAULT_QDRANT_IMAGE)) {
      assertTrue(!image.endsWith(":latest"), "钉扎镜像不允许 latest 漂移标签：" + image);
    }
  }

  /** 守卫佐证：桩端口的 chat 是即时空串语义（查询改写回退原查询），绝不产生外呼形态的返回。 */
  @Test
  void stubChatReturnsInstantEmptyRewrite() {
    RepresentativeHotpathBenchmark.Counters counters =
        new RepresentativeHotpathBenchmark.Counters();
    RepresentativeHotpathBenchmark.StubModelProvider stub =
        new RepresentativeHotpathBenchmark.StubModelProvider(counters);
    ModelProvider provider = stub;
    long start = System.nanoTime();
    com.slz.crm.platform.contract.ModelCallResult<String> result =
        provider.chat(new org.springframework.ai.chat.prompt.Prompt("任意改写输入"));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertEquals("", result.content(), "改写桩必须返回空串（生产侧回退原查询）");
    assertTrue(elapsedMs < 100, "改写桩必须即时返回（本机实测 <100ms），不得模拟任何远程 RTT");
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
