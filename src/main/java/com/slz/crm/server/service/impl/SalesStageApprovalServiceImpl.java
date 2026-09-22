package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AddSalesStageApprovalDTO;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.SalesStageApprovalService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesStageApprovalServiceImpl
    extends ServiceImpl<SalesStageApprovalMapper, SalesStageApprovalEntity>
    implements SalesStageApprovalService {

  @Autowired private UserMapper userMapper;

  @Autowired private ApprovalAttachmentMapper approvalAttachmentMapper;

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;

  @Autowired private AssistRequestService assistRequestService;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SalesStageApprovalVO approveStage(AddSalesStageApprovalDTO dto) {

    return createStage(dto, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SalesStageApprovalVO saveDraft(AddSalesStageApprovalDTO dto) {
    return createStage(dto, false);
  }

  @Override
  public SalesStageApprovalVO getDraftByOpportunityId(Long opportunityId) {
    if (opportunityId == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "销售机会不能为空");
    }
    Long currentId = BaseUnit.getCurrentId();
    SalesStageApprovalEntity entity =
        baseMapper.selectOne(
            new LambdaQueryWrapper<SalesStageApprovalEntity>()
                .eq(SalesStageApprovalEntity::getOpportunityId, opportunityId)
                .eq(SalesStageApprovalEntity::getApplicantId, currentId)
                .eq(SalesStageApprovalEntity::getApprovalTriggered, false)
                .orderByDesc(SalesStageApprovalEntity::getApplyTime)
                .last("LIMIT 1"));
    SalesStageApprovalVO result = null;
    if (entity != null) {
      SalesOpportunityEntity opportunity =
          salesOpportunityMapper.selectById(entity.getOpportunityId());
      SalesStageApprovalVO vo =
          SalesStageApprovalVO.fromEntity(
              entity,
              opportunity == null ? null : opportunity.getOpportunityName(),
              opportunity == null ? null : convertStageToChinese(opportunity.getStage()),
              convertStageToChinese(entity.getTargetStage()),
              null);
      vo.setAssistUsers(
          assistRequestService.listAssistsByRecord(ModelName.SALES_STAGE_APPROVAL, entity.getId()));
      result = vo;
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SalesStageApprovalVO updateDraft(Long id, AddSalesStageApprovalDTO dto) {
    SalesStageApprovalEntity entity = baseMapper.selectById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.SALES_STAGE_APPROVAL_NOT_EXISTS);
    }
    Long currentId = BaseUnit.getCurrentId();
    requireUpdatableDraft(entity, dto, currentId);
    SalesOpportunityEntity opportunity = requireDraftStageValid(entity, dto);

    entity.setCurrentStage(opportunity.getStage());
    entity.setTargetStage(dto.getTargetStage());
    entity.setApproverId(dto.getApproverId());
    entity.setMessage(dto.getMessage().trim());
    // @Version 乐观锁：updateById 会携带 version 条件并自增；
    // 多端并发修改时后写入者影响行数为 0，避免静默覆盖对方内容。
    if (baseMapper.updateById(entity) <= 0) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "草稿已被其他会话修改，请刷新后重试");
    }
    assistRequestService.updatePendingAssists(
        ModelName.SALES_STAGE_APPROVAL, entity.getId(), currentId, dto.getAssistApplyList());

    SalesStageApprovalVO vo =
        SalesStageApprovalVO.fromEntity(
            entity,
            opportunity.getOpportunityName(),
            convertStageToChinese(opportunity.getStage()),
            convertStageToChinese(entity.getTargetStage()),
            null);
    vo.setAssistUsers(
        assistRequestService.listAssistsByRecord(ModelName.SALES_STAGE_APPROVAL, entity.getId()));
    return vo;
  }

  /**
   * 校验草稿可修改：申请人本人、未提交审批、关联商机未变、备注非空（拆自 updateDraft，行为等价；存在性校验留在主方法）。
   *
   * @param entity 审批实体
   * @param dto 草稿更新请求
   * @param currentId 当前用户 ID
   */
  private void requireUpdatableDraft(
      SalesStageApprovalEntity entity, AddSalesStageApprovalDTO dto, Long currentId) {
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
   * @return 锁定查询到的商机实体
   */
  private SalesOpportunityEntity requireDraftStageValid(
      SalesStageApprovalEntity entity, AddSalesStageApprovalDTO dto) {
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

  private SalesStageApprovalVO createStage(
      AddSalesStageApprovalDTO dto, boolean approvalTriggered) {

    // 草稿幂等保存：同一用户同一商机已存在未提交草稿时更新它，避免重复保存产生多条草稿
    if (!approvalTriggered) {
      Long currentId = BaseUnit.getCurrentId();
      SalesStageApprovalEntity existingDraft =
          baseMapper.selectOne(
              new LambdaQueryWrapper<SalesStageApprovalEntity>()
                  .eq(SalesStageApprovalEntity::getOpportunityId, dto.getOpportunityId())
                  .eq(SalesStageApprovalEntity::getApplicantId, currentId)
                  .eq(SalesStageApprovalEntity::getApprovalTriggered, false)
                  .last("LIMIT 1"));
      if (existingDraft != null) {
        return updateDraft(existingDraft.getId(), dto);
      }
    }

    // 锁住同一商机，避免两个请求同时通过“无待审批”的检查后重复创建审批。
    SalesOpportunityEntity selected =
        salesOpportunityMapper.selectByIdForUpdate(dto.getOpportunityId());
    if (selected == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS);
    }

    // 状态机验证：销售阶段流转合法性验证
    if (!isValidStageTransition(selected.getStage(), dto.getTargetStage())) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_MUST_BE_CLOSED,
          String.format(
              "无效的销售阶段流转：从阶段 %s (%s) 到阶段 %s (%s)",
              selected.getStage(),
              convertStageToChinese(selected.getStage()),
              dto.getTargetStage(),
              convertStageToChinese(dto.getTargetStage())));
    }

    // 审批备注不能为空
    if (dto.getMessage() == null || dto.getMessage().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "审批备注不能为空");
    }

    // 同一商机同一时刻只能有一条待审批推进；被拒绝/退回后可重新申请，
    // 通过后阶段已变化，下一次只能申请新的下一阶段。
    if (approvalTriggered) {
      Long pendingCount =
          count(
              new LambdaQueryWrapper<SalesStageApprovalEntity>()
                  .eq(SalesStageApprovalEntity::getOpportunityId, dto.getOpportunityId())
                  .eq(SalesStageApprovalEntity::getApprovalStatus, 0)
                  .and(
                      t ->
                          t.ne(SalesStageApprovalEntity::getApprovalTriggered, false)
                              .or()
                              .isNull(SalesStageApprovalEntity::getApprovalTriggered)));
      if (pendingCount != null && pendingCount > 0) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该订单已有待审批的阶段推进申请，请等待审批完成后再操作");
      }
    }

    Long currentId = BaseUnit.getCurrentId();
    // 插入审批记录
    SalesStageApprovalEntity entity = new SalesStageApprovalEntity();
    BeanUtils.copyProperties(dto, entity);
    entity.setApplicantId(currentId);

    entity.setApplicantId(currentId);
    entity.setTargetStage(dto.getTargetStage());
    entity.setCurrentStage(selected.getStage());
    entity.setApprovalStatus(0);
    entity.setApprovalTriggered(approvalTriggered);
    entity.setApplyTime(LocalDateTime.now());

    save(entity);

    // 保存协助人记录（可空，空则跳过）
    assistRequestService.createAssists(
        ModelName.SALES_STAGE_APPROVAL, entity.getId(), currentId, dto.getAssistApplyList());

    String currentStage = convertStageToChinese(selected.getStage());
    String targetStage = convertStageToChinese(dto.getTargetStage());
    // 审批人姓名在审批时还没有，先设为null
    SalesStageApprovalVO vo =
        SalesStageApprovalVO.fromEntity(
            entity, selected.getOpportunityName(), currentStage, targetStage, null);
    // 创建响应同样组装协助人，供前端创建后直接展示
    vo.setAssistUsers(
        assistRequestService.listAssistsByRecord(ModelName.SALES_STAGE_APPROVAL, entity.getId()));
    return vo;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean submitDraft(Long id) {
    SalesStageApprovalEntity entity = baseMapper.selectById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.SALES_STAGE_APPROVAL_NOT_EXISTS);
    }
    Long currentId = BaseUnit.getCurrentId();
    requireSubmittableDraft(entity, currentId);

    SalesOpportunityEntity opportunity =
        salesOpportunityMapper.selectByIdForUpdate(entity.getOpportunityId());
    if (opportunity == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS);
    }
    if (!isValidStageTransition(opportunity.getStage(), entity.getTargetStage())) {
      throw new BaseException(ErrorCode.OPPORTUNITY_MUST_BE_CLOSED, "当前销售阶段已变化，无法提交该草稿");
    }
    requireNoPendingConflicts(entity.getOpportunityId(), entity.getId());

    entity.setCurrentStage(opportunity.getStage());
    entity.setApprovalStatus(0);
    entity.setApprovalTriggered(true);
    entity.setApplyTime(LocalDateTime.now());
    if (baseMapper.updateById(entity) <= 0) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "提交审批失败");
    }
    return true;
  }

  /**
   * 校验草稿可提交：申请人本人且未提交审批（拆自 submitDraft，行为等价）。
   *
   * @param entity 审批实体
   * @param currentId 当前用户 ID
   */
  private void requireSubmittableDraft(SalesStageApprovalEntity entity, Long currentId) {
    if (!Objects.equals(entity.getApplicantId(), currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (Boolean.TRUE.equals(entity.getApprovalTriggered())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该申请已提交审批");
    }
  }

  /**
   * 校验同一商机没有其他待审批的阶段推进申请（拆自 submitDraft，行为等价）。
   *
   * @param opportunityId 商机 ID
   * @param excludeId 当前草稿 ID（排除自身）
   */
  private void requireNoPendingConflicts(Long opportunityId, Long excludeId) {
    Long pendingCount =
        count(
            new LambdaQueryWrapper<SalesStageApprovalEntity>()
                .eq(SalesStageApprovalEntity::getOpportunityId, opportunityId)
                .eq(SalesStageApprovalEntity::getApprovalStatus, 0)
                .ne(SalesStageApprovalEntity::getId, excludeId)
                .and(
                    t ->
                        t.ne(SalesStageApprovalEntity::getApprovalTriggered, false)
                            .or()
                            .isNull(SalesStageApprovalEntity::getApprovalTriggered)));
    if (pendingCount != null && pendingCount > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该订单已有待审批的阶段推进申请，请等待审批完成后再操作");
    }
  }

  /** 组装协助申请明细：优先明细列表，兼容旧 assistUserIds（无目的/要求） */
  private java.util.List<com.slz.crm.pojo.dto.AssistApplyItem> resolveAssistApplyList(
      java.util.List<com.slz.crm.pojo.dto.AssistApplyItem> applyList,
      java.util.List<Long> assistUserIds) {
    if (applyList != null && !applyList.isEmpty()) {
      return applyList;
    }
    if (assistUserIds == null || assistUserIds.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    return assistUserIds.stream()
        .map(
            id -> {
              com.slz.crm.pojo.dto.AssistApplyItem item =
                  new com.slz.crm.pojo.dto.AssistApplyItem();
              item.setAssistUserId(id);
              return item;
            })
        .collect(java.util.stream.Collectors.toList());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean updateById(SalesStageApprovalDTO dto) {
    SalesStageApprovalEntity oldEntity = baseMapper.selectById(dto.getId());
    if (oldEntity == null) {
      throw new BaseException(ErrorCode.SALES_STAGE_APPROVAL_NOT_EXISTS);
    }
    if (!Objects.equals(oldEntity.getApproverId(), BaseUnit.getCurrentId())) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (!Objects.equals(oldEntity.getApprovalStatus(), 0) || !isApprovalTriggered(oldEntity)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "只有待审批记录可以处理");
    }
    SalesStageApprovalEntity entity = new SalesStageApprovalEntity();
    BeanUtils.copyProperties(dto, entity);
    entity.setApprovalTime(LocalDateTime.now());
    entity.setApproverId(BaseUnit.getCurrentId());

    entity.setApprovalStatus(dto.getApprovalStatus().getFirst());
    applyApprovedStageChange(entity);

    if (baseMapper.updateById(entity) <= 0) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "审批状态更新失败");
    }
    // 同意、拒绝或退回修改均意味着本轮审批结束，不能留下可继续处理的协助待办。
    assistRequestService.cancelPendingByRecord(
        ModelName.SALES_STAGE_APPROVAL, oldEntity.getId(), "所属销售阶段审批已结束，待协助申请自动取消");
    return true;
  }

  /**
   * 审批同意时推进销售机会阶段（拆自 updateById，行为等价）。
   *
   * @param entity 审批更新实体
   */
  private void applyApprovedStageChange(SalesStageApprovalEntity entity) {
    if (entity.getApprovalStatus() != 1) {
      return;
    }
    SalesStageApprovalEntity salesStageApprovalEntity = baseMapper.selectById(entity.getId());
    if (salesStageApprovalEntity == null) {
      throw new BaseException(ErrorCode.SALES_STAGE_APPROVAL_NOT_EXISTS);
    }
    // 如果是同意，更新销售机会的阶段
    SalesOpportunityEntity opportunityEntity =
        salesOpportunityMapper.selectById(salesStageApprovalEntity.getOpportunityId());

    if (opportunityEntity == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS);
    }

    opportunityEntity.setStage(salesStageApprovalEntity.getTargetStage());
    salesOpportunityMapper.updateById(opportunityEntity);
  }

  @Override
  public void removeByIds(List<Long> ids) {
    assistRequestService.deleteByRecords(ModelName.SALES_STAGE_APPROVAL, ids);
    baseMapper.deleteBatchIds(ids);
  }

  @Override
  @Privacy
  public Page<SalesStageApprovalVO> getPage(
      Page<SalesStageApprovalEntity> page, SalesStageApprovalDTO dto) {

    LambdaQueryWrapper<SalesStageApprovalEntity> queryWrapper =
        Wrappers.lambdaQuery(SalesStageApprovalEntity.class);
    Long currentId = BaseUnit.getCurrentId();

    // 通过审批人、关联销售机会名称获得相应ids
    if (dto != null) {
      applyApprovalFilters(queryWrapper, dto);
    }

    // 普通审批列表只对申请人/审批人开放；协助人统一从“待我协助”页面进入
    applyApprovalVisibilityScope(queryWrapper, currentId);

    Page<SalesStageApprovalEntity> pageResult = baseMapper.selectPage(page, queryWrapper);

    List<SalesStageApprovalVO> voList =
        pageResult.getRecords().stream().map(this::toApprovalVO).collect(Collectors.toList());

    // 批量组装协助人（按可见性过滤）
    fillAssistUsers(voList, currentId);

    Page<SalesStageApprovalVO> resultPage = new Page<>();
    BeanUtils.copyProperties(pageResult, resultPage);
    resultPage.setRecords(voList);

    return resultPage;
  }

  /**
   * 组装审批分页查询条件（拆自 getPage，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param dto 查询条件
   */
  private void applyApprovalFilters(
      LambdaQueryWrapper<SalesStageApprovalEntity> queryWrapper, SalesStageApprovalDTO dto) {
    // 通过关联销售机会名称/审批人姓名获得相应ids
    applyApprovalNameIdFilters(queryWrapper, dto);

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
   */
  private void applyApprovalNameIdFilters(
      LambdaQueryWrapper<SalesStageApprovalEntity> queryWrapper, SalesStageApprovalDTO dto) {
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
   */
  private void applyApprovalVisibilityScope(
      LambdaQueryWrapper<SalesStageApprovalEntity> queryWrapper, Long currentId) {
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
   * @return 审批 VO
   */
  private SalesStageApprovalVO toApprovalVO(SalesStageApprovalEntity entity) {
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
   */
  private void fillAssistUsers(List<SalesStageApprovalVO> voList, Long currentId) {
    if (voList == null || voList.isEmpty()) {
      return;
    }
    List<Long> recordIds = voList.stream().map(SalesStageApprovalVO::getId).toList();
    List<AssistVO> allAssists =
        assistRequestService.listAssistsByRecords(ModelName.SALES_STAGE_APPROVAL, recordIds);
    if (allAssists.isEmpty()) {
      voList.forEach(vo -> vo.setAssistUsers(Collections.emptyList()));
      return;
    }
    Map<Long, List<AssistVO>> byRecord =
        allAssists.stream().collect(Collectors.groupingBy(AssistVO::getRecordId));
    boolean isAdmin = isAdmin(currentId);
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

  private boolean isAdmin(Long userId) {
    UserEntity user = userMapper.selectById(userId);
    return user != null && Objects.equals(user.getRoleId(), 1L);
  }

  private boolean isApprovalTriggered(SalesStageApprovalEntity entity) {
    // 旧数据没有该列时按已提交处理，只有明确 false 才是草稿。
    return !Boolean.FALSE.equals(entity.getApprovalTriggered());
  }

  /**
   * 将销售阶段数字转换为对应的中文名称
   *
   * @param stage 阶段数字
   * @return 中文名称
   */
  private String convertStageToChinese(Integer stage) {
    if (stage == null) {
      return null;
    }
    return switch (stage) {
      case 0 -> "种子";
      case 1 -> "潜在商机";
      case 2 -> "确认商机";
      case 3 -> "储备项目";
      case 4 -> "立项签约";
      case 5 -> "关闭";
      default -> "未知阶段";
    };
  }

  /**
   * 验证销售阶段流转的合法性（状态机验证）
   *
   * @param currentStage 当前阶段
   * @param targetStage 目标阶段
   * @return 是否允许流转
   */
  private boolean isValidStageTransition(int currentStage, Integer targetStage) {
    if (targetStage == null) {
      return false;
    }

    // 已关闭的阶段（5）不能再流转到其他阶段
    if (currentStage == 5) {
      return false;
    }

    // 只能按顺序流转：0 -> 1 -> 2 -> 3 -> 4 -> 5
    // 允许跳过中间阶段，但不能倒流
    // 例如：0 -> 2 允许，2 -> 1 不允许
    if (targetStage <= currentStage) {
      // 只能从当前阶段向后流转，不能倒流
      return targetStage == 5; // 唯一例外：可以直接关闭
    }

    // 目标阶段不能超过5
    if (targetStage > 5) {
      return false;
    }

    return true;
  }
}
