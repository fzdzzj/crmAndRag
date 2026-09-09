package com.slz.crm.server.ai;

import com.slz.crm.server.properties.AiProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI SSE 心跳调度器。
 * 长模型调用或工具执行期间周期性发送轻量事件，避免代理链路判定连接空闲。
 */
@Component
public class AiChatStreamHeartbeat {

    private final AiProperties aiProperties;

    /** 小型有界调度池；多个 SSE 连接的心跳互不串行阻塞 */
    private final ScheduledExecutorService scheduler;

    @Autowired
    public AiChatStreamHeartbeat(AiProperties aiProperties) {
        this(aiProperties, createScheduler(resolvePoolSize(aiProperties)));
    }

    public AiChatStreamHeartbeat(AiProperties aiProperties, ScheduledExecutorService scheduler) {
        this.aiProperties = aiProperties;
        this.scheduler = scheduler;
    }

    /**
     * 启动当前活跃流的周期心跳。
     * 回调用当前任务校验防止旧流替换后继续触发。
     */
    public ScheduledFuture<?> start(AiStreamRegistry.ActiveStream activeStream, Runnable heartbeatAction) {
        Boolean enabled = aiProperties.getHeartbeatEnabled();
        Integer intervalSeconds = aiProperties.getHeartbeatIntervalSeconds();
        if (!Boolean.TRUE.equals(enabled) || intervalSeconds == null || intervalSeconds <= 0) {
            return null;
        }

        AtomicReference<ScheduledFuture<?>> currentFuture = new AtomicReference<>();
        long interval = intervalSeconds;
        // 回调先校验 future 仍是当前任务，避免活跃流被替换后旧心跳继续执行
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (activeStream.isCurrentHeartbeat(currentFuture.get())) {
                heartbeatAction.run();
            }
        }, interval, interval, TimeUnit.SECONDS);
        currentFuture.set(future);
        activeStream.setHeartbeatFuture(future);
        if (activeStream.isFinished()) {
            // start 与终态并发时，注册后立即停止刚创建的任务
            activeStream.stopHeartbeat();
        }
        return future;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** 创建守护线程调度器，应用关闭时不阻塞 JVM 退出。 */
    private static ScheduledExecutorService createScheduler(int poolSize) {
        return Executors.newScheduledThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "ai-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static int resolvePoolSize(AiProperties aiProperties) {
        Integer poolSize = aiProperties.getHeartbeatPoolSize();
        return poolSize == null || poolSize < 1 ? 4 : poolSize;
    }
}
