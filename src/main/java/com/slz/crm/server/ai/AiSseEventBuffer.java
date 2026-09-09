package com.slz.crm.server.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 业务事件有界缓冲。
 *
 * <p>断线续传只重放已成功发出的业务事件；心跳注释帧不进入缓冲。</p>
 */
final class AiSseEventBuffer {

    /** 默认事件条数上限，防止长回答或异常刷屏拖垮内存。 */
    private static final int MAX_EVENTS = 1000;

    /** 默认缓冲字节上限，与条数上限共同约束极端大 payload。 */
    private static final int MAX_BYTES = 256 * 1024;

    private final String generationId;
    private final ArrayDeque<BufferedEvent> events = new ArrayDeque<>();
    private int totalBytes;
    private final AtomicLong sequence = new AtomicLong();

    AiSseEventBuffer(String generationId) {
        this.generationId = generationId;
    }

    /**
     * 分配 generation 内单调递增事件号。
     *
     * @return SSE id，格式 {@code <generationId>:<seq>}
     */
    synchronized String nextEventId() {
        return generationId + ":" + sequence.incrementAndGet();
    }

    /**
     * 追加已成功发送的事件；超限时先淘汰最旧业务事件。
     *
     * @param eventId SSE id
     * @param eventName 事件名
     * @param data JSON 文本
     */
    synchronized void append(String eventId, String eventName, String data) {
        BufferedEvent event = new BufferedEvent(eventId, eventName, data);
        events.addLast(event);
        totalBytes += event.sizeBytes();
        while (events.size() > MAX_EVENTS || totalBytes > MAX_BYTES) {
            BufferedEvent removed = events.pollFirst();
            if (removed == null) {
                break;
            }
            totalBytes -= removed.sizeBytes();
        }
    }

    /**
     * 重放指定事件之后的所有缓冲事件。
     *
     * @param lastEventId 客户端最后收到的事件 id；null 表示从重放点开始
     * @return 有序事件快照
     */
    synchronized List<BufferedEvent> eventsAfter(String lastEventId) {
        long lastSeq = parseSequence(lastEventId);
        List<BufferedEvent> result = new ArrayList<>();
        for (BufferedEvent event : events) {
            if (parseSequence(event.eventId()) > lastSeq) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    /**
     * @return 最新事件 id；缓冲为空返回 null
     */
    synchronized String lastEventId() {
        return events.isEmpty() ? null : events.peekLast().eventId();
    }

    /**
     * 只接受当前 generation 的事件 id，避免跨 generation 误续传。
     */
    private long parseSequence(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return 0L;
        }
        String prefix = generationId + ":";
        if (!eventId.startsWith(prefix)) {
            return 0L;
        }
        try {
            return Long.parseLong(eventId.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** 缓冲事件不可变快照。 */
    record BufferedEvent(String eventId, String eventName, String data) {
        private int sizeBytes() {
            return eventId.length() + eventName.length() + (data == null ? 0 : data.length());
        }
    }
}
