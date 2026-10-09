package com.slz.crm.platform.config.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.platform.config.ConfigItemView;
import com.slz.crm.platform.config.ConfigKeyTierPolicy;
import com.slz.crm.platform.config.ConfigOperator;
import com.slz.crm.platform.config.ConfigValueType;
import com.slz.crm.platform.config.CostKeyChangeRequestStatus;
import com.slz.crm.platform.config.CostKeyChangeRequestVO;
import com.slz.crm.platform.config.CurrentUserResolver;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.config.controller.CostKeyChangeRequestRejectReq;
import com.slz.crm.platform.config.controller.CostKeyChangeRequestSubmitReq;
import com.slz.crm.platform.config.entity.CostKeyChangeRequestEntity;
import com.slz.crm.platform.config.mapper.CostKeyChangeRequestMapper;
import com.slz.crm.platform.contract.PlatformErrorCode;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成本键申请-审批流服务实现（add-cost-key-approval-workflow 任务 2.1）。
 *
 * <p>遵循 spec-delta 契约：
 *
 * <ul>
 *   <li>契约 1：COST 白名单封闭（ConfigKeyTierPolicy.COST_KEYS 22 键，零新白名单常量）；
 *   <li>契约 2：状态机四态封闭（PENDING -> APPROVED / REJECTED / WITHDRAWN，终态不可变）；
 *   <li>契约 3：审批写入原子性（以审批人身份调用既有写路径，写入失败申请单留 PENDING，回填 applied_config_version）；
 *   <li>契约 4：一键一单在途（同键存在 PENDING 申请单时拒绝重复提交）；
 *   <li>契约 5：权限语义（审批/驳回服务层超管闸 96005，普通查询仅看本人，超管看全部）。
 * </ul>
 */
@Service
public class CostKeyChangeRequestServiceImpl implements CostKeyChangeRequestService {

  private final CostKeyChangeRequestMapper requestMapper;
  private final DynamicConfigAdminService adminService;
  private final DynamicConfigKeyRegistry registry;
  private final CurrentUserResolver userResolver;

  public CostKeyChangeRequestServiceImpl(
      CostKeyChangeRequestMapper requestMapper,
      DynamicConfigAdminService adminService,
      DynamicConfigKeyRegistry registry,
      CurrentUserResolver userResolver) {
    this.requestMapper = requestMapper;
    this.adminService = adminService;
    this.registry = registry;
    this.userResolver = userResolver;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public CostKeyChangeRequestVO submit(CostKeyChangeRequestSubmitReq req) {
    ConfigOperator op = requireOperator();

    if (req == null || req.getConfigKey() == null || req.getConfigKey().isBlank()) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "配置键不能为空");
    }
    String key = req.getConfigKey().trim();

