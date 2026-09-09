package com.slz.crm.platform.trace;

import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import org.slf4j.MDC;
import java.util.Map;

/**
 * 将提交线程的 MDC 与用户上下文复制到异步任务线程。
 *
 * <p>异步快照必须在提交时捕获；执行线程结束后按“恢复执行线程原状态”的语义还原，
 * 既避免业务串号，也避免污染线程池复用线程。本类只做治理横切，不感知业务。</p>
 */
public final class MdcTaskDecorator {

    private MdcTaskDecorator() {
    }

    /**
     * 使用提交线程上下文包装任务。
     *
     * @param task 原始异步任务
     * @return 携带 traceId 与 UserContext 的任务
     */
    public static Runnable wrap(Runnable task) {
        return wrap(task, MDC.getCopyOfContextMap(), UserContextHolder.current());
    }

    /**
     * 使用显式上下文包装任务，供延迟任务或测试在提交时固定快照。
     *
     * @param task 原始异步任务
     * @param context 提交线程的 MDC 上下文
     * @param userContext 提交线程的用户身份快照
     * @return 携带指定上下文的任务
     */
    public static Runnable wrap(Runnable task, Map<String, String> context, UserContext userContext) {
        return () -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            UserContext previousUserContext = UserContextHolder.current();
            restore(context, userContext);
            try {
                task.run();
            } finally {
                restore(previousContext, previousUserContext);
            }
        };
    }

    private static void restore(Map<String, String> context, UserContext userContext) {
        MDC.clear();
        if (context != null && !context.isEmpty()) {
            MDC.setContextMap(context);
        }
        UserContextHolder.set(userContext);
    }
}
