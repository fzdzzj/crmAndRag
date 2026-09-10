package com.slz.crm.platform.quota;

/**
 * 配额检查结果。
 *
 * @param allowed 是否允许请求
 * @param used 当前窗口已用量
 * @param limit 当前窗口上限
 * @param retryAfterSeconds 被拒绝后建议等待秒数
 * @param reason 机器可读原因
 */
public record QuotaDecision(
        boolean allowed,
        long used,
        long limit,
        long retryAfterSeconds,
        String reason) {
}
