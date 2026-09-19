package com.slz.crm.platform.config.audit;

import lombok.extern.slf4j.Slf4j;

/**
 * 无操作审计兜底（D 审计实现合入前的默认落点）。
 *
 * <p>为什么是“无操作”而非“写本地表”：审计归属 D 的治理审计流（任务 15 的审计存储/查询/看板）， 本域自建审计表会造成双轨审计、责任重叠；集成时由 D 实现替换本兜底，见
 * {@link DynamicConfigAuditRecorder} 类注释。
 */
@Slf4j
public class NoOpDynamicConfigAuditRecorder implements DynamicConfigAuditRecorder {

  @Override
  public void record(DynamicConfigAuditEvent event) {
    // D 实现未接入：仅记日志，保证“配置变更审计可追溯”在过渡期不丢信息
    log.info(
        "[动态配置审计-过渡期] operator={}, key={}, operation={}, old={}, new={}, remark={}",
        event.operatorRef(),
        event.key(),
        event.operationType(),
        event.oldValue(),
        event.newValue(),
        event.remark());
  }
}
