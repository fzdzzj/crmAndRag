package com.slz.crm.platform.resilience;

/**
 * 依赖失败分类器。
 *
 * <p>供批量恢复等调用方在异常链中查找统一语义，避免通过异常文案做脆弱匹配。
 */
public final class DependencyFailureClassifier {

  private DependencyFailureClassifier() {}

  /**
   * 判断异常是否属于可恢复的依赖临时故障。
   *
   * @param error 业务层捕获到的异常，可为 null
   * @return true 表示应保留/恢复为待处理状态，等待依赖恢复后重放
   */
  public static boolean isRecoverable(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof DependencyUnavailableException unavailable
          && unavailable.failureType().recoverable()) {
        return true;
      }
    }
    return false;
  }
}
