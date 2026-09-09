package com.slz.crm.platform.contract;

/**
 * 单次模型调用的计量明细（冻结契约）。
 *
 * @param model            实际模型名（可能是降级后的备用模型）
 * @param userIdRef        用户跨域引用 {@code user:<id>}；系统旁路任务用 {@code user:system}
 * @param sessionId        会话 id；非对话类调用（如批量入库）可传业务批次 id
 * @param knowledgeBaseId  知识库主键；非知识库调用为 null
 * @param type             计量类型
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens      总 token 数
 * @param success          是否成功（失败调用也要计量，便于核算重试成本）
 */
public record TokenUsageRecord(
        String model,
        String userIdRef,
        String sessionId,
        Long knowledgeBaseId,
        TokenUsageType type,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        boolean success) {
}
