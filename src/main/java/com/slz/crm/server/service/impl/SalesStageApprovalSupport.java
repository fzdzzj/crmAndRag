package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistRequestService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 阶段审批的查询条件/VO 装配/阶段状态机支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * SalesStageApprovalServiceImpl，行为等价）。纯静态、无状态，mapper 与服务依赖经参数传入。
 */
final class SalesStageApprovalSupport {

  private SalesStageApprovalSupport() {}

  /**
   * 组装审批分页查询条件（拆自 getPage，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param dto 查询条件
   * @param salesOpportunityMapper 商机 mapper
   * @param userMapper 用户 mapper
   */
  static void applyApprovalFilters(
      LambdaQueryWrapper<SalesStageApprovalEntity> queryWrapper,
      SalesStageApprovalDTO dto,
      SalesOpportunityMapper salesOpportunityMapper,
      UserMapper userMapper) {
    // 通过关联销售机会名称/审批人姓名获得相应ids
    applyApprovalNameIdFilters(queryWrapper, dto, salesOpportunityMapper, userMapper);

    if (dto.getApproverId() != null) {
      queryWrapper.eq(SalesStageApprovalEntity::getApproverId, dto.getApproverId());
    }
    if (dto.getTargetStage() != null) {
      queryWrapper.like(SalesStageApprovalEntity::getTargetStage, dto.getTargetStage());
    }
    if (dto.getApprovalOpinion() != null) {
      queryWrapper.like(SalesStageApprovalEntity::getApprovalOpinion, dto.getApprovalOpinion());
    }
    if (dto.getApplyTime() != null) {
      queryWrapper.like(SalesStageApprovalEntity::getApplyTime, dto.getApplyTime());
    }
    if (dto.getApprovalStatus() != null && !dto.getApprovalStatus().isEmpty()) {
      queryWrapper.in(SalesStageApprovalEntity::getApprovalStatus, dto.getApprovalStatus());
    }
  }

  /**
   * 按关联销售机会名称/审批人姓名解析 ID 并加入过滤（拆自 getPage，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param dto 查询条件
   * @param salesOpportunityMapper 商机 mapper
   * @param userMapper 用户 mapper
   */
  private static void applyApprovalNameIdFilters(
      LambdaQueryWrapper<SalesStageApprovalEntity> queryWrapper,
      SalesStageApprovalDTO dto,
      SalesOpportunityMapper salesOpportunityMapper,
      UserMapper userMapper) {
    // 通过关联销售机会名称获得相应ids
    if (dto.getOpportunityName() != null && !dto.getOpportunityName().isEmpty()) {
      List<Long> opportunityIds =
          salesOpportunityMapper.selectObjs(
              Wrappers.lambdaQuery(SalesOpportunityEntity.class)
                  .like(SalesOpportunityEntity::getOpportunityName, dto.getOpportunityName()));

      if (!opportunityIds.isEmpty()) {
        queryWrapper.in(SalesStageApprovalEntity::getOpportunityId, opportunityIds);
      }
    }
    // 通过审批人姓名获得相应ids
    if (dto.getApproverName() != null && !dto.getApproverName().isEmpty()) {
      List<Long> approverIds =
          userMapper.selectObjs(
              Wrappers.lambdaQuery(UserEntity.class)
                  .like(UserEntity::getRealName, dto.getApproverName()));

      if (!approverIds.isEmpty()) {
        queryWrapper.in(SalesStageApprovalEntity::getApproverId, approverIds);
      }
    }
  }

  /**
   * 审批列表可见范围：管理员看全部已触发审批，其他人限申请人/审批人（拆自 getPage，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param currentId 当前用户 ID
   * @param userMapper 用户 mapper
   */
  static void applyApprovalVisibilityScope(
      LambdaQueryWrapper<SalesStageApprovalEntity> queryWrapper,
      Long currentId,
      UserMapper userMapper) {
    // 普通审批列表只对申请人/审批人开放；协助人统一从“待我协助”页面进入
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    if (isAdmin) {
      queryWrapper.and(
          t ->
              t.ne(SalesStageApprovalEntity::getApprovalTriggered, false)
                  .or()
                  .isNull(SalesStageApprovalEntity::getApprovalTriggered));
    } else {
      queryWrapper.and(
          w ->
              w.eq(SalesStageApprovalEntity::getApplicantId, currentId)
                  .or(
                      q ->
                          q.eq(SalesStageApprovalEntity::getApproverId, currentId)
                              .and(
                                  t ->
                                      t.ne(SalesStageApprovalEntity::getApprovalTriggered, false)
                                          .or()
                                          .isNull(
                                              SalesStageApprovalEntity::getApprovalTriggered))));
    }
  }

