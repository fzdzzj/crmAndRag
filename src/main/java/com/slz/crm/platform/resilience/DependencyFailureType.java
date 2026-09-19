package com.slz.crm.platform.resilience;

/**
 * 依赖失败分类。
 *
 * <p>批量处理不能只靠异常消息判断是否恢复；必须拿到机器可读语义。 熔断 OPEN 与重试耗尽都是“依赖暂时不可用”，应回到可重放状态； 参数类失败和中断则不应盲目重放。
 */
public enum DependencyFailureType {

  /** 依赖调用后重试耗尽，通常可以在依赖恢复后重放。 */
  CALL_FAILED(true),
  /** 熔断器处于 OPEN 状态直接拒绝，调用尚未执行，恢复后必须重放。 */
  CIRCUIT_OPEN(true),
  /** 调用方自定义的非重试失败，恢复流程不应把它当作临时故障。 */
  NON_RETRYABLE(false),
  /** 重试等待被中断，由线程中断策略决定后续处理。 */
  INTERRUPTED(false);

  private final boolean recoverable;

  DependencyFailureType(boolean recoverable) {
    this.recoverable = recoverable;
  }

  /**
   * @return true 表示属于依赖暂时不可用，可交给恢复流程重放
   */
  public boolean recoverable() {
    return recoverable;
  }
}
