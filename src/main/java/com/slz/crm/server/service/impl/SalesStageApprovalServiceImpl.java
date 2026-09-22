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
import com.slz.crm.pojo.vo.AddSalesStageApprovalDTO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.SalesStageApprovalService;
import java.time.LocalDateTime;
import java.util.List;
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
      result =
          SalesStageApprovalSupport.toVoWithAssists(
              entity,
              opportunity == null ? null : opportunity.getOpportunityName(),
              opportunity == null
                  ? null
                  : SalesStageApprovalSupport.convertStageToChinese(opportunity.getStage()),
              SalesStageApprovalSupport.convertStageToChinese(entity.getTargetStage()),
              assistRequestService);
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
    SalesStageApprovalSupport.requireUpdatableDraft(entity, dto, currentId);
    SalesOpportunityEntity opportunity =
        SalesStageApprovalSupport.requireDraftStageValid(entity, dto, salesOpportunityMapper);

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

    return SalesStageApprovalSupport.toVoWithAssists(
        entity,
        opportunity.getOpportunityName(),
        SalesStageApprovalSupport.convertStageToChinese(opportunity.getStage()),
        SalesStageApprovalSupport.convertStageToChinese(entity.getTargetStage()),
        assistRequestService);
  }

  @SuppressWarnings("PMD.OnlyOneReturn") // 草稿幂等路径：同一用户同一商机已有未提交草稿时直接转更新并提前返回，机械合并会显著加深嵌套
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
    if (!SalesStageApprovalSupport.isValidStageTransition(
        selected.getStage(), dto.getTargetStage())) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_MUST_BE_CLOSED,
          String.format(
              "无效的销售阶段流转：从阶段 %s (%s) 到阶段 %s (%s)",
              selected.getStage(),
              SalesStageApprovalSupport.convertStageToChinese(selected.getStage()),
              dto.getTargetStage(),
              SalesStageApprovalSupport.convertStageToChinese(dto.getTargetStage())));
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

    // 创建响应同样组装协助人，供前端创建后直接展示；审批人姓名在审批时还没有，先设为null
    return SalesStageApprovalSupport.toVoWithAssists(
        entity,
        selected.getOpportunityName(),
        SalesStageApprovalSupport.convertStageToChinese(selected.getStage()),
        SalesStageApprovalSupport.convertStageToChinese(dto.getTargetStage()),
        assistRequestService);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean submitDraft(Long id) {
    SalesStageApprovalEntity entity = baseMapper.selectById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.SALES_STAGE_APPROVAL_NOT_EXISTS);
    }
    Long currentId = BaseUnit.getCurrentId();
    SalesStageApprovalSupport.requireSubmittableDraft(entity, currentId);

    SalesOpportunityEntity opportunity =
        salesOpportunityMapper.selectByIdForUpdate(entity.getOpportunityId());
    if (opportunity == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS);
    }
    if (!SalesStageApprovalSupport.isValidStageTransition(
        opportunity.getStage(), entity.getTargetStage())) {
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
      SalesStageApprovalSupport.applyApprovalFilters(
          queryWrapper, dto, salesOpportunityMapper, userMapper);
    }

    // 普通审批列表只对申请人/审批人开放；协助人统一从“待我协助”页面进入
    SalesStageApprovalSupport.applyApprovalVisibilityScope(queryWrapper, currentId, userMapper);

    Page<SalesStageApprovalEntity> pageResult = baseMapper.selectPage(page, queryWrapper);

    List<SalesStageApprovalVO> voList =
        pageResult.getRecords().stream()
            .map(e -> SalesStageApprovalSupport.toApprovalVO(e, salesOpportunityMapper, userMapper))
            .collect(Collectors.toList());

    // 批量组装协助人（按可见性过滤）
    SalesStageApprovalSupport.fillAssistUsers(voList, currentId, assistRequestService, userMapper);

    Page<SalesStageApprovalVO> resultPage = new Page<>();
    BeanUtils.copyProperties(pageResult, resultPage);
    resultPage.setRecords(voList);

    return resultPage;
  }

  private boolean isApprovalTriggered(SalesStageApprovalEntity entity) {
    // 旧数据没有该列时按已提交处理，只有明确 false 才是草稿。
    return !Boolean.FALSE.equals(entity.getApprovalTriggered());
  }
}
