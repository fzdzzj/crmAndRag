package com.slz.crm.platform.config.audit;

import com.slz.crm.platform.audit.GovernanceAuditEvent;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.audit.GovernanceAuditResult;
import org.springframework.stereotype.Component;

/**
 * 集成接缝（Seam 3）：把 Lane E 的动态配置审计事件转接到 Lane D 的治理审计流。
 *
 * <p>替换 {@link NoOpDynamicConfigAuditRecorder} 的过渡兜底：E 的 {@code DynamicConfigAdminService}
 * 以 {@code ObjectProvider<DynamicConfigAuditRecorder>} 可选注入（{@code getIfAvailable(NoOp::new)}），
 * 本 {@code @Component} 就位后自动命中，配置变更即落入 D 的 {@code platform_governance_audit} 表——
 * 与配额调整/清理/对账等治理动作共用同一条审计流，避免双轨审计、责任重叠。</p>
 *
 * <p>脱敏：{@code oldValue/newValue} 已由 E 服务层按敏感度掩码，本类 detail 不出现明文。</p>
 * <p>旁路语义：审计失败不得打断配置主流程；{@link GovernanceAuditRecorder#record} 内部已对落库异常降级记日志，
 * 故本类不做额外 try/catch（异常传播由 E 服务层的 recordAudit 兜底捕获）。</p>
 */
@Component
public class GovernanceDynamicConfigAuditRecorder implements DynamicConfigAuditRecorder {

    /** 治理审计事件类型前缀；与 D 的 QUOTA_ADJUSTED/CLEANUP_TRIGGERED 等并列可辨识。 */
    private static final String EVENT_TYPE_PREFIX = "DYNAMIC_CONFIG_";
    /** 目标类型：动态配置项。 */
    private static final String TARGET_TYPE = "DYNAMIC_CONFIG";

    private final GovernanceAuditRecorder governanceAuditRecorder;

    public GovernanceDynamicConfigAuditRecorder(GovernanceAuditRecorder governanceAuditRecorder) {
        this.governanceAuditRecorder = governanceAuditRecorder;
    }

    @Override
    public void record(DynamicConfigAuditEvent event) {
        governanceAuditRecorder.record(new GovernanceAuditEvent(
                EVENT_TYPE_PREFIX + event.operationType(),
                event.operatorRef(),
                TARGET_TYPE,
                event.key(),
                event.operationType(),
                GovernanceAuditResult.SUCCESS,
                buildDetail(event)));
    }

    /**
     * 组装扩展信息（键值串，非 JSON，规避值内含引号导致的转义问题；值已掩码）。
     *
     * @param event 配置审计事件
     * @return 供治理审计 detail 列存储的可读串
     */
    private String buildDetail(DynamicConfigAuditEvent event) {
        return "namespace=" + nullToEmpty(event.namespace())
                + "; operatorName=" + nullToEmpty(event.operatorName())
                + "; old=" + nullToEmpty(event.oldValue())
                + "; new=" + nullToEmpty(event.newValue())
                + "; remark=" + nullToEmpty(event.remark());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
