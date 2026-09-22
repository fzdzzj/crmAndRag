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
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.AssistScopeService;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.SalesOpportunityService;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
  @Autowired private com.slz.crm.server.mapper.ContractMapper contractMapper;
  @Autowired private com.slz.crm.server.mapper.BusinessActivityMapper businessActivityMapper;
  @Autowired private com.slz.crm.server.mapper.SalesStageApprovalMapper salesStageApprovalMapper;
  @Autowired private DataConvertService dataConvertService;
  @Autowired private AssistRequestService assistRequestService;

  @Autowired private BusinessRecordAccessService businessRecordAccessService;
  @Autowired private AssistScopeService assistScopeService;

  /** 详情装配协作对象（经工厂构造，复用主类依赖，保持 @InjectMocks 测试行为不变） */
  private SalesOpportunityDetailAssembler detailAssembler() {
    return new SalesOpportunityDetailAssembler(
        dataConvertService, salesStageApprovalMapper, assistRequestService, userMapper);
  }

  @Override
  @CacheEvict(value = "opportunityName", key = "#salesOpportunityDTO.id")
  public boolean update(SalesOpportunityDTO salesOpportunityDTO) {
    SalesOpportunityEntity salesOpportunityEntity = new SalesOpportunityEntity();
    BeanUtils.copyProperties(salesOpportunityDTO, salesOpportunityEntity);
    salesOpportunityEntity.setCreatorId(BaseUnit.getCurrentId());
    return updateById(salesOpportunityEntity);
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

    foreignKeyDeleteUtil.deleteCascade(SalesOpportunityEntity.class, List.of(id), 0);
    return salesOpportunityMapper.logicalDeleteById(id);
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
