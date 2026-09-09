package com.slz.crm.platform.async;

import com.slz.crm.platform.contract.BypassTaskExecutor;
import com.slz.crm.platform.trace.MdcTaskDecorator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

import java.util.concurrent.RejectedExecutionException;

/**
 * 记忆旁路任务执行器。
 *
 * <p>意图/摘要属于旁路加工：饱和时必须让调用方立即跳过，而不是阻塞主答。
 * MDC 和 UserContext 在提交线程捕获、任务线程恢复；单飞去重仍由 C 的业务层 CAS 完成。</p>
 */
final class MemoryBypassTaskExecutor implements BypassTaskExecutor {

    private final ThreadPoolTaskExecutor executor;
    private final Counter rejectedCounter;

    /**
     * 构造旁路执行器。
     *
     * @param executor 底层有界线程池
     * @param meterRegistry Micrometer 注册表
     */
    MemoryBypassTaskExecutor(ThreadPoolTaskExecutor executor, MeterRegistry meterRegistry) {
        Assert.notNull(executor, "memory bypass executor 不能为空");
        this.executor = executor;
        this.rejectedCounter = Counter.builder("platform.memory.bypass.rejected")
                .tag("executor", "memory-bypass")
                .register(meterRegistry);
    }

    @Override
    public boolean tryExecute(Runnable task) {
        Assert.notNull(task, "记忆旁路任务不能为空");
        try {
            executor.execute(MdcTaskDecorator.wrap(task));
            return true;
        } catch (RejectedExecutionException | IllegalStateException exception) {
            // IllegalStateException 来自执行器关闭后的提交；两者都表示任务未入队，调用方应降级跳过。
            rejectedCounter.increment();
            return false;
        }
    }
}
