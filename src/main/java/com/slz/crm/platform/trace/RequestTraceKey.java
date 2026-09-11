package com.slz.crm.platform.trace;

/**
 * 请求追踪上下文键。
 *
 * <p>键名集中定义，避免日志字段、过滤器与异步装饰器出现拼写漂移。</p>
 */
public final class RequestTraceKey {

    /** MDC 中保存请求链路 ID 的键。 */
    public static final String TRACE_ID = "traceId";

    private RequestTraceKey() {
    }
}
