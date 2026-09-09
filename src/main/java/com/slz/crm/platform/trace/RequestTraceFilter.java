package com.slz.crm.platform.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求入口统一写入链路 ID。
 *
 * <p>过滤器排在最高优先级，保证后续拦截器、业务日志与异步任务都能拿到同一个 traceId。
 * 支持调用方传入合法 traceId，便于跨服务日志串联；非法值一律重新生成，防止日志注入。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    /** 支持调用方传入的追踪 ID 请求头。 */
    public static final String TRACE_ID_HEADER = "X-Request-Id";

    /** traceId 只允许常见安全字符，长度控制在 8 到 64 位。 */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_.-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(RequestTraceKey.TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程可能来自容器复用池，必须清理，避免下个请求继承旧链路。
            MDC.remove(RequestTraceKey.TRACE_ID);
        }
    }

    private String resolveTraceId(String requestedTraceId) {
        if (requestedTraceId != null && VALID_TRACE_ID.matcher(requestedTraceId.trim()).matches()) {
            return requestedTraceId.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
