package com.slz.crm.unit.knowledge.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 卡 E（async-executor-governance）测试支撑执行器：三种可控行为替代真实池。 asyncPerTaskExecutor 让 get(timeout)
 * 的超时真实触发；DeferringExecutor 收集任务不执行，用于观测"被放弃的任务是否还会跑"； rejectingExecutor 模拟饱和池的 abort 拒绝语义。
 */
final class AsyncExecutorTestSupport {

  private AsyncExecutorTestSupport() {}

  /** 每任务一个守护线程：supplyAsync 立即返回、任务异步执行（直接执行会让超时永不发生）。 */
  static Executor asyncPerTaskExecutor() {
    return task -> {
      Thread worker = new Thread(task, "test-llm-aux");
      worker.setDaemon(true);
      worker.start();
    };
  }

  /** 收集提交的任务不执行：drain() 后才逐个同步执行——若 future 已被 cancel，排队任务将不再触碰 Provider。 */
  static final class DeferringExecutor implements Executor {
    private final List<Runnable> submitted = new ArrayList<>();

    @Override
    public void execute(Runnable task) {
      submitted.add(task);
    }

    void drain() {
      List<Runnable> pending = new ArrayList<>(submitted);
      submitted.clear();
      for (Runnable task : pending) {
        task.run();
      }
    }
  }

  /** 模拟饱和池 abort 拒绝：execute 同步抛 RejectedExecutionException（与 ThreadPoolExecutor.AbortPolicy 同效）。 */
  static Executor rejectingExecutor() {
    return task -> {
      throw new RejectedExecutionException("test-pool-saturated");
    };
  }
}