  /**
   * 审批实体转 VO：补齐商机名称/阶段中文/审批人姓名（拆自 getPage，行为等价）。
   *
   * @param entity 审批实体
   * @param salesOpportunityMapper 商机 mapper
   * @param userMapper 用户 mapper
   * @return 审批 VO
   */
  static SalesStageApprovalVO toApprovalVO(
      SalesStageApprovalEntity entity,
      SalesOpportunityMapper salesOpportunityMapper,
      UserMapper userMapper) {
    String opportunityName = null;
    String currentStage = null;
    String targetStage = null;
    String approverName = null;

    // 获取销售机会名称
    if (entity.getOpportunityId() != null) {
      SalesOpportunityEntity opportunity =
          salesOpportunityMapper.selectById(entity.getOpportunityId());
      if (opportunity != null) {
        opportunityName = opportunity.getOpportunityName();
        // 将数字阶段转换为对应的中文名称
        currentStage = convertStageToChinese(opportunity.getStage());
      }
    }

    // 将目标阶段数字转换为对应的中文名称
    targetStage = convertStageToChinese(entity.getTargetStage());

    // 获取审批人姓名
    if (entity.getApproverId() != null) {
      UserEntity approver = userMapper.selectById(entity.getApproverId());
      if (approver != null) {
        approverName = approver.getRealName();
      }
    }

    return SalesStageApprovalVO.fromEntity(
        entity, opportunityName, currentStage, targetStage, approverName);
  }

  /**
   * 批量组装协助人列表（申请人可见全部；协助人仅可见指派给自己的；其他人不展示）
   *
   * @param voList 审批VO列表
   * @param currentId 当前用户ID
   * @param assistRequestService 协助服务
   * @param userMapper 用户 mapper
   */
  static void fillAssistUsers(
      List<SalesStageApprovalVO> voList,
      Long currentId,
      AssistRequestService assistRequestService,
      UserMapper userMapper) {
    if (voList != null && !voList.isEmpty()) {
      doFillAssistUsers(voList, currentId, assistRequestService, userMapper);
    }
  }

  private static void doFillAssistUsers(
      List<SalesStageApprovalVO> voList,
      Long currentId,
      AssistRequestService assistRequestService,
      UserMapper userMapper) {
    List<Long> recordIds = voList.stream().map(SalesStageApprovalVO::getId).toList();
    List<AssistVO> allAssists =
        assistRequestService.listAssistsByRecords(ModelName.SALES_STAGE_APPROVAL, recordIds);
    if (allAssists.isEmpty()) {
      voList.forEach(vo -> vo.setAssistUsers(Collections.emptyList()));
    } else {
      Map<Long, List<AssistVO>> byRecord =
          allAssists.stream().collect(Collectors.groupingBy(AssistVO::getRecordId));
      boolean isAdmin = isAdminUser(currentId, userMapper);
      for (SalesStageApprovalVO vo : voList) {
        List<AssistVO> list = byRecord.getOrDefault(vo.getId(), Collections.emptyList());
        if (list.isEmpty()) {
          vo.setAssistUsers(Collections.emptyList());
          continue;
        }
        // 超管/审批人/申请人可见全部；协助人仅可见指派给自己的
        if (isAdmin
            || Objects.equals(vo.getApproverId(), currentId)
            || list.stream().anyMatch(a -> Objects.equals(a.getApplicantId(), currentId))) {
          vo.setAssistUsers(list);
        } else {
          vo.setAssistUsers(
              list.stream().filter(a -> Objects.equals(a.getAssistUserId(), currentId)).toList());
        }
      }
    }
  }

  private static boolean isAdminUser(Long userId, UserMapper userMapper) {
    UserEntity user = userMapper.selectById(userId);
    return user != null && Objects.equals(user.getRoleId(), 1L);
  }

