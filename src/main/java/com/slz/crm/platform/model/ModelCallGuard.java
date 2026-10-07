package com.slz.crm.platform.model;

import com.slz.crm.platform.resilience.DependencyFailureType;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import io.micrometer.core.instrument.Metrics;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模型外呼的依赖熔断护栏（wire-dependency-circuit-breaker 任务 3）。
 *
 * <p>把 {@link DependencyResilienceExecutor} 的连续失败计数与 OPEN 快速拒绝接到模型调用外面，并把执行器的 {@link
 * DependencyUnavailableException} 转回门面既有异常语义：真实调用失败原样透传底层运行时异常（消息与 cause 链零变化，超时护栏的断口不被改写），熔断拒绝转成
 * {@link IllegalStateException}，cause 为 {@link
 * DependencyUnavailableException.CircuitOpenException}。
 *
 * <p>零自动重试：一次请求只有一次物理外呼，费用与时延不因熔断层放大；退避重试属执行器的 {@code execute} 语义，本护栏不启用。
 *
 * <p>拆分理由：{@code ModelProviderImpl} 的 PMD 类 NCSS 实测 145（阈值见 pmd-rules.xml），四条 call 点就地包 try/catch
 * 必然越阈，故把"接线 + 异常转换"外移为协作者，{@code ModelProviderImpl} 只替换 call 点。
 */
@Component
public class ModelCallGuard {

  private final DependencyResilienceExecutor executor;

  /**
   * 注入共享的依赖韧性执行器（同一进程内按依赖名隔离熔断状态）。
   *
   * @param executor 依赖韧性执行器
   */
  @Autowired
  public ModelCallGuard(DependencyResilienceExecutor executor) {
    this.executor = executor;
  }

  /**
   * 手工装配（非 Spring 路径）用的独立护栏：自带执行器，熔断状态不与其它实例共享， 指标注册到 Micrometer 全局注册表。既有测试与基准以四参构造 {@code
   * ModelProviderImpl}，走这条路。
   *
   * @return 自带执行器的护栏
   */
  public static ModelCallGuard standalone() {
    return new ModelCallGuard(new DependencyResilienceExecutor(Metrics.globalRegistry));
  }

  /**
   * 单次执行模型调用并计入依赖熔断。
   *
   * @param dependency 依赖名（model-chat / model-embed / model-vision）
   * @param call 模型调用
   * @param <T> 返回类型
   * @return 调用结果
   * @throws IllegalStateException 依赖失败透传或熔断快速拒绝
   */
  public <T> T call(String dependency, Supplier<T> call) {
    try {
      return executor.executeNoRetry(dependency, call::get);
    } catch (DependencyUnavailableException exception) {
      throw toFacadeFailure(dependency, exception);
    }
  }

  /** 熔断层异常转回门面既有异常类型，DependencyUnavailableException 不出现在门面链上。 */
  private static RuntimeException toFacadeFailure(
      String dependency, DependencyUnavailableException exception) {
    Throwable cause = exception.getCause();
    RuntimeException failure;
    if (exception.failureType() == DependencyFailureType.CIRCUIT_OPEN) {
      failure = new IllegalStateException("模型调用被熔断快速拒绝: " + dependency, cause);
    } else if (cause instanceof RuntimeException runtimeException) {
      failure = runtimeException;
    } else {
      failure = new IllegalStateException(dependency + " 模型调用失败", cause);
    }
    return failure;
  }
}
