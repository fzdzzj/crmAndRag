package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.pojo.dto.SalesOpportunityDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.entity.*;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.AssistScopeService;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.SalesOpportunityService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SalesOpportunityServiceImpl extends ServiceImpl<SalesOpportunityMapper, SalesOpportunityEntity> implements SalesOpportunityService {

    @Autowired
    private SalesOpportunityMapper salesOpportunityMapper;
    @Autowired
    private CustomerContactMapper customerContactMapper;
    @Autowired
    private CustomerCompanyMapper customerCompanyMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ForeignKeyDeleteUtil foreignKeyDeleteUtil;
    @Autowired
    private com.slz.crm.server.mapper.ContractMapper contractMapper;
    @Autowired
    private com.slz.crm.server.mapper.BusinessActivityMapper businessActivityMapper;
    @Autowired
    private com.slz.crm.server.mapper.SalesStageApprovalMapper salesStageApprovalMapper;
    @Autowired
    private DataConvertService dataConvertService;
    @Autowired
    private AssistRequestService assistRequestService;

    @Autowired
    private BusinessRecordAccessService businessRecordAccessService;
    @Autowired
    private AssistScopeService assistScopeService;


    @Override
    @CacheEvict(value = "opportunityName", key = "#salesOpportunityDTO.id")
    public boolean update(SalesOpportunityDTO salesOpportunityDTO) {
        SalesOpportunityEntity salesOpportunityEntity = new SalesOpportunityEntity();
        BeanUtils.copyProperties(salesOpportunityDTO, salesOpportunityEntity);
        salesOpportunityEntity.setCreatorId(BaseUnit.getCurrentId());
        return updateById(salesOpportunityEntity);
    }

    @Override
    @Transactional
    @CacheEvict(value = "opportunityName", key = "#id")
    public boolean delete(Long id) {
        // 收集不符合状态的商机ID
        SalesOpportunityEntity entity = getById(id);
        if (entity.getStage() != 5) {
            String msg = ErrorCode.OPPORTUNITY_MUST_BE_CLOSED.getMessage()
                    + "，不符合的商机ID：" + id;
            throw new BaseException(ErrorCode.OPPORTUNITY_MUST_BE_CLOSED, msg);
        }

        foreignKeyDeleteUtil.deleteCascade(SalesOpportunityEntity.class, List.of(id), 0);
        return salesOpportunityMapper.logicalDeleteById(id);
    }

    @Override
    @Privacy
    public Page<SalesOpportunityVO> getAllSalesOpportunity(Integer pageNum, Integer pageSize) {
        Page<SalesOpportunityEntity> page = new Page<>(pageNum, pageSize);
        Page<SalesOpportunityEntity> pageResult = salesOpportunityMapper.selectPage(
                page,
                new LambdaQueryWrapper<SalesOpportunityEntity>()
                        .orderByDesc(SalesOpportunityEntity::getCreateTime)
        );

        // 批量收集ID
        Set<Long> allUserIds = new HashSet<>();
        Set<Long> companyIds = new HashSet<>();
        Set<Long> contactIds = new HashSet<>();
        for (SalesOpportunityEntity entity : pageResult.getRecords()) {
            if (entity.getCreatorId() != null) allUserIds.add(entity.getCreatorId());
            if (entity.getOwnerId() != null) allUserIds.add(entity.getOwnerId());
            if (entity.getApproverId() != null) allUserIds.add(entity.getApproverId());
            if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
            if (entity.getContactId() != null) contactIds.add(entity.getContactId());
        }
        Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);
        Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
        Map<Long, String> contactNameMap = dataConvertService.getContactNames(contactIds);

        // 构建VO
        List<SalesOpportunityVO> salesOpportunityVOs = new ArrayList<>();
        for (SalesOpportunityEntity entity : pageResult.getRecords()) {
            SalesOpportunityVO vo = SalesOpportunityVO.fromEntity(entity,
                    companyNameMap.get(entity.getCompanyId()),
                    contactNameMap.get(entity.getContactId()),
                    userNameMap.get(entity.getOwnerId()),
                    userNameMap.get(entity.getCreatorId()),
                    userNameMap.get(entity.getApproverId()));
            salesOpportunityVOs.add(vo);
        }

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

        //创建人id
        entity.setCreatorId(BaseUnit.getCurrentId());

        if (baseMapper.insert(entity) <= 0) {
            return null;
        }

        String companyName = dataConvertService.getCompanyName(entity.getCompanyId());
        String contactName = entity.getContactId() != null
                ? dataConvertService.getContactName(entity.getContactId())
                : null;
        return SalesOpportunityVO.fromEntity(entity, companyName, contactName,
                dataConvertService.getUserName(entity.getOwnerId()),
                dataConvertService.getUserName(entity.getCreatorId()),
                dataConvertService.getUserName(entity.getApproverId()));
    }


    @Override
    @Privacy
    public Page<SalesOpportunityVO> getSalesOpportunityByQuery(SalesOpportunityQueryDTO queryDTO, Integer pageNum, Integer pageSize) {
        Page<SalesOpportunityEntity> page = new Page<>(pageNum, pageSize);
        Page<SalesOpportunityEntity> pageResult = salesOpportunityMapper.selectPage(
                page,
                getQueryWrapper(queryDTO)
        );

        // 批量收集ID
        Set<Long> allUserIds = new HashSet<>();
        Set<Long> companyIds = new HashSet<>();
        Set<Long> contactIds = new HashSet<>();
        for (SalesOpportunityEntity entity : pageResult.getRecords()) {
            if (entity.getCreatorId() != null) allUserIds.add(entity.getCreatorId());
            if (entity.getOwnerId() != null) allUserIds.add(entity.getOwnerId());
            if (entity.getApproverId() != null) allUserIds.add(entity.getApproverId());
            if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
            if (entity.getContactId() != null) contactIds.add(entity.getContactId());
        }
        Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);
        Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
        Map<Long, String> contactNameMap = dataConvertService.getContactNames(contactIds);

        // 构建VO
        List<SalesOpportunityVO> salesOpportunityVOs = new ArrayList<>();
        for (SalesOpportunityEntity entity : pageResult.getRecords()) {
            SalesOpportunityVO vo = SalesOpportunityVO.fromEntity(entity,
                    companyNameMap.get(entity.getCompanyId()),
                    contactNameMap.get(entity.getContactId()),
                    userNameMap.get(entity.getOwnerId()),
                    userNameMap.get(entity.getCreatorId()),
                    userNameMap.get(entity.getApproverId()));
            salesOpportunityVOs.add(vo);
        }

        Page<SalesOpportunityVO> ansPage = new Page<>();
        BeanUtils.copyProperties(pageResult, ansPage);
        ansPage.setRecords(salesOpportunityVOs);
        return ansPage;
    }

    /**
     * 构造自定义销售机会查询条件
     * @param queryDTO 查询条件DTO
     * @return 查询条件
     */
    public LambdaQueryWrapper<SalesOpportunityEntity> getQueryWrapper(SalesOpportunityQueryDTO queryDTO){
        LambdaQueryWrapper<SalesOpportunityEntity> queryWrapper = new LambdaQueryWrapper<>();

        //销售机会名称模糊搜索
        if(queryDTO.getOpportunityName() != null && !queryDTO.getOpportunityName().isEmpty()){
            queryWrapper.like(SalesOpportunityEntity::getOpportunityName,queryDTO.getOpportunityName());
        }
        //公司名称模糊搜索
        if(queryDTO.getCompanyName() != null && !queryDTO.getCompanyName().isEmpty()){
            Set<Long> companyIds = customerCompanyMapper.selectCompanyIdsByName(queryDTO.getCompanyName());
            if(!companyIds.isEmpty()){
                queryWrapper.in(SalesOpportunityEntity::getCompanyId,companyIds);
            }else{
                queryWrapper.eq(SalesOpportunityEntity::getCompanyId,-1);
            }
        }
        //联系人名称模糊搜索
        if(queryDTO.getContactName() != null && !queryDTO.getContactName().isEmpty()){
            Set<Long> contactIds = customerContactMapper.selectContactIdsByName(queryDTO.getContactName());
            if(!contactIds.isEmpty()){
                queryWrapper.in(SalesOpportunityEntity::getContactId,contactIds);
            }else{
                queryWrapper.eq(SalesOpportunityEntity::getContactId,-1);
            }
        }
        //负责人名称模糊搜索
        if(queryDTO.getOwnerName() != null && !queryDTO.getOwnerName().isEmpty()){
            Set<Long> ownerIds = userMapper.selectUserIdsByUserName(queryDTO.getOwnerName());
            if(!ownerIds.isEmpty()){
                queryWrapper.in(SalesOpportunityEntity::getOwnerId,ownerIds);
            }else{
                queryWrapper.eq(SalesOpportunityEntity::getOwnerId,-1);
            }
        }
        //审批人名称模糊搜索
        if(queryDTO.getApproverName() != null && !queryDTO.getApproverName().isEmpty()){
            Set<Long> approverIds = userMapper.selectUserIdsByUserName(queryDTO.getApproverName());
            if(!approverIds.isEmpty()){
                queryWrapper.in(SalesOpportunityEntity::getApproverId,approverIds);
            }else{
                queryWrapper.eq(SalesOpportunityEntity::getApproverId,-1);
            }
        }
        //创建人名称模糊搜索
        if(queryDTO.getCreatorName() != null && !queryDTO.getCreatorName().isEmpty()){
            Set<Long> creatorIds = userMapper.selectUserIdsByUserName(queryDTO.getCreatorName());
            if(!creatorIds.isEmpty()){
                queryWrapper.in(SalesOpportunityEntity::getCreatorId,creatorIds);
            }else{
                queryWrapper.eq(SalesOpportunityEntity::getCreatorId,-1);
            }
        }
        //销售机会阶段搜索
        if(queryDTO.getStage() != null){
            if(queryDTO.getStage() >= 0 && queryDTO.getStage() <= 5){
                queryWrapper.eq(SalesOpportunityEntity::getStage,queryDTO.getStage());
            }else{
                queryWrapper.eq(SalesOpportunityEntity::getStage,-1);
            }
        }
        //销售机会描述模糊搜索
        if(queryDTO.getDescription() != null && !queryDTO.getDescription().isEmpty()){
            queryWrapper.like(SalesOpportunityEntity::getDescription,queryDTO.getDescription());
        }
        //来源模糊搜索
        if(queryDTO.getSource() != null && !queryDTO.getSource().isEmpty()){
            queryWrapper.like(SalesOpportunityEntity::getSource,queryDTO.getSource());
        }
        //金额范围搜索
        if(queryDTO.getMinAmount() != null && queryDTO.getMaxAmount() != null){
            queryWrapper.between(SalesOpportunityEntity::getAmount,queryDTO.getMinAmount(),queryDTO.getMaxAmount());
        }
        //创建时间范围搜索
        if(queryDTO.getMinCreateTime() != null && queryDTO.getMaxCreateTime() != null){
            queryWrapper.between(SalesOpportunityEntity::getCreateTime,queryDTO.getMinCreateTime(),queryDTO.getMaxCreateTime());
        }
        //关闭日期范围搜索
        if(queryDTO.getMinExpectedCloseDate() != null && queryDTO.getMaxExpectedCloseDate() != null){
            queryWrapper.between(SalesOpportunityEntity::getExpectedCloseDate,queryDTO.getMinExpectedCloseDate(),queryDTO.getMaxExpectedCloseDate());
        }
        //负责人用户ID精确匹配
        if(queryDTO.getOwnerId() != null){
            queryWrapper.eq(SalesOpportunityEntity::getOwnerId,queryDTO.getOwnerId());
        }
        //负责人用户名模糊搜索
        if(queryDTO.getOwnerUserName() != null && !queryDTO.getOwnerUserName().isEmpty()){
            Set<Long> ownerUserIds = userMapper.selectUserIdsByUserName(queryDTO.getOwnerUserName());
            if(!ownerUserIds.isEmpty()){
                queryWrapper.in(SalesOpportunityEntity::getOwnerId,ownerUserIds);
            }else{
                queryWrapper.eq(SalesOpportunityEntity::getOwnerId,-1);
            }
        }
        return queryWrapper;
    }

    @Override
    public void cascadeDeleteByCompanyId(Long companyId) {
        LambdaUpdateWrapper<SalesOpportunityEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SalesOpportunityEntity::getCompanyId, companyId)
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
        wrapper.in(SalesOpportunityEntity::getContactId, contactIds)
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
        boolean temporaryAssistAccess = businessRecordAccessService.assertCanReadOpportunity(opportunity);
        Long currentId = BaseUnit.getCurrentId();
        List<AssistRequestEntity> visibleAssists = temporaryAssistAccess
                ? assistScopeService.visibleAssistsForOpportunity(currentId, opportunityId)
                : Collections.emptyList();
        OpportunityAssistVisibility visibility = temporaryAssistAccess
                ? OpportunityAssistVisibility.from(visibleAssists)
                : OpportunityAssistVisibility.unrestricted();

        // 2. 获取商机相关信息
        com.slz.crm.pojo.vo.OpportunityDetailVO detailVO = com.slz.crm.pojo.vo.OpportunityDetailVO.fromEntity(
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
        java.util.Map<String, List<com.slz.crm.pojo.vo.BusinessActivityVO>> activitiesByStage = new java.util.LinkedHashMap<>();

        // 为所有6个商机状态初始化空列表（确保所有状态都返回）
        activitiesByStage.put("种子商机", new java.util.ArrayList<>());
        activitiesByStage.put("潜在商机", new java.util.ArrayList<>());
        activitiesByStage.put("确认商机", new java.util.ArrayList<>());
        activitiesByStage.put("储备项目", new java.util.ArrayList<>());
        activitiesByStage.put("立项签约", new java.util.ArrayList<>());
        activitiesByStage.put("关闭", new java.util.ArrayList<>());

        // 如果没有审批记录，所有活动归入当前阶段
        if (approvals.isEmpty()) {
            String currentStageName = com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(opportunity.getStage());

            for (com.slz.crm.pojo.entity.BusinessActivityEntity activity : activities) {
                String actCreatorName = dataConvertService.getUserName(activity.getCreatorId());
                com.slz.crm.pojo.vo.BusinessActivityVO activityVO = com.slz.crm.pojo.vo.BusinessActivityVO.fromEntity(
                        activity, actCreatorName, opportunity.getOpportunityName());
                activitiesByStage.get(currentStageName).add(activityVO);
            }
        } else {
            // 构建阶段时间线：阶段 -> 开始时间（审批完成时间）
            java.util.Map<Integer, java.time.LocalDateTime> stageStartTimeMap = new java.util.LinkedHashMap<>();

            // 初始阶段从商机创建时间开始
            stageStartTimeMap.put(approvals.get(0).getCurrentStage(), opportunity.getCreateTime());

            // 记录每个阶段的开始时间（审批通过后进入新阶段）
            for (com.slz.crm.pojo.entity.SalesStageApprovalEntity approval : approvals) {
                stageStartTimeMap.put(approval.getTargetStage(), approval.getApprovalTime());
            }

            // 将业务活动按时间匹配到对应阶段
            for (com.slz.crm.pojo.entity.BusinessActivityEntity activity : activities) {
                if (activity.getActivityTime() == null) {
                    continue; // 跳过没有时间的活动
                }

                String actCreatorName = dataConvertService.getUserName(activity.getCreatorId());
                com.slz.crm.pojo.vo.BusinessActivityVO activityVO = com.slz.crm.pojo.vo.BusinessActivityVO.fromEntity(
                        activity, actCreatorName, opportunity.getOpportunityName());

                // 找到活动时间对应的阶段
                String matchedStageName = findStageByActivityTime(
                        activity.getActivityTime(),
                        stageStartTimeMap,
                        opportunity
                );

                if (matchedStageName != null) {
                    activitiesByStage.get(matchedStageName).add(activityVO);
                }
            }
        }

        detailVO.setActivitiesByStage(activitiesByStage);

        // 6. 构建审批记录VO列表（包含所有状态的审批记录）
        List<com.slz.crm.pojo.entity.SalesStageApprovalEntity> allApprovals =
                visibility.fullDetail()
                        ? salesStageApprovalMapper.selectAllApprovalsByOpportunityId(opportunityId)
                        : Collections.emptyList();

        List<com.slz.crm.pojo.vo.SalesStageApprovalVO> approvalVOs = new java.util.ArrayList<>();
        // 批量收集审批人ID，避免N+1查询
        Set<Long> approverIds = new java.util.HashSet<>();
        for (com.slz.crm.pojo.entity.SalesStageApprovalEntity approval : allApprovals) {
            if (approval.getApproverId() != null) {
                approverIds.add(approval.getApproverId());
            }
        }
        // 批量查询审批人姓名
        Map<Long, String> approverNameMap = dataConvertService.getUserNames(approverIds);

        for (com.slz.crm.pojo.entity.SalesStageApprovalEntity approval : allApprovals) {
            String currentStageName = com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(approval.getCurrentStage());
            String targetStageName = com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(approval.getTargetStage());

            // 从Map中获取审批人姓名
            String approvalApproverName = approval.getApproverId() != null ?
                    approverNameMap.get(approval.getApproverId()) : null;

            com.slz.crm.pojo.vo.SalesStageApprovalVO approvalVO = com.slz.crm.pojo.vo.SalesStageApprovalVO.fromEntity(
                    approval, opportunity.getOpportunityName(), currentStageName, targetStageName, approvalApproverName);
            approvalVOs.add(approvalVO);
        }

        // 按每次审批组装协助人（不同审批协助人可能不同）
        fillApprovalAssistUsers(approvalVOs, BaseUnit.getCurrentId());
        detailVO.setStageChangeRecords(approvalVOs);

        // 对象级授权已经确认当前用户与该商机有关，协助场景下视为本人不脱敏
        if (currentId != null && temporaryAssistAccess) {
            detailVO.setRelatedUserIds(Set.of(currentId));
        }

        return detailVO;
    }

    /**
     * 商机详情的临时协助可见范围。
     * 审批协助人可读完整详情；联络任务协助人只读该任务关联活动；
     * 业务活动协助人只读被协助的活动。正常数据权限不进入该分支。
     */
    private record OpportunityAssistVisibility(boolean restricted,
                                               boolean fullDetail,
                                               Set<Long> taskIds,
                                               Set<Long> activityIds) {

        static OpportunityAssistVisibility from(List<AssistRequestEntity> assists) {
            if (assists == null || assists.isEmpty()) {
                return new OpportunityAssistVisibility(true, false, Collections.emptySet(), Collections.emptySet());
            }
            boolean approvalAssist = assists.stream()
                    .anyMatch(assist -> ModelName.SALES_STAGE_APPROVAL.equals(assist.getModelName()));
            Set<Long> taskIds = assists.stream()
                    .filter(assist -> ModelName.CONTACT_TASK.equals(assist.getModelName()))
                    .map(AssistRequestEntity::getRecordId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<Long> activityIds = assists.stream()
                    .filter(assist -> ModelName.BUSINESS_ACTIVITY.equals(assist.getModelName()))
                    .map(AssistRequestEntity::getRecordId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            return new OpportunityAssistVisibility(true, approvalAssist, taskIds, activityIds);
        }

        static OpportunityAssistVisibility unrestricted() {
            return new OpportunityAssistVisibility(false, true, Collections.emptySet(), Collections.emptySet());
        }

        List<BusinessActivityEntity> filterActivities(List<BusinessActivityEntity> activities) {
            if (!restricted || fullDetail || activities == null || activities.isEmpty()) {
                return activities == null ? Collections.emptyList() : activities;
            }
            return activities.stream()
                    .filter(activity -> activityIds.contains(activity.getId())
                            || (activity.getTaskId() != null && taskIds.contains(activity.getTaskId())))
                    .toList();
        }
    }

    /**
     * 为商机详情的审批记录批量填充协助人（每次审批独立展示）
     * 可见性规则与审批列表一致：超管/申请人/审批人可见全部；协助人仅可见指派给自己的；其他人不展示
     *
     * @param approvalVOs 审批VO列表
     * @param currentId   当前登录用户ID
     */
    private void fillApprovalAssistUsers(List<SalesStageApprovalVO> approvalVOs, Long currentId) {
        if (approvalVOs == null || approvalVOs.isEmpty()) {
            return;
        }
        List<Long> approvalIds = approvalVOs.stream().map(SalesStageApprovalVO::getId).toList();
        List<AssistVO> allAssists = assistRequestService.listAssistsByRecords(
                ModelName.SALES_STAGE_APPROVAL, approvalIds);
        if (allAssists.isEmpty()) {
            approvalVOs.forEach(vo -> vo.setAssistUsers(Collections.emptyList()));
            return;
        }
        UserEntity currentUser = userMapper.selectById(currentId);
        boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
        Map<Long, List<AssistVO>> byRecord = allAssists.stream()
                .collect(Collectors.groupingBy(AssistVO::getRecordId));
        for (SalesStageApprovalVO vo : approvalVOs) {
            List<AssistVO> list = byRecord.getOrDefault(vo.getId(), Collections.emptyList());
            if (list.isEmpty()) {
                vo.setAssistUsers(Collections.emptyList());
                continue;
            }
            // 超管/审批人/申请人可见全部；协助人仅可见指派给自己的；其他人不展示
            if (isAdmin
                    || Objects.equals(vo.getApproverId(), currentId)
                    || list.stream().anyMatch(a -> Objects.equals(a.getApplicantId(), currentId))) {
                vo.setAssistUsers(list);
            } else {
                vo.setAssistUsers(list.stream()
                        .filter(a -> Objects.equals(a.getAssistUserId(), currentId))
                        .toList());
            }
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

    /**
     * 根据业务活动时间找到对应的阶段名称
     * 核心逻辑：审批通过后进入新阶段，所以活动应该归入旧阶段
     * 例如：状态1 -> 状态2的审批通过后，这段时间的活动归入状态1，而不是状态2
     *
     * @param activityTime 业务活动时间
     * @param stageStartTimeMap 阶段开始时间映射
     * @param opportunity 商机实体
     * @return 阶段名称
     */
    private String findStageByActivityTime(
            java.time.LocalDateTime activityTime,
            java.util.Map<Integer, java.time.LocalDateTime> stageStartTimeMap,
            SalesOpportunityEntity opportunity) {

        // 将阶段开始时间转换为列表并排序
        java.util.List<java.util.Map.Entry<Integer, java.time.LocalDateTime>> stageStartTimes = new java.util.ArrayList<>(stageStartTimeMap.entrySet());
        stageStartTimes.sort(java.util.Map.Entry.comparingByValue());

        // 遍历排序后的阶段时间点，找到活动时间对应的阶段
        for (int i = 0; i < stageStartTimes.size(); i++) {
            java.util.Map.Entry<Integer, java.time.LocalDateTime> currentEntry = stageStartTimes.get(i);
            java.time.LocalDateTime currentStageStartTime = currentEntry.getValue();

            // 如果还有下一个阶段，获取下一个阶段的开始时间
            if (i + 1 < stageStartTimes.size()) {
                java.time.LocalDateTime nextStageStartTime = stageStartTimes.get(i + 1).getValue();

                // 活动时间在当前阶段开始时间（包含）到下一阶段开始时间（不包含）之间
                // 归入当前阶段
                if ((!activityTime.isBefore(currentStageStartTime)) && activityTime.isBefore(nextStageStartTime)) {
                    return com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(currentEntry.getKey());
                }
            } else {
                // 这是最后一个阶段，活动时间在该阶段开始时间之后都归入该阶段
                if (!activityTime.isBefore(currentStageStartTime)) {
                    return com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(currentEntry.getKey());
                }
            }
        }

        // 如果活动时间早于所有阶段开始时间，归入初始阶段
        if (!stageStartTimes.isEmpty()) {
            return com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(stageStartTimes.get(0).getKey());
        }

        // 兜底：返回当前阶段
        return com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(opportunity.getStage());
    }
}