    // 契约 1：COST 白名单封闭
    if (!ConfigKeyTierPolicy.costKeys().contains(key)) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "非成本档配置键，无法发起审批申请：" + key);
    }

    if (registry.definitionOf(key).isEmpty()) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "未知配置键：" + key);
    }

    // 值预校验（复用写路径 validate 语义但不写入）
    ConfigValueType.Parsed parsed = registry.validate(key, req.getRequestedValue());
    if (!parsed.valid()) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), parsed.errorMessage());
    }

    // 契约 4：一键一单在途
    Long pendingCount =
        requestMapper.selectCount(
            new LambdaQueryWrapper<CostKeyChangeRequestEntity>()
                .eq(CostKeyChangeRequestEntity::getConfigKey, key)
                .eq(
                    CostKeyChangeRequestEntity::getStatus,
                    CostKeyChangeRequestStatus.PENDING.name()));
    if (pendingCount != null && pendingCount > 0) {
      throw new ServiceException(
          PlatformErrorCode.VALIDATION.getCode(), "该配置键已存在在途申请单，无法重复提交：" + key);
    }

    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setConfigKey(key);
    entity.setRequestedValue(parsed.canonical());
    entity.setReason(req.getReason());
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(op.userId());
    entity.setCreatedAt(LocalDateTime.now());

    requestMapper.insert(entity);
    return toVO(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public CostKeyChangeRequestVO withdraw(Long id) {
    ConfigOperator op = requireOperator();
    CostKeyChangeRequestEntity entity = requireEntity(id);

    // 仅本人可撤回
    if (!op.userId().equals(entity.getRequesterId())) {
      throw new ServiceException(PlatformErrorCode.FORBIDDEN.getCode(), "无权撤回他人的申请单");
    }

    // 仅 PENDING 态可撤回
    if (!CostKeyChangeRequestStatus.PENDING.name().equals(entity.getStatus())) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "非待审批状态不可撤回");
    }

    entity.setStatus(CostKeyChangeRequestStatus.WITHDRAWN.name());
    entity.setDecidedAt(LocalDateTime.now());
    requestMapper.updateById(entity);

    return toVO(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public CostKeyChangeRequestVO approve(Long id) {
    ConfigOperator op = requireSuperAdmin();
    CostKeyChangeRequestEntity entity = requireEntity(id);

    if (!CostKeyChangeRequestStatus.PENDING.name().equals(entity.getStatus())) {
      throw new ServiceException(
          PlatformErrorCode.VALIDATION.getCode(), "非待审批状态不可审批：" + entity.getStatus());
    }

    // 契约 3：以审批人身份调用既有写路径（写入失败申请单留 PENDING，异常向上抛出）
    String remark =
        "成本键申请单 #"
            + entity.getId()
            + " 审批通过："
            + (entity.getReason() != null ? entity.getReason() : "");
    ConfigItemView view =
        adminService.updateValue(entity.getConfigKey(), entity.getRequestedValue(), remark);

    entity.setStatus(CostKeyChangeRequestStatus.APPROVED.name());
    entity.setApproverId(op.userId());
    entity.setDecidedAt(LocalDateTime.now());
    if (view != null && view.version() != null) {
      entity.setAppliedConfigVersion(view.version().longValue());
    }
    requestMapper.updateById(entity);

    return toVO(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public CostKeyChangeRequestVO reject(Long id, CostKeyChangeRequestRejectReq req) {
    ConfigOperator op = requireSuperAdmin();
    CostKeyChangeRequestEntity entity = requireEntity(id);

    if (!CostKeyChangeRequestStatus.PENDING.name().equals(entity.getStatus())) {
      throw new ServiceException(
          PlatformErrorCode.VALIDATION.getCode(), "非待审批状态不可驳回：" + entity.getStatus());
    }

    entity.setStatus(CostKeyChangeRequestStatus.REJECTED.name());
    entity.setApproverId(op.userId());
    entity.setDecidedAt(LocalDateTime.now());
    entity.setRejectReason(req != null ? req.getRejectReason() : null);
    requestMapper.updateById(entity);

    return toVO(entity);
  }

  @Override
  public Page<CostKeyChangeRequestVO> list(
      Integer pageNum, Integer pageSize, String status, String configKey) {
    ConfigOperator op = requireOperator();

    Page<CostKeyChangeRequestEntity> pageParam =
        new Page<>(
            pageNum != null && pageNum > 0 ? pageNum : 1,
            pageSize != null && pageSize > 0 ? pageSize : 10);

    LambdaQueryWrapper<CostKeyChangeRequestEntity> wrapper = new LambdaQueryWrapper<>();
    // 超管看全部、普通 608 只看自己（当前请求实时角色判定）
    if (!op.isSuperAdmin()) {
      wrapper.eq(CostKeyChangeRequestEntity::getRequesterId, op.userId());
    }
    if (status != null && !status.isBlank()) {
      wrapper.eq(CostKeyChangeRequestEntity::getStatus, status.trim());
    }
    if (configKey != null && !configKey.isBlank()) {
      wrapper.eq(CostKeyChangeRequestEntity::getConfigKey, configKey.trim());
    }
    wrapper.orderByDesc(CostKeyChangeRequestEntity::getId);

    Page<CostKeyChangeRequestEntity> entityPage = requestMapper.selectPage(pageParam, wrapper);
    Page<CostKeyChangeRequestVO> voPage =
        new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
    voPage.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
    return voPage;
  }

  private ConfigOperator requireOperator() {
    ConfigOperator op = userResolver.resolve();
    if (op == null) {
      throw new ServiceException(
          PlatformErrorCode.UNAUTHORIZED.getCode(), PlatformErrorCode.UNAUTHORIZED.getMessage());
    }
    return op;
  }

  private ConfigOperator requireSuperAdmin() {
    ConfigOperator op = requireOperator();
    if (!op.isSuperAdmin()) {
      throw new ServiceException(PlatformErrorCode.FORBIDDEN.getCode(), "仅超级管理员可执行审批/驳回操作");
    }
    return op;
  }

  private CostKeyChangeRequestEntity requireEntity(Long id) {
    if (id == null) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "申请单 ID 不能为空");
    }
    CostKeyChangeRequestEntity entity = requestMapper.selectById(id);
    if (entity == null) {
      throw new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "未找到申请单：" + id);
    }
    return entity;
  }

  private CostKeyChangeRequestVO toVO(CostKeyChangeRequestEntity entity) {
    CostKeyChangeRequestVO vo = null;
    if (entity != null) {
      vo =
          new CostKeyChangeRequestVO(
              entity.getId(),
              entity.getConfigKey(),
              entity.getRequestedValue(),
              entity.getReason(),
              entity.getStatus(),
              entity.getRequesterId(),
              entity.getApproverId(),
              entity.getRejectReason(),
              entity.getCreatedAt(),
              entity.getDecidedAt(),
              entity.getAppliedConfigVersion());
    }
    return vo;
  }
}
