package com.slz.crm.platform.config.service;

import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.ConfigOperationType;
import com.slz.crm.platform.config.ConfigOperator;
import com.slz.crm.platform.config.audit.DynamicConfigAuditEvent;
import com.slz.crm.platform.config.audit.DynamicConfigAuditRecorder;
import com.slz.crm.platform.config.audit.NoOpDynamicConfigAuditRecorder;
import com.slz.crm.platform.config.entity.DynamicConfigHistoryEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigHistoryMapper;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 动态配置的版本历史追加与审计旁路协作类（tighten-pmd-residual-325 任务 6.3 拆自
 * DynamicConfigAdminService，行为等价）。由主类经工厂方法构造，复用主类注入的 historyMapper 与审计 recorder ObjectProvider，保证测试
 * mock 行为不变。
 */
@Slf4j
class DynamicConfigAuditTrail {

  private final DynamicConfigHistoryMapper historyMapper;
  private final ObjectProvider<DynamicConfigAuditRecorder> auditRecorders;

  DynamicConfigAuditTrail(
      DynamicConfigHistoryMapper historyMapper,
      ObjectProvider<DynamicConfigAuditRecorder> auditRecorders) {
    this.historyMapper = historyMapper;
    this.auditRecorders = auditRecorders;
  }

  /** 追加一行版本历史（存真实值，回滚需要；展示层再掩码） */
  void appendHistory(
      Long configId,
      ConfigKeyDefinition def,
      int version,
      ConfigOperationType operation,
      String oldValue,
      String newValue,
      ConfigOperator op,
      String remark) {
    DynamicConfigHistoryEntity history = new DynamicConfigHistoryEntity();
    history.setConfigId(configId);
    history.setConfigKey(def.key());
    history.setValueType(def.type().name());
    history.setVersion(version);
    history.setOperationType(operation.name());
    history.setOldValue(oldValue);
    history.setNewValue(newValue);
    history.setOperatorRef(op.ref());
    history.setRemark(remark);
    history.setCreateTime(LocalDateTime.now());
    historyMapper.insert(history);
  }

  /** 审计事件（敏感值掩码后交 D 审计流 / 过渡期日志兜底） */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 审计旁路：recorder实现可抛任意运行时，不打断配置主流程
  void recordAudit(
      ConfigKeyDefinition def,
      ConfigOperationType operation,
      String oldValue,
      String newValue,
      ConfigOperator op,
      String remark) {
    DynamicConfigAuditRecorder recorder =
        auditRecorders.getIfAvailable(NoOpDynamicConfigAuditRecorder::new);
    try {
      recorder.record(
          new DynamicConfigAuditEvent(
              op.ref(),
              op.displayName(),
              def.key(),
              def.namespace(),
              operation.name(),
              DynamicConfigViewSupport.mask(oldValue, def.sensitive()),
              DynamicConfigViewSupport.mask(newValue, def.sensitive()),
              remark,
              LocalDateTime.now()));
    } catch (RuntimeException e) {
      // 审计是旁路：实现方异常不得打断配置主流程（规范见 DynamicConfigAuditRecorder 类注释）
      log.warn("动态配置审计记录失败（已降级）：key={}, operation={}", def.key(), operation, e);
    }
  }
}
