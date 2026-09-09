package com.slz.crm.platform.trace;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MDC 与用户上下文跨线程传播及清理测试。
 */
class MdcTaskDecoratorTest {

    @AfterEach
    void cleanContext() {
        MDC.clear();
        UserContextHolder.clear();
    }

    @Test
    void shouldPropagateTraceIdAndUserContext() throws Exception {
        MDC.put(RequestTraceKey.TRACE_ID, "trace-123456");
        UserContext context = new UserContext(10L, 2L, 20L, DataScopeLevel.SELF, " tester");
        UserContextHolder.set(context);
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<UserContext> userContext = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread thread = new Thread(MdcTaskDecorator.wrap(() -> {
            traceId.set(MDC.get(RequestTraceKey.TRACE_ID));
            userContext.set(UserContextHolder.current());
            latch.countDown();
        }));
        thread.start();
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(traceId.get()).isEqualTo("trace-123456");
        assertThat(userContext.get()).isEqualTo(context);
    }

    @Test
    void shouldRestoreTargetThreadPreviousState() throws Exception {
        AtomicReference<String> targetTraceId = new AtomicReference<>();
        AtomicReference<UserContext> targetUserContext = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        MDC.put(RequestTraceKey.TRACE_ID, "trace-123456");

        Thread thread = new Thread(() -> {
            MDC.put(RequestTraceKey.TRACE_ID, "target-before");
            UserContext before = new UserContext(99L, 1L, 90L, DataScopeLevel.ALL, "before");
            UserContextHolder.set(before);

            MdcTaskDecorator.wrap(() -> {
                targetTraceId.set(MDC.get(RequestTraceKey.TRACE_ID));
                targetUserContext.set(UserContextHolder.current());
            }).run();

            targetTraceId.set(MDC.get(RequestTraceKey.TRACE_ID));
            targetUserContext.set(UserContextHolder.current());
            latch.countDown();
        });
        thread.start();
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(targetTraceId.get()).isEqualTo("target-before");
        assertThat(targetUserContext.get()).isNotNull();
        assertThat(targetUserContext.get().userId()).isEqualTo(99L);
    }
}