  /**
   * 将销售阶段数字转换为对应的中文名称
   *
   * @param stage 阶段数字
   * @return 中文名称
   */
  static String convertStageToChinese(Integer stage) {
    String name;
    if (stage == null) {
      name = null;
    } else {
      name =
          switch (stage) {
            case 0 -> "种子";
            case 1 -> "潜在商机";
            case 2 -> "确认商机";
            case 3 -> "储备项目";
            case 4 -> "立项签约";
            case 5 -> "关闭";
            default -> "未知阶段";
          };
    }
    return name;
  }

  /**
   * 验证销售阶段流转的合法性（状态机验证）
   *
   * @param currentStage 当前阶段
   * @param targetStage 目标阶段
   * @return 是否允许流转
   */
  static boolean isValidStageTransition(int currentStage, Integer targetStage) {
    boolean allowed;
    if (targetStage == null) {
      allowed = false;
    } else if (currentStage == 5) {
      // 已关闭的阶段（5）不能再流转到其他阶段
      allowed = false;
    } else if (targetStage <= currentStage) {
      // 只能按顺序流转：0 -> 1 -> 2 -> 3 -> 4 -> 5，允许跳过中间阶段但不能倒流
      // 例如：0 -> 2 允许，2 -> 1 不允许；唯一例外：可以直接关闭
      allowed = targetStage == 5;
    } else {
      // 目标阶段不能超过5
      allowed = targetStage <= 5;
    }
    return allowed;
  }

  /**
   * 校验草稿可修改：申请人本人、未提交审批、关联商机未变、备注非空（拆自 updateDraft，行为等价）。
   *
   * @param entity 审批实体
   * @param dto 草稿更新请求
   * @param currentId 当前用户 ID
   */
  static void requireUpdatableDraft(
      SalesStageApprovalEntity entity,
      com.slz.crm.pojo.vo.AddSalesStageApprovalDTO dto,
      Long currentId) {
    if (!Objects.equals(entity.getApplicantId(), currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (!Boolean.FALSE.equals(entity.getApprovalTriggered())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "只有未提交审批的草稿可以修改");
    }
    if (!Objects.equals(entity.getOpportunityId(), dto.getOpportunityId())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "草稿关联的销售机会不能修改");
    }
    if (dto.getMessage() == null || dto.getMessage().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "审批备注不能为空");
    }
  }

  /**
   * 校验草稿关联商机与阶段流转仍有效（拆自 updateDraft，行为等价）。
   *
   * @param entity 审批实体
   * @param dto 草稿更新请求
   * @param salesOpportunityMapper 商机 mapper
   * @return 锁定查询到的商机实体
   */
  static SalesOpportunityEntity requireDraftStageValid(
      SalesStageApprovalEntity entity,
      com.slz.crm.pojo.vo.AddSalesStageApprovalDTO dto,
      SalesOpportunityMapper salesOpportunityMapper) {
    SalesOpportunityEntity opportunity =
        salesOpportunityMapper.selectByIdForUpdate(entity.getOpportunityId());
    if (opportunity == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS);
    }
    if (!isValidStageTransition(opportunity.getStage(), dto.getTargetStage())) {
      throw new BaseException(ErrorCode.OPPORTUNITY_MUST_BE_CLOSED, "当前销售阶段已变化，无法保存草稿");
    }
    return opportunity;
  }

  /**
   * 校验草稿可提交：申请人本人且未提交审批（拆自 submitDraft，行为等价）。
   *
   * @param entity 审批实体
   * @param currentId 当前用户 ID
   */
  static void requireSubmittableDraft(SalesStageApprovalEntity entity, Long currentId) {
    if (!Objects.equals(entity.getApplicantId(), currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (Boolean.TRUE.equals(entity.getApprovalTriggered())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该申请已提交审批");
    }
  }

  /** 实体 → VO 并组装协助人（创建/更新/草稿查询三处共用，行为等价）。 */
  static SalesStageApprovalVO toVoWithAssists(
      SalesStageApprovalEntity entity,
      String opportunityName,
      String currentStage,
      String targetStage,
      AssistRequestService assistRequestService) {
    SalesStageApprovalVO vo =
        SalesStageApprovalVO.fromEntity(entity, opportunityName, currentStage, targetStage, null);
    vo.setAssistUsers(
        assistRequestService.listAssistsByRecord(ModelName.SALES_STAGE_APPROVAL, entity.getId()));
    return vo;
  }
}
