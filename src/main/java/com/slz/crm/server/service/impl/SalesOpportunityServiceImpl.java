package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.knowledge.ingestion.SalesOpportunityIngestionService;
import com.slz.crm.pojo.dto.SalesOpportunityDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.entity.*;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.AssistScopeService;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.SalesOpportunityService;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesOpportunityServiceImpl
    extends ServiceImpl<SalesOpportunityMapper, SalesOpportunityEntity>
    implements SalesOpportunityService {

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;
  @Autowired private CustomerContactMapper customerContactMapper;
  @Autowired private CustomerCompanyMapper customerCompanyMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private ForeignKeyDeleteUtil foreignKeyDeleteUtil;

  /** 物理级联删前的子宿主附件清理（fix-backlog-batch3 任务 4.2）。 */
  @Autowired private ChildHostAttachmentCleaner childHostAttachmentCleaner;

  @Autowired private com.slz.crm.server.mapper.ContractMapper contractMapper;
  @Autowired private com.slz.crm.server.mapper.BusinessActivityMapper businessActivityMapper;
  @Autowired private com.slz.crm.server.mapper.SalesStageApprovalMapper salesStageApprovalMapper;
  @Autowired private DataConvertService dataConvertService;
  @Autowired private AssistRequestService assistRequestService;

  /** 审批附件服务：商机删除前显式清理其阶段审批附件（canonical 删除=DB 物理删+磁盘清理）。 */
  @Autowired private ApprovalAttachmentService approvalAttachmentService;

  @Autowired private BusinessRecordAccessService businessRecordAccessService;
  @Autowired private AssistScopeService assistScopeService;

  /** 销售商机语义向量摄取（add-sales-opportunity-semantic-retrieval 任务 2.2）。 */
  @Autowired private SalesOpportunityIngestionService salesOpportunityIngestionService;

  /** 商机语义向量异步任务执行器（add-sales-opportunity-semantic-retrieval 任务 2.2；异步非阻塞核心依赖）。 */
  @Autowired
  @Qualifier("embeddingTaskExecutor")
  private Executor embeddingTaskExecutor;

  /** 详情装配协作对象（经工厂构造，复用主类依赖，保持 @InjectMocks 测试行为不变） */
  private SalesOpportunityDetailAssembler detailAssembler() {
    return new SalesOpportunityDetailAssembler(
        dataConvertService, salesStageApprovalMapper, assistRequestService, userMapper);
  }

  /**
   * 语义向量同步闸（任务 2.2，委托 {@link SalesOpportunitySemanticHookSupport}）：事务激活时注册 afterCommit 并异步派发至
   * embeddingTaskExecutor，无事务环境同样异步派发；semantic 侧未装配（评测/单测注入 null）时跳过，绝不阻塞业务主流程。
   *
   * @param scene 场景描述（日志定位用）
   * @param action 向量同步动作
   */
  private void runSemanticSync(String scene, Runnable action) {
    if (salesOpportunityIngestionService != null && embeddingTaskExecutor != null) {
      SalesOpportunitySemanticHookSupport.runAfterCommitAsync(scene, embeddingTaskExecutor, action);
    }
  }

  /** 回查完整实体后 upsert 语义向量（任务 2.2）；仅在 runSemanticSync 的 action 内调用。 */
  private void reingestSemanticById(Long id) {
    SalesOpportunitySemanticHookSupport.reingestById(
        id, salesOpportunityIngestionService, this::getById);
  }

  /**
   * 阶段跃迁阻断校验（harden-oppo-stage-approval，任务 1.2）。
   *
   * <p>需审批判定复用审批域既有流转合法性口径 {@link SalesStageApprovalSupport#isValidStageTransition}
   * （SalesStageApprovalServiceImpl 创建链 L148 / submitDraft L226 同源），不新造规则。目标阶段按该口径需
   * 审批（合法正向流转）且相对库内现值发生跃迁时，查询已通过审批记录 （{@code
   * SalesStageApprovalMapper#selectApprovedApprovalsByOpportunityId}，approval_status=1）作目标阶段
   * 已通过审批校验，缺失即抛业务异常，杜绝前端绕过审批流直接修改商机阶段。
   *
   * @param dto 更新请求（含目标阶段）
   */
  private void requireApprovedStageTransition(SalesOpportunityDTO dto) {
    SalesOpportunityEntity current = salesOpportunityMapper.selectById(dto.getId());
    int targetStage = dto.getStage();
    // 需审批才强校验：库内现值存在、stage 发生跃迁、且按审批域既有口径（isValidStageTransition）
    // 属合法正向流转（= 审批域定义的「需审批」）。商机不存在/阶段无值/非跃迁/非需审批流转
    // 均交既有 update 行为兜底，本卡只补审批绕过阻断，不新增流转规则。
    boolean requiresApprovedRecord =
        current != null
            && current.getStage() != null
            && targetStage != current.getStage()
            && SalesStageApprovalSupport.isValidStageTransition(current.getStage(), targetStage);
    if (requiresApprovedRecord) {
      List<SalesStageApprovalEntity> approved =
          salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(dto.getId());
      boolean covered =
          approved.stream().anyMatch(a -> Objects.equals(a.getTargetStage(), targetStage));
      if (!covered) {
        throw new BaseException(
            ErrorCode.OPPORTUNITY_MUST_BE_CLOSED,
            String.format(
                "销售阶段推进需先完成审批：当前阶段到阶段 %s (%s) 的推进尚无已通过的审批记录，请经由审批流程操作",
                targetStage, SalesStageApprovalSupport.convertStageToChinese(targetStage)));
      }
    }
  }

  @Override
  @CacheEvict(value = "opportunityName", key = "#salesOpportunityDTO.id")
  public boolean update(SalesOpportunityDTO salesOpportunityDTO) {
    // harden-oppo-stage-approval：阶段跃迁至需审批阶段时强校验已通过审批记录，杜绝绕过审批流直接跳阶段。
    requireApprovedStageTransition(salesOpportunityDTO);

    SalesOpportunityEntity salesOpportunityEntity = new SalesOpportunityEntity();
    BeanUtils.copyProperties(salesOpportunityDTO, salesOpportunityEntity);
    salesOpportunityEntity.setCreatorId(BaseUnit.getCurrentId());
    boolean updated = updateById(salesOpportunityEntity);

    // add-sales-opportunity-semantic-retrieval 任务 2.2：更新成功提交后异步重嵌语义向量
    // （回查完整实体防 DTO 部分字段丢键；afterCommit/无事务均异步派发，主线程零等待）
    runSemanticSync("更新商机", () -> reingestSemanticById(salesOpportunityEntity.getId()));

    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "opportunityName", key = "#id")
  public boolean delete(Long id) {
    // 收集不符合状态的商机ID
    SalesOpportunityEntity entity = getById(id);
    if (entity.getStage() != 5) {
      String msg = ErrorCode.OPPORTUNITY_MUST_BE_CLOSED.getMessage() + "，不符合的商机ID：" + id;
      throw new BaseException(ErrorCode.OPPORTUNITY_MUST_BE_CLOSED, msg);
    }

    // fix-approval-attachment-cascade-dead-edge：阶段审批附件属于审批行，必须在级联物理删审批之前显式清理
    // （附件表是多态表 and_id+model_name，级联机器不能裸列级联；canonical 删除含磁盘清理）。
    deleteStageApprovalAttachments(id);

    // fix-backlog-batch3 任务 4.2：级联物理删商机还会带走其名下商业活动与联络任务，
    // 这两类宿主的多态附件必须在级联前按 (and_id, model_name) 精确清理（同上：级联机器带不了 model_name 判别）
    childHostAttachmentCleaner.cleanBeforeOpportunityPhysicalDelete(List.of(id));

    foreignKeyDeleteUtil.deleteCascade(SalesOpportunityEntity.class, List.of(id), 0);
    boolean deleted = salesOpportunityMapper.logicalDeleteById(id);

    // add-sales-opportunity-semantic-retrieval 任务 2.2：删除事务提交后异步失效向量（删除即失效）
    runSemanticSync(
        "删除商机",
        () -> SalesOpportunitySemanticHookSupport.deleteById(id, salesOpportunityIngestionService));

    return deleted;
  }

  /**
   * 删除商机名下阶段审批的全部附件（fix-approval-attachment-cascade-dead-edge 任务 2.2）。
   *
   * <p><b>为什么必须显式补删</b>：{@code approval_attachment} 是多态附件表（{@code and_id} + {@code model_name}
   * 判别），且无 {@code is_deleted} 列——删除语义是「DB 物理删 + 磁盘文件清理」。级联机器只能按单列外键递归，无法携带 {@code model_name}
   * 判别条件（裸 {@code and_id} 级联会误删 {@code business_activity} 等其他模型同 id 的附件），也无法承载子删除的磁盘 IO， 故按 {@code
   * (ModelName.APPROVAL_ATTACHMENT, approvalIds)} 精确收集后走 canonical {@link
   * ApprovalAttachmentService#removeByIds}，与 {@code SalesStageApprovalController#deleteStage} 同型。
   *
   * <p><b>鉴权差异</b>：{@code deleteStage} 是「单条审批附件删除」的独立业务入口，须逐附件做 {@code canWriteAttachments}
   * 判定；本方法是「商机删除」的级联清理，调用方（商机删除端点）已完成业务对象级删除权校验，级联过程不再逐附件鉴权， 否则同一事务内的清理会因附件上传人不同而部分失败。差异与理由见
   * handoff。
   *
   * @param opportunityId 商机ID
   */
  private void deleteStageApprovalAttachments(Long opportunityId) {
    List<SalesStageApprovalEntity> approvals =
        salesStageApprovalMapper.selectAllApprovalsByOpportunityId(opportunityId);
    List<Long> approvalIds =
        approvals == null
            ? List.of()
            : approvals.stream()
                .map(SalesStageApprovalEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    if (!approvalIds.isEmpty()) {
      List<ApprovalAttachmentEntity> attachments =
          approvalAttachmentService.listEntitiesByAndIds(
              approvalIds, ModelName.APPROVAL_ATTACHMENT);
      if (attachments != null && !attachments.isEmpty()) {
        List<Long> attachmentIds =
            attachments.stream()
                .map(ApprovalAttachmentEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        approvalAttachmentService.removeByIds(attachmentIds, ModelName.APPROVAL_ATTACHMENT);
      }
    }
  }

  @Override
  @Privacy
  public Page<SalesOpportunityVO> getAllSalesOpportunity(Integer pageNum, Integer pageSize) {
    Page<SalesOpportunityEntity> page = new Page<>(pageNum, pageSize);
    Page<SalesOpportunityEntity> pageResult =
        salesOpportunityMapper.selectPage(
            page,
            new LambdaQueryWrapper<SalesOpportunityEntity>()
                .orderByDesc(SalesOpportunityEntity::getCreateTime));

    List<SalesOpportunityVO> salesOpportunityVOs =
        SalesOpportunityQuerySupport.buildVoRecords(pageResult.getRecords(), dataConvertService);

    Page<SalesOpportunityVO> pageVO = new Page<>(pageNum, pageSize, pageResult.getTotal());
    BeanUtils.copyProperties(pageResult, pageVO);
    pageVO.setRecords(salesOpportunityVOs);
    return pageVO;
  }

  @Override
  @Privacy
  public SalesOpportunityVO create(SalesOpportunityDTO dto) {

    SalesOpportunityEntity entity = new SalesOpportunityEntity();
    BeanUtils.copyProperties(dto, entity);

    // 创建人id
    entity.setCreatorId(BaseUnit.getCurrentId());

    SalesOpportunityVO result = null;
    if (baseMapper.insert(entity) > 0) {
      String companyName = dataConvertService.getCompanyName(entity.getCompanyId());
      String contactName =
          entity.getContactId() != null
              ? dataConvertService.getContactName(entity.getContactId())
              : null;
      result =
          SalesOpportunityVO.fromEntity(
              entity,
              companyName,
              contactName,
              dataConvertService.getUserName(entity.getOwnerId()),
              dataConvertService.getUserName(entity.getCreatorId()),
              dataConvertService.getUserName(entity.getApproverId()));
    }

    // add-sales-opportunity-semantic-retrieval 任务 2.2：创建成功提交后异步增量 upsert 语义向量
    // （异步派发，HTTP 主线程立即返回，不等待任何向量嵌入耗时）
    runSemanticSync("创建商机", () -> reingestSemanticById(entity.getId()));

    return result;
  }

  @Override
  @Privacy
  public Page<SalesOpportunityVO> getSalesOpportunityByQuery(
      SalesOpportunityQueryDTO queryDTO, Integer pageNum, Integer pageSize) {
    Page<SalesOpportunityEntity> page = new Page<>(pageNum, pageSize);
    Page<SalesOpportunityEntity> pageResult =
        salesOpportunityMapper.selectPage(page, getQueryWrapper(queryDTO));

    List<SalesOpportunityVO> salesOpportunityVOs =
        SalesOpportunityQuerySupport.buildVoRecords(pageResult.getRecords(), dataConvertService);

    Page<SalesOpportunityVO> ansPage = new Page<>();
    BeanUtils.copyProperties(pageResult, ansPage);
    ansPage.setRecords(salesOpportunityVOs);
    return ansPage;
  }

  /**
   * 构造自定义销售机会查询条件
   *
   * @param queryDTO 查询条件DTO
   * @return 查询条件
   */
  public LambdaQueryWrapper<SalesOpportunityEntity> getQueryWrapper(
      SalesOpportunityQueryDTO queryDTO) {
    return SalesOpportunityQuerySupport.getQueryWrapper(
        queryDTO, customerCompanyMapper, customerContactMapper, userMapper);
  }

  @Override
  public void cascadeDeleteByCompanyId(Long companyId) {
    LambdaUpdateWrapper<SalesOpportunityEntity> wrapper = new LambdaUpdateWrapper<>();
    wrapper
        .eq(SalesOpportunityEntity::getCompanyId, companyId)
        .eq(SalesOpportunityEntity::getIsDeleted, Boolean.FALSE)
        .set(SalesOpportunityEntity::getIsDeleted, Boolean.TRUE);
    update(wrapper);
  }

  @Override
  public void cascadeDeleteByContactIds(List<Long> contactIds) {
    if (contactIds == null || contactIds.isEmpty()) {
      return;
    }
    LambdaUpdateWrapper<SalesOpportunityEntity> wrapper = new LambdaUpdateWrapper<>();
    wrapper
        .in(SalesOpportunityEntity::getContactId, contactIds)
        .eq(SalesOpportunityEntity::getIsDeleted, Boolean.FALSE)
        .set(SalesOpportunityEntity::getIsDeleted, Boolean.TRUE);
    update(wrapper);
  }

  @Override
  @Privacy
  public com.slz.crm.pojo.vo.OpportunityDetailVO getOpportunityDetailById(Long opportunityId) {
    // 1. 根据销售机会ID查询商机信息
    if (opportunityId == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "销售机会ID不能为空");
    }

    SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(opportunityId);
    if (opportunity == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS, "商机不存在，商机ID：" + opportunityId);
    }
    boolean temporaryAssistAccess =
        businessRecordAccessService.assertCanReadOpportunity(opportunity);
    Long currentId = BaseUnit.getCurrentId();
    List<AssistRequestEntity> visibleAssists =
        temporaryAssistAccess
            ? assistScopeService.visibleAssistsForOpportunity(currentId, opportunityId)
            : Collections.emptyList();
    OpportunityAssistVisibility visibility =
        temporaryAssistAccess
            ? OpportunityAssistVisibility.from(visibleAssists)
            : OpportunityAssistVisibility.unrestricted();

    // 2. 获取商机相关信息
    com.slz.crm.pojo.vo.OpportunityDetailVO detailVO =
        com.slz.crm.pojo.vo.OpportunityDetailVO.fromEntity(
            opportunity,
            dataConvertService.getCompanyName(opportunity.getCompanyId()),
            dataConvertService.getContactName(opportunity.getContactId()),
            dataConvertService.getUserName(opportunity.getOwnerId()),
            dataConvertService.getUserName(opportunity.getCreatorId()),
            dataConvertService.getUserName(opportunity.getApproverId()));

    // 3. 查询商机状态变更记录（已通过的审批记录,用于构建阶段时间线）
    List<com.slz.crm.pojo.entity.SalesStageApprovalEntity> approvals =
        salesStageApprovalMapper.selectApprovedApprovalsByOpportunityId(opportunityId);

    // 4. 查询该商机的所有业务活动
    List<com.slz.crm.pojo.entity.BusinessActivityEntity> activities =
        businessActivityMapper.selectByOpportunityId(opportunityId);
    activities = visibility.filterActivities(activities);

    // 5. 按商机状态分组业务活动（根据审批完成时间匹配阶段）
    detailVO.setActivitiesByStage(
        detailAssembler().groupActivitiesByStage(opportunity, activities, approvals));

    // 6. 构建审批记录VO列表（包含所有状态的审批记录）
    List<com.slz.crm.pojo.vo.SalesStageApprovalVO> approvalVOs =
        detailAssembler().buildApprovalRecords(opportunity, opportunityId, visibility);

    // 按每次审批组装协助人（不同审批协助人可能不同）
    detailAssembler().fillApprovalAssistUsers(approvalVOs, BaseUnit.getCurrentId());
    detailVO.setStageChangeRecords(approvalVOs);

    // 对象级授权已经确认当前用户与该商机有关，协助场景下视为本人不脱敏
    if (currentId != null && temporaryAssistAccess) {
      detailVO.setRelatedUserIds(Set.of(currentId));
    }

    return detailVO;
  }

  /** 商机详情的临时协助可见范围。 审批协助人可读完整详情；联络任务协助人只读该任务关联活动； 业务活动协助人只读被协助的活动。正常数据权限不进入该分支。 */
  record OpportunityAssistVisibility(
      boolean restricted, boolean fullDetail, Set<Long> taskIds, Set<Long> activityIds) {

    static OpportunityAssistVisibility from(List<AssistRequestEntity> assists) {
      OpportunityAssistVisibility visibility;
      if (assists == null || assists.isEmpty()) {
        visibility =
            new OpportunityAssistVisibility(
                true, false, Collections.emptySet(), Collections.emptySet());
      } else {
        boolean approvalAssist =
            assists.stream()
                .anyMatch(assist -> ModelName.SALES_STAGE_APPROVAL.equals(assist.getModelName()));
        Set<Long> taskIds =
            assists.stream()
                .filter(assist -> ModelName.CONTACT_TASK.equals(assist.getModelName()))
                .map(AssistRequestEntity::getRecordId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> activityIds =
            assists.stream()
                .filter(assist -> ModelName.BUSINESS_ACTIVITY.equals(assist.getModelName()))
                .map(AssistRequestEntity::getRecordId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        visibility = new OpportunityAssistVisibility(true, approvalAssist, taskIds, activityIds);
      }
      return visibility;
    }

    static OpportunityAssistVisibility unrestricted() {
      return new OpportunityAssistVisibility(
          false, true, Collections.emptySet(), Collections.emptySet());
    }

    List<BusinessActivityEntity> filterActivities(List<BusinessActivityEntity> activities) {
      List<BusinessActivityEntity> result;
      if (!restricted || fullDetail || activities == null || activities.isEmpty()) {
        result = activities == null ? Collections.emptyList() : activities;
      } else {
        result =
            activities.stream()
                .filter(
                    activity ->
                        activityIds.contains(activity.getId())
                            || (activity.getTaskId() != null
                                && taskIds.contains(activity.getTaskId())))
                .toList();
      }
      return result;
    }
  }

  @Override
  @Privacy
  public com.slz.crm.pojo.vo.OpportunityDetailVO getOpportunityDetailByContractId(Long contractId) {
    // 1. 根据合同ID查询商机信息
    com.slz.crm.pojo.entity.ContractEntity contract = contractMapper.selectById(contractId);
    if (contract == null) {
      throw new BaseException(ErrorCode.CONTRACT_NOT_EXISTS, "合同不存在，合同ID：" + contractId);
    }

    Long opportunityId = contract.getOpportunityId();
    if (opportunityId == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS, "该合同未关联商机");
    }

    return getOpportunityDetailById(opportunityId);
  }
}
