package com.slz.crm.platform.contract;

/**
 * Token 计量上报入口（冻结契约，Lane D 提供实现，B/C 调用）。
 *
 * <p>设计取舍：方法名只保留一个 {@link #record(TokenUsageRecord)}，
 * 避免“chat 用 A 接口、embedding 用 B 接口”导致漏记；
 * 全部场景（含意图/摘要旁路）都走同一入口，治理侧才可能对齐账。</p>
 *
 * <p>失败边界：实现 MUST 不抛异常（计量失败不应打断业务流），
 * 但要记录告警日志，否则会出现“账不对还查不出原因”。</p>
 */
public interface TokenUsageRecorder {

    /**
     * 上报一次模型调用计量。
     *
     * @param record 计量明细；type/success 必填，token 数允许为 null（Provider 未返回）
     */
    void record(TokenUsageRecord record);
}
