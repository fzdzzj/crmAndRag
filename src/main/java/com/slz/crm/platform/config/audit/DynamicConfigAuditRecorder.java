package com.slz.crm.platform.config.audit;

/**
 * 动态配置审计落点接口（本 lane 的 lane-local 审计端口）。
 *
 * <p><b>与 Lane D 治理审计流的关系（agent-e 提示词「配置变更审计复用 D 的治理审计流」）：</b>
 * D 的审计实现仍在 {@code feature/lane-d-governance}（未进 spec），本域没有可编程的冻结审计契约。
 * 因此本接口 + 无操作兜底（{@link NoOpDynamicConfigAuditRecorder}）是<b>集成缝</b>：
 * 服务层以 {@code ObjectProvider<DynamicConfigAuditRecorder>} 可选注入——
 * D 实现合入后由 D/integrator 提供一个实现本接口的 bean（或把事件转接到 D 的审计存储），
 * 本域零改动即可切换为真实审计；未合入前自动落到无操作兜底，不影响功能。</p>
 *
 * <p>线程安全：实现方需自行保证线程安全（配置写入频率低，串行化即可）。</p>
 */
public interface DynamicConfigAuditRecorder {

    /**
     * 记录一条配置变更审计事件。
     *
     * <p>约定：实现方不得抛出运行时异常打断主流程（审计是旁路，失败应记日志降级）；</p>
     *
     * @param event 已掩码的审计事件
     */
    void record(DynamicConfigAuditEvent event);
}
