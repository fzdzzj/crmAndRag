package com.slz.crm.platform.token;

import com.slz.crm.platform.contract.TokenUsageType;

/**
 * 模型调用前的预算检查请求。
 *
 * @param scope 预算维度
 * @param scopeId 用户引用/会话ID/知识库ID；全局传 {@code GLOBAL}
 * @param usageType 即将发生的调用类型；为空表示检查所有类型
 * @param estimatedTokens 调用方给出的预估 token 数；未知可传 0
 */
public record TokenBudgetRequest(
        TokenBudgetScope scope,
        String scopeId,
        TokenUsageType usageType,
        long estimatedTokens) {
}
