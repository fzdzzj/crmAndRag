package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.ai.AiDraftResult;
import com.slz.crm.pojo.entity.AiPendingActionEntity;
import com.slz.crm.pojo.vo.AiConfirmResultVO;
import com.slz.crm.pojo.vo.AiPendingActionVO;
import com.slz.crm.server.ai.enums.PendingActionStatus;
import com.slz.crm.server.ai.executor.AiActionExecutor;
import com.slz.crm.server.ai.executor.AiExecutionResult;
import com.slz.crm.server.ai.validation.AiActionValidator;
import com.slz.crm.server.ai.validation.AiValidationResult;
import com.slz.crm.server.mapper.AiPendingActionMapper;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.PendingActionService;
import com.slz.crm.server.service.PermissionService;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** AI 待确认操作状态机服务实现 校验与执行按 actionType 策略分发，扩展新操作类型只需新增 Validator/Executor Bean */
@Slf4j
@Service
public class PendingActionServiceImpl
    extends ServiceImpl<AiPendingActionMapper, AiPendingActionEntity>
    implements PendingActionService {

  @Autowired private AiProperties aiProperties;

  @Autowired private PermissionService permissionService;

  @Autowired private List<AiActionValidator> validators;

  @Autowired private List<AiActionExecutor> executors;

  @Autowired @Lazy private PendingActionService self;

  /** actionType → 校验器 */
  private final Map<String, AiActionValidator> validatorMap = new HashMap<>();

  /** actionType → 执行器 */
  private final Map<String, AiActionExecutor> executorMap = new HashMap<>();

  @PostConstruct
  void initStrategyMaps() {
    validators.forEach(v -> validatorMap.put(v.actionType(), v));

    executors.forEach(e -> executorMap.put(e.actionType(), e));

    log.info(
        "AI 操作策略注册完成: validators={}, executors={}", validatorMap.keySet(), executorMap.keySet());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiDraftResult submitDraft(
      Long sessionId, Long userId, String actionType, String payloadJson) {
    AiActionValidator validator = PendingActionGuards.requireValidator(actionType, validatorMap);

    AiValidationResult vr = validator.validate(payloadJson);

    AiPendingActionEntity entity = new AiPendingActionEntity();

    entity.setPendingId(PendingActionPayloadSupport.generatePendingId());

    entity.setSessionId(sessionId);

    entity.setUserId(userId);

    entity.setActionType(actionType);

    // 实体解析后的修正 payload 优先（名称→ID 解析结果落库）
    String effectivePayload =
        vr.getResolvedPayload() != null ? vr.getResolvedPayload() : payloadJson;

    entity.setPayload(effectivePayload);

    entity.setAskRound(0);

    entity.setCreatedTime(LocalDateTime.now());

    entity.setUpdatedTime(LocalDateTime.now());

    entity.setExpireTime(LocalDateTime.now().plusMinutes(aiProperties.getPendingExpireMinutes()));

    if (vr.isValid()) {

      entity.setStatus(PendingActionStatus.PENDING.getValue());

      entity.setPreview(PendingActionPayloadSupport.buildPreview(effectivePayload));

    } else {
      entity.setStatus(PendingActionStatus.DRAFTING.getValue());

      entity.setMissingFields(PendingActionPayloadSupport.toJson(vr.getMissingFields()));
    }
    save(entity);

    return PendingActionPayloadSupport.toDraftResult(entity, vr);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiDraftResult mergeDraft(String pendingId, Long userId, String incrementJson) {
    AiPendingActionEntity entity =
        PendingActionGuards.getOwnedEntity(pendingId, userId, this::getByPendingId);

    if (!PendingActionStatus.DRAFTING.getValue().equals(entity.getStatus())) {

      throw new BaseException(ErrorCode.PARAM_REQUIRED, "当前状态不可合并参数");
    }

    // 合并增量参数到 payload（增量覆盖/补入）
    String mergedPayload =
        PendingActionPayloadSupport.mergePayload(entity.getPayload(), incrementJson);

    entity.setAskRound(entity.getAskRound() + 1);

    entity.setUpdatedTime(LocalDateTime.now());

    // 全量重校验（实体解析后的修正 payload 优先）
    AiValidationResult vr =
        PendingActionGuards.requireValidator(entity.getActionType(), validatorMap)
            .validate(mergedPayload);

    String effectivePayload =
        vr.getResolvedPayload() != null ? vr.getResolvedPayload() : mergedPayload;

    entity.setPayload(effectivePayload);

    if (vr.isValid()) {

      entity.setStatus(PendingActionStatus.PENDING.getValue());

      entity.setMissingFields(null);

      entity.setExpireTime(LocalDateTime.now().plusMinutes(aiProperties.getPendingExpireMinutes()));

      entity.setPreview(PendingActionPayloadSupport.buildPreview(effectivePayload));

    } else {
      entity.setStatus(PendingActionStatus.DRAFTING.getValue());

      entity.setMissingFields(PendingActionPayloadSupport.toJson(vr.getMissingFields()));
    }
    updateById(entity);

    return PendingActionPayloadSupport.toDraftResult(entity, vr);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 执行器外呼+ORM确认多源，失败标记FAILED后按业务异常上抛
  public AiConfirmResultVO confirm(String pendingId, Long userId) {
    AiPendingActionEntity entity =
        PendingActionGuards.getOwnedEntity(pendingId, userId, this::getByPendingId);

    // 终态幂等（本次未真实执行，不携带引用）
    PendingActionStatus currentStatus = PendingActionStatus.fromValue(entity.getStatus());
    AiConfirmResultVO result;
    if (currentStatus != null
        && (currentStatus == PendingActionStatus.CONFIRMED
            || currentStatus == PendingActionStatus.CANCELLED
            || currentStatus == PendingActionStatus.EXPIRED)) {

      String idempotentResult =
          entity.getResult() != null ? entity.getResult() : "{\"message\":\"操作已处理\"}";

      result = PendingActionPayloadSupport.toConfirmResult(idempotentResult, List.of());
    } else {
      result = executePendingAction(entity, pendingId, userId);
    }
    return result;
  }

  /**
   * 执行待确认操作主体：权限校验、状态与过期校验、CAS 抢占、策略分发执行（拆自 confirm，行为等价）。
   *
   * @param entity 待确认动作实体
   * @param pendingId 待确认动作 ID
   * @param userId 当前用户 ID
   * @return 确认结果
   */
  /** 执行器为外部 AI 动作实现，失败形态不可枚举；须吞任意异常统一落失败标记后按业务异常上抛（tighten-pmd-residual-325 任务 6.3） */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private AiConfirmResultVO executePendingAction(
      AiPendingActionEntity entity, String pendingId, Long userId) {
    // 运行时权限校验（按 actionType 映射）
    PendingActionGuards.requirePermission(entity.getActionType(), userId, permissionService);

    if (!PendingActionStatus.PENDING.getValue().equals(entity.getStatus())
        && !PendingActionStatus.FAILED.getValue().equals(entity.getStatus())) {

      throw new BaseException(ErrorCode.AI_ACTION_PARAM_MISSING, "当前状态不可确认，请先补齐参数");
    }

    // 校验过期
    if (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now())) {

      self.markExpired(pendingId);

      throw new BaseException(ErrorCode.AI_ACTION_TIMEOUT, "确认已超时，请重新发起");
    }

    // 条件更新抢占执行权（防并发双击）
    int affected =
        baseMapper.update(
            null,
            new UpdateWrapper<AiPendingActionEntity>()
                .eq("pending_id", pendingId)
                .and(
                    w ->
                        w.eq("status", PendingActionStatus.PENDING.getValue())
                            .or()
                            .eq("status", PendingActionStatus.FAILED.getValue()))
                .set("status", PendingActionStatus.CONFIRMED.getValue())
                .set("confirmed_time", LocalDateTime.now())
                .set("updated_time", LocalDateTime.now()));

    if (affected == 0) {

      throw new BaseException(ErrorCode.AI_ACTION_ALREADY_HANDLED, "操作已被处理");
    }

    // 策略分发执行（以库中 payload 为准，忽略请求体）
    AiActionExecutor executor = executorMap.get(entity.getActionType());

    if (executor == null) {

      throw new BaseException(
          ErrorCode.AI_ACTION_PARAM_MISSING, "不支持的操作类型: " + entity.getActionType());
    }
    AiExecutionResult execResult;
    try {
      execResult = executor.execute(entity.getPayload());

      update(
          new UpdateWrapper<AiPendingActionEntity>()
              .eq("pending_id", pendingId)
              .set("result", execResult.getResult()));

    } catch (Exception e) {

      log.error("执行待确认操作失败, pendingId={}", pendingId, e);

      markFailedAfterRollback(pendingId, buildErrorJson());

      throw new BaseException(ErrorCode.AI_ACTION_EXECUTE_FAILED, "执行失败，请稍后重试");
    }
    return PendingActionPayloadSupport.toConfirmResult(
        execResult.getResult(), execResult.getReferences());
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markExpired(String pendingId) {
    update(
        new UpdateWrapper<AiPendingActionEntity>()
            .eq("pending_id", pendingId)
            .eq("status", PendingActionStatus.PENDING.getValue())
            .set("status", PendingActionStatus.EXPIRED.getValue())
            .set("updated_time", LocalDateTime.now()));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(String pendingId, String resultJson) {
    update(
        new UpdateWrapper<AiPendingActionEntity>()
            .eq("pending_id", pendingId)
            .and(
                w ->
                    w.eq("status", PendingActionStatus.PENDING.getValue())
                        .or()
                        .eq("status", PendingActionStatus.FAILED.getValue()))
            .set("status", PendingActionStatus.FAILED.getValue())
            .set("result", resultJson)
            .set("updated_time", LocalDateTime.now()));
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 回滚后写失败状态旁路，失败仅记日志
  private void markFailedAfterRollback(String pendingId, String resultJson) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      self.markFailed(pendingId, resultJson);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
              return;
            }
            try {
              self.markFailed(pendingId, resultJson);
            } catch (Exception markFailedException) {
              log.error("回滚后写入执行失败状态异常, pendingId={}", pendingId, markFailedException);
            }
          }
        });
  }

  /** 失败结果持久化为稳定文案；异常细节只保留在服务端日志，避免后续查询接口透出。 */
  private String buildErrorJson() {
    return "{\"error\":\"执行失败\"}";
  }

  @Override
  public void cancel(String pendingId, Long userId) {
    AiPendingActionEntity entity =
        PendingActionGuards.getOwnedEntity(pendingId, userId, this::getByPendingId);

    PendingActionStatus currentStatus = PendingActionStatus.fromValue(entity.getStatus());
    if (currentStatus != null && currentStatus.isCancellable()) {

      update(
          new UpdateWrapper<AiPendingActionEntity>()
              .eq("pending_id", pendingId)
              .in(
                  "status",
                  PendingActionStatus.DRAFTING.getValue(),
                  PendingActionStatus.PENDING.getValue())
              .set("status", PendingActionStatus.CANCELLED.getValue())
              .set("confirmed_time", LocalDateTime.now())
              .set("updated_time", LocalDateTime.now()));
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiPendingActionVO edit(String pendingId, Long userId, String payloadJson) {
    AiPendingActionEntity entity =
        PendingActionGuards.getOwnedEntity(pendingId, userId, this::getByPendingId);

    // 运行时权限校验（与 confirm 一致）
    PendingActionGuards.requirePermission(entity.getActionType(), userId, permissionService);

    PendingActionStatus currentStatus = PendingActionStatus.fromValue(entity.getStatus());
    if (currentStatus != null && currentStatus.isTerminal()) {

      throw new BaseException(ErrorCode.PARAM_REQUIRED, "操作已处理/已超时，不可编辑");
    }
    if (payloadJson == null || payloadJson.isBlank()) {

      throw new BaseException(ErrorCode.PARAM_REQUIRED, "编辑参数不能为空");
    }

    entity.setUpdatedTime(LocalDateTime.now());

    // 重新校验（实体解析后的修正 payload 优先）
    AiValidationResult vr =
        PendingActionGuards.requireValidator(entity.getActionType(), validatorMap)
            .validate(payloadJson);

    String effectivePayload =
        vr.getResolvedPayload() != null ? vr.getResolvedPayload() : payloadJson;

    entity.setPayload(effectivePayload);

    if (vr.isValid()) {

      entity.setStatus(PendingActionStatus.PENDING.getValue());

      entity.setMissingFields(null);

      // expire_time 重算
      entity.setExpireTime(LocalDateTime.now().plusMinutes(aiProperties.getPendingExpireMinutes()));

      entity.setPreview(PendingActionPayloadSupport.buildPreview(effectivePayload));

    } else {
      entity.setStatus(PendingActionStatus.DRAFTING.getValue());

      entity.setMissingFields(PendingActionPayloadSupport.toJson(vr.getMissingFields()));
    }
    updateById(entity);

    return PendingActionPayloadSupport.toVO(entity);
  }

  @Override
  public AiPendingActionVO getStatus(String pendingId, Long userId) {
    AiPendingActionEntity entity = getByPendingId(pendingId);

    AiPendingActionVO result;

    if (entity == null || !entity.getUserId().equals(userId)) {

      result = null;
    } else {
      result = PendingActionPayloadSupport.toVO(entity);
    }
    return result;
  }

  @Override
  public AiPendingActionEntity getByPendingId(String pendingId) {
    return getOne(
        new LambdaQueryWrapper<AiPendingActionEntity>()
            .eq(AiPendingActionEntity::getPendingId, pendingId),
        false);
  }

  @Override
  public void cancelBySessionId(Long sessionId) {
    update(
        new UpdateWrapper<AiPendingActionEntity>()
            .eq("session_id", sessionId)
            .in(
                "status",
                PendingActionStatus.DRAFTING.getValue(),
                PendingActionStatus.PENDING.getValue())
            .set("status", PendingActionStatus.CANCELLED.getValue())
            .set("confirmed_time", LocalDateTime.now())
            .set("updated_time", LocalDateTime.now()));
  }

  @Override
  public int expireOverdue() {
    return baseMapper.update(
        null,
        new UpdateWrapper<AiPendingActionEntity>()
            .eq("status", PendingActionStatus.PENDING.getValue())
            .lt("expire_time", LocalDateTime.now())
            .set("status", PendingActionStatus.EXPIRED.getValue())
            .set("updated_time", LocalDateTime.now()));
  }

  // ==================== 私有方法 ====================
}
