package com.slz.crm.server.ai;

import com.slz.crm.server.properties.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** SSE 心跳调度周期、开关与终态清理行为验证。 */
@ExtendWith(MockitoExtension.class)
class AiChatStreamHeartbeatTest {

    @Mock
    private ScheduledExecutorService scheduler;

    @Mock
    private ScheduledFuture<?> future;

    private AiProperties properties;

    private AiChatStreamHeartbeat heartbeat;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setHeartbeatIntervalSeconds(15);
        heartbeat = new AiChatStreamHeartbeat(properties, scheduler);
    }

    @Test
    void start_shouldScheduleAtConfiguredIntervalAndRunCurrentAction() {
        // 只按配置周期创建任务，且任务必须绑定到当前活跃流
        AiStreamRegistry.ActiveStream stream = new AiStreamRegistry.ActiveStream(1L, new SseEmitter());
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));

        heartbeat.start(stream, () -> {});

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        assertThat(stream.isCurrentHeartbeat(future)).isTrue();
    }

    @Test
    void terminalStream_shouldCancelCurrentHeartbeat() {
        // 终态抢占成功后立即取消当前心跳任务
        AiStreamRegistry.ActiveStream stream = new AiStreamRegistry.ActiveStream(1L, new SseEmitter());
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        heartbeat.start(stream, () -> {});

        assertThat(stream.tryMarkFinished()).isTrue();

        verify(future).cancel(false);
    }

    @Test
    void disabledHeartbeat_shouldNotScheduleTask() {
        // 开关关闭时不占用调度器
        properties.setHeartbeatEnabled(false);
        AiStreamRegistry.ActiveStream stream = new AiStreamRegistry.ActiveStream(1L, new SseEmitter());

        ScheduledFuture<?> result = heartbeat.start(stream, () -> {});

        assertThat((Object) result).isNull();
        verifyNoInteractions(scheduler);
    }

    @Test
    void invalidInterval_shouldNotScheduleTask() {
        // 周期非法时视为禁用，避免调度异常
        properties.setHeartbeatIntervalSeconds(0);
        AiStreamRegistry.ActiveStream stream = new AiStreamRegistry.ActiveStream(1L, new SseEmitter());

        ScheduledFuture<?> result = heartbeat.start(stream, () -> {});

        assertThat((Object) result).isNull();
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    void configuredPoolSize_shouldCreateBoundedScheduler() {
        // 有界池是多流隔离的基础；池大小必须来自配置而不是固定单线程
        properties.setHeartbeatPoolSize(5);
        AiChatStreamHeartbeat realHeartbeat = new AiChatStreamHeartbeat(properties);
        try {
            ScheduledExecutorService scheduler =
                    (ScheduledExecutorService) ReflectionTestUtils.getField(realHeartbeat, "scheduler");
            ThreadPoolExecutor executor = (ThreadPoolExecutor) scheduler;
            assertThat(executor.getCorePoolSize()).isEqualTo(5);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(Integer.MAX_VALUE);
        } finally {
            realHeartbeat.shutdown();
        }
    }

    @Test
    void concurrentHeartbeats_shouldNotBlockEachOther() throws Exception {
        // 第一个心跳等待释放时，第二个心跳仍应能在同一线程池中执行
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();

        properties.setHeartbeatIntervalSeconds(1);
        AiChatStreamHeartbeat realHeartbeat = new AiChatStreamHeartbeat(properties);
        AiStreamRegistry.ActiveStream first = new AiStreamRegistry.ActiveStream(1L, new SseEmitter());
        AiStreamRegistry.ActiveStream second = new AiStreamRegistry.ActiveStream(2L, new SseEmitter());
        realHeartbeat.start(first, () -> {
            firstThread.set(Thread.currentThread());
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        realHeartbeat.start(second, () -> {
            secondThread.set(Thread.currentThread());
            secondCompleted.countDown();
        });

        try {
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(firstThread.get()).isNotSameAs(secondThread.get());
        } finally {
            releaseFirst.countDown();
            realHeartbeat.shutdown();
        }
    }
}
