package com.slz.crm.server.ai;

/**
 * SSE 断线续传参数。
 *
 * @param generationId start 事件下发的生成标识
 * @param lastEventId 客户端最后成功接收的事件 id；可为空表示从头重放
 */
public record AiChatResume(String generationId, String lastEventId) {
}
