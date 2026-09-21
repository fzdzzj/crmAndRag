package com.slz.crm.platform.audit;

import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.mapper.PlatformGovernanceAuditMapper;
import com.slz.crm.platform.trace.RequestTraceKey;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 治理审计记录器。
 *
 * <p>授权变更、配额调整、清理、重建等管理动作必须可追溯。审计失败要记告警， 但不能反过来阻断已经执行的治理动作。
 */
@Component
public class GovernanceAuditRecorder {

  private static final Logger log = LoggerFactory.getLogger(GovernanceAuditRecorder.class);

  private final ObjectProvider<PlatformGovernanceAuditMapper> mapperProvider;
  private final MeterRegistry meterRegistry;

  /**
   * 构造审计记录器。
   *
   * @param mapperProvider 审计 Mapper 提供器
   * @param meterRegistry Micrometer 注册表
   */
  public GovernanceAuditRecorder(
      ObjectProvider<PlatformGovernanceAuditMapper> mapperProvider, MeterRegistry meterRegistry) {
    this.mapperProvider = mapperProvider;
    this.meterRegistry = meterRegistry;
  }

  /**
   * 记录治理审计事件。
   *
   * @param event 审计事件
   */
  public void record(GovernanceAuditEvent event) {
    Objects.requireNonNull(event, "治理审计事件不能为空");
    try {
      PlatformGovernanceAuditMapper mapper = mapperProvider.getIfAvailable();
      if (mapper == null) {
        throw new IllegalStateException("PlatformGovernanceAuditMapper 未装配");
      }
      mapper.insert(toEntity(event));
      meterRegistry
          .counter(
              "platform.governance.audit",
              "event",
              event.eventType(),
              "result",
              event.result() == null ? "UNKNOWN" : event.result().name())
          .increment();
    } catch (Exception exception) {
      meterRegistry.counter("platform.governance.audit.failure").increment();
      log.warn(
          "治理审计落库失败: eventType={}, target={}/{}",
          event.eventType(),
          event.targetType(),
          event.targetId(),
          exception);
    }
  }

  private PlatformGovernanceAuditEntity toEntity(GovernanceAuditEvent event) {
    PlatformGovernanceAuditEntity entity = new PlatformGovernanceAuditEntity();
    entity.setEventType(event.eventType());
    entity.setActorUserRef(resolveActor(event.actorUserRef()));
    entity.setTargetType(event.targetType());
    entity.setTargetId(event.targetId());
    entity.setAction(event.action());
    entity.setResult(
        event.result() == null ? GovernanceAuditResult.FAILED.name() : event.result().name());
    entity.setDetail(event.detail());
    entity.setTraceId(MDC.get(RequestTraceKey.TRACE_ID));
    LocalDateTime now = LocalDateTime.now();
    entity.setCreateTime(now);
    entity.setUpdateTime(now);
    return entity;
  }

  private String resolveActor(String actorUserRef) {
    String result = actorUserRef;
    if (actorUserRef == null || actorUserRef.isBlank()) {
      var context = UserContextHolder.current();
      result = context == null ? "anonymous" : context.userIdRef();
    }
    return result;
  }
}
