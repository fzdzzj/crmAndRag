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

    /** generationId 到活跃/最近终态流的索引，用于按 Last-Event-ID 续传。 */
    private final ConcurrentHashMap<String, ActiveStream> generations = new ConcurrentHashMap<>();

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
            // 接管不硬中断旧模型调用；旧流在下一个 shouldAbort 检查点协作退出。
            log.info("会话 {} 存在旧的活跃流，等待其协作式退出", sessionId);
        }
        generations.put(stream.getGenerationId(), stream);
    }


    public ActiveStream get(Long sessionId) {
        return streams.get(sessionId);

    }

    /**
     * 按 generationId 查找可续传流。
     *
     * @param generationId start 事件下发的生成标识
     * @return 流状态；缓冲淘汰或重启后返回 null
     */
    public ActiveStream getByGenerationId(String generationId) {
        return generationId == null ? null : generations.get(generationId);
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
        boolean removed = streams.remove(sessionId, expected);
        if (removed) {
            // 终态流保留在 generation 缓冲索引中，供断线重连重放终态与答案。
            expected.markCompleted();
        }
        return removed;

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

        /** 当前生成标识，同时是 SSE 事件 id 的根。 */
        private final String generationId;

        /** 断线续传事件缓冲。 */
        private final AiSseEventBuffer eventBuffer;

        /** 用户/接管取消标记；与终态 CAS 分离，便于区分保存路径。 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /** registry 终态完成标记，用于重连后判断是否只重放。 */
        private volatile boolean completed;


        /** SSE 发射器；断线重连时可替换到新连接。 */
        private volatile SseEmitter emitter;

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

        /** start 事件已回传的助手占位消息 ID。 */
        private volatile Long assistantMessageId;


        public ActiveStream(Long sessionId, SseEmitter emitter) {
            this(sessionId, emitter, java.util.UUID.randomUUID().toString());
        }

        public ActiveStream(Long sessionId, SseEmitter emitter, String generationId) {

            this.sessionId = sessionId;

            this.emitter = emitter;
            this.generationId = generationId == null || generationId.isBlank()
                    ? java.util.UUID.randomUUID().toString() : generationId;
            this.eventBuffer = new AiSseEventBuffer(this.generationId);

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

        public void setAssistantMessageId(Long assistantMessageId) {
            this.assistantMessageId = assistantMessageId;
        }

        public Long getAssistantMessageId() {
            return assistantMessageId;
        }


        public StringBuilder getPartialAnswer() {
            return partialAnswer;

        }

        public String getGenerationId() {
            return generationId;
        }

        public AiSseEventBuffer getEventBuffer() {
            return eventBuffer;
        }

        /**
         * 标记用户取消；接管场景由 shouldAbort 根据注册表当前流判断。
         *
         * @return 首次标记返回 true
         */
        public boolean markCancelled() {
            return cancelled.compareAndSet(false, true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void markCompleted() {
            this.completed = true;
        }

        public boolean isCompleted() {
            return completed;
        }

        /**
         * 续传时替换到新 SSE 连接；调用方负责先重放缓冲。
         *
         * @param newEmitter 新连接
         * @return 旧连接，便于关闭；无旧连接返回 null
         */
        public synchronized SseEmitter attachResumeEmitter(SseEmitter newEmitter) {
            SseEmitter previous = this.emitter;
            this.emitter = newEmitter;
            return previous;
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
