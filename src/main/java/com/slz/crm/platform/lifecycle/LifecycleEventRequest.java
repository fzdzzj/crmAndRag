package com.slz.crm.platform.lifecycle;

/**
 * 文档生命周期事件请求。
 *
 * @param documentId 文档业务 ID
 * @param eventType 事件类型
 * @param statusVersion 状态版本，单调递增
 * @param idempotencyKey 幂等键，通常为 {@code documentId:eventType:version:source}
 * @param payload 事件扩展数据
 */
public record LifecycleEventRequest(
    String documentId,
    LifecycleEventType eventType,
    long statusVersion,
    String idempotencyKey,
    String payload) {}
