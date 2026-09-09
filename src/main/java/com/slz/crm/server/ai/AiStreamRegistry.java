package com.slz.crm.server.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AI 活跃流注册表（每会话同时只有一个活跃流）
 * 用于支持"生成中取消"：持有 Flux 订阅句柄与部分内容，供 cancel/断连兜底使用
 */
@Slf4j

@Component
public class AiStreamRegistry {


    private final ConcurrentHashMap<Long, ActiveStream> streams = new ConcurrentHashMap<>();

    /** 固定数量的会话接管锁；避免为每个会话保留锁对象 */
    private final ReentrantLock[] takeoverLocks = new ReentrantLock[64];

    public AiStreamRegistry() {
        for (int i = 0; i < takeoverLocks.length; i++) {
            takeoverLocks[i] = new ReentrantLock();
        }
    }


    /**
     * 注册活跃流（若已有旧流先 dispose）
     */
    public void register(Long sessionId, ActiveStream stream) {
        ActiveStream old = streams.put(sessionId, stream);

        if (old != null) {
            old.stopHeartbeat();
            if (old.getSubscription() != null && !old.getSubscription().isDisposed()) {

            log.info("会话 {} 存在旧的活跃流，先终止", sessionId);

                old.getSubscription().dispose();
            }

        }
    }


    public ActiveStream get(Long sessionId) {
        return streams.get(sessionId);

    }

    /**
     * 同一会话的“结束旧流 -> 保存片段 -> 注册新流”必须串行执行；
     * 使用固定锁槽位避免注册表随历史会话数量无限增长。
     */
    public Lock takeoverLock(Long sessionId) {
        return takeoverLocks[Math.floorMod(sessionId, takeoverLocks.length)];
    }

    /** 当前活跃流数量，供 Micrometer Gauge 读取。 */
    public long activeCount() {
        return streams.size();
    }

    /**
     * 无条件移除（仅限确定无并发替换的场景）
     */
    public void remove(Long sessionId) {
        streams.remove(sessionId);

    }


    /**
     * 条件移除：仅当当前注册的流仍是 expected 时才移除。
     * 防止 cancel/断连兜底处理旧流时误删并发注册的新流
     * （用户取消后立即重发消息的场景）
     */
    public boolean remove(Long sessionId, ActiveStream expected) {
        return streams.remove(sessionId, expected);

    }

    /**
     * 活跃流上下文
     */
    public static class ActiveStream {
        /** Flux 订阅句柄（dispose 终止 LLM 流） */
        private volatile Disposable subscription;

        /** SSE 心跳任务句柄（终态后自动取消） */
        private volatile ScheduledFuture<?> heartbeatFuture;

        /** 已输出的部分内容 */
        private final StringBuilder partialAnswer = new StringBuilder();


        /** SSE 发射器 */
        private final SseEmitter emitter;

        /** 会话ID */
        private final Long sessionId;

        /** 防完成/取消竞态双写：doOnComplete / doOnError / cancel / 断连兜底四条路径共用 */
        private final AtomicBoolean finished = new AtomicBoolean(false);

        /** 流开始时间（单调时钟），供 Timer 计算总耗时 */
        private final long startNanos = System.nanoTime();

        /** 首个回答片段时间（单调时钟）；0 表示尚未收到 */
        private final AtomicLong firstTokenNanos = new AtomicLong();

        /** 当前订阅上下文，供错误路径继承并降级 */
        private volatile AiChatStreamContext context;


        public ActiveStream(Long sessionId, SseEmitter emitter) {

            this.sessionId = sessionId;

            this.emitter = emitter;

        }


        public Disposable getSubscription() {
            return subscription;

        }

        public void setSubscription(Disposable subscription) {
            this.subscription = subscription;

        }

        public void setHeartbeatFuture(ScheduledFuture<?> future) {
            ScheduledFuture<?> previous = this.heartbeatFuture;
            this.heartbeatFuture = future;
            // 替换旧任务，避免同一会话并发注册后旧心跳继续发送
            if (previous != null && !previous.isDone()) {
                previous.cancel(false);
            }
        }

        public boolean isCurrentHeartbeat(ScheduledFuture<?> future) {
            return future != null && future == this.heartbeatFuture;
        }

        /** 清空并取消当前心跳任务，确保终态后不再发送心跳 */
        public void stopHeartbeat() {
            ScheduledFuture<?> future = this.heartbeatFuture;
            this.heartbeatFuture = null;
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }

        public AiChatStreamContext getContext() {
            return context;
        }

        public void setContext(AiChatStreamContext context) {
            this.context = context;
        }


        public StringBuilder getPartialAnswer() {
            return partialAnswer;

        }

        public SseEmitter getEmitter() {
            return emitter;

        }

        public Long getSessionId() {
            return sessionId;

        }

        /** 返回流开始时间（单调时钟，纳秒） */
        public long getStartNanos() {
            return startNanos;
        }

        /** CAS 记录首个回答片段时间，重复调用返回 false */
        public boolean markFirstToken() {
            return firstTokenNanos.compareAndSet(0, System.nanoTime());
        }

        /** 返回首个回答片段时间；未收到时返回 null */
        public Long getFirstTokenNanos() {
            long value = firstTokenNanos.get();
            return value == 0 ? null : value;
        }

        /**
         * 抢占完成标记（仅第一个调用者成功）
         */
        public boolean tryMarkFinished() {
            if (finished.compareAndSet(false, true)) {
                stopHeartbeat();
                return true;
            }
            return false;

        }

        public boolean isFinished() {
            return finished.get();

        }
    }
}
