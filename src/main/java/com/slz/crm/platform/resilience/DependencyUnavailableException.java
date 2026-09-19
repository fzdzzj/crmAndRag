package com.slz.crm.platform.resilience;

/**
 * 依赖执行失败的统一异常。
 *
 * <p>异常必须携带失败类型；熔断 OPEN 虽然没有真实网络异常，仍设置 {@link CircuitOpenException} 作为 cause，避免调用方把“无
 * cause”误解为程序缺陷。
 */
public class DependencyUnavailableException extends RuntimeException {

  private final DependencyFailureType failureType;

  /**
   * 构造依赖失败异常。
   *
   * @param message 可读描述
   * @param cause 底层异常；熔断 OPEN 时为 {@link CircuitOpenException}
   * @param failureType 机器可读失败分类
   */
  public DependencyUnavailableException(
      String message, Throwable cause, DependencyFailureType failureType) {
    super(message, cause);
    this.failureType = failureType;
  }

  /**
   * 构造依赖失败异常。
   *
   * @param message 可读描述
   * @param failureType 机器可读失败分类
   */
  public DependencyUnavailableException(String message, DependencyFailureType failureType) {
    this(message, null, failureType);
  }

  /**
   * 构造熔断 OPEN 拒绝。
   *
   * @param dependency 依赖名称
   * @return 带 OPEN 语义和 cause 的异常
   */
  public static DependencyUnavailableException circuitOpen(String dependency) {
    return new DependencyUnavailableException(
        "依赖熔断中: " + dependency,
        new CircuitOpenException("熔断器处于 OPEN 状态: " + dependency),
        DependencyFailureType.CIRCUIT_OPEN);
  }

  /**
   * @return 机器可读失败分类
   */
  public DependencyFailureType failureType() {
    return failureType;
  }

  /** 熔断器处于 OPEN 状态的显式 cause。 */
  public static final class CircuitOpenException extends RuntimeException {

    /**
     * 构造熔断 OPEN 异常。
     *
     * @param message 可读描述
     */
    public CircuitOpenException(String message) {
      super(message);
    }
  }
}
