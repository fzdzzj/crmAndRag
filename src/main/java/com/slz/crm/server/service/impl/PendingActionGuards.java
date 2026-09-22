package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.AiPendingActionEntity;
import com.slz.crm.server.ai.validation.AiActionValidator;
import com.slz.crm.server.service.PermissionService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * AI 待确认操作的校验守卫支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * PendingActionServiceImpl，行为等价）。纯静态、无状态，依赖经参数传入。
 */
final class PendingActionGuards {

  private PendingActionGuards() {}

  /** 按 actionType 校验当前用户是否有对应写权限 */
  static void requirePermission(
      String actionType, Long userId, PermissionService permissionService) {
    com.slz.crm.common.enumeration.PermissionOperates required =
        com.slz.crm.server.ai.enums.ActionTypeEnum.getRequiredPermission(actionType);
    if (required == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "不支持的操作类型: " + actionType);
    }
    if (!permissionService.hasPermission(userId, required)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权限执行此操作: " + actionType);
    }
  }

  /** 按 actionType 取校验器，未注册则抛参数错误 */
  static AiActionValidator requireValidator(
      String actionType, Map<String, AiActionValidator> validatorMap) {
    AiActionValidator validator = validatorMap.get(actionType);

    if (validator == null) {

      throw new BaseException(ErrorCode.PARAM_REQUIRED, "不支持的操作类型: " + actionType);
    }
    return validator;
  }

  /** 获取实体（不存在则抛异常）；lookup 为主类的 getByPendingId 查询 */
  static AiPendingActionEntity getOwnedEntity(
      String pendingId, Long userId, Function<String, AiPendingActionEntity> lookup) {
    AiPendingActionEntity entity = lookup.apply(pendingId);

    if (entity == null || !Objects.equals(entity.getUserId(), userId)) {

      throw new BaseException(ErrorCode.PARAM_REQUIRED, "操作不存在");
    }
    return entity;
  }
}
