package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商机详情装配协作类（tighten-pmd-residual-325 任务 6.3 拆自 SalesOpportunityServiceImpl，
 * 行为等价）。由主类经工厂方法构造，复用主类注入的 mapper/service，保证 @InjectMocks 测试行为不变。
 */
class SalesOpportunityDetailAssembler {

  private final DataConvertService dataConvertService;
  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final AssistRequestService assistRequestService;
  private final UserMapper userMapper;

  SalesOpportunityDetailAssembler(
      DataConvertService dataConvertService,
      SalesStageApprovalMapper salesStageApprovalMapper,
      AssistRequestService assistRequestService,
      UserMapper userMapper) {
    this.dataConvertService = dataConvertService;
    this.salesStageApprovalMapper = salesStageApprovalMapper;
    this.assistRequestService = assistRequestService;
    this.userMapper = userMapper;
  }

  /**
   * 按商机状态分组业务活动：无审批记录时全部归入当前阶段，否则按审批时间线匹配（拆自 getOpportunityDetailById，行为等价）。
   *
   * @param opportunity 商机实体
   * @param activities 业务活动列表
   * @param approvals 已通过的审批记录
   * @return 阶段名 -> 活动 VO 列表
   */
  Map<String, List<BusinessActivityVO>> groupActivitiesByStage(
      SalesOpportunityEntity opportunity,
      List<BusinessActivityEntity> activities,
      List<SalesStageApprovalEntity> approvals) {
    Map<String, List<BusinessActivityVO>> activitiesByStage = new LinkedHashMap<>();

    // 为所有6个商机状态初始化空列表（确保所有状态都返回）
    activitiesByStage.put("种子商机", new ArrayList<>());
    activitiesByStage.put("潜在商机", new ArrayList<>());
    activitiesByStage.put("确认商机", new ArrayList<>());
    activitiesByStage.put("储备项目", new ArrayList<>());
    activitiesByStage.put("立项签约", new ArrayList<>());
    activitiesByStage.put("关闭", new ArrayList<>());

    // 如果没有审批记录，所有活动归入当前阶段
    if (approvals.isEmpty()) {
      String currentStageName = getStageName(opportunity.getStage());

      for (BusinessActivityEntity activity : activities) {
        String actCreatorName = dataConvertService.getUserName(activity.getCreatorId());
        BusinessActivityVO activityVO =
            BusinessActivityVO.fromEntity(
                activity, actCreatorName, opportunity.getOpportunityName());
        activitiesByStage.get(currentStageName).add(activityVO);
      }
    } else {
      // 构建阶段时间线：阶段 -> 开始时间（审批完成时间）
      Map<Integer, java.time.LocalDateTime> stageStartTimeMap = new LinkedHashMap<>();

      // 初始阶段从商机创建时间开始
      stageStartTimeMap.put(approvals.get(0).getCurrentStage(), opportunity.getCreateTime());

      // 记录每个阶段的开始时间（审批通过后进入新阶段）
      for (SalesStageApprovalEntity approval : approvals) {
        stageStartTimeMap.put(approval.getTargetStage(), approval.getApprovalTime());
      }

      // 将业务活动按时间匹配到对应阶段
      for (BusinessActivityEntity activity : activities) {
        if (activity.getActivityTime() == null) {
          continue; // 跳过没有时间的活动
        }

        String actCreatorName = dataConvertService.getUserName(activity.getCreatorId());
        BusinessActivityVO activityVO =
            BusinessActivityVO.fromEntity(
                activity, actCreatorName, opportunity.getOpportunityName());

        // 找到活动时间对应的阶段
        String matchedStageName =
            findStageByActivityTime(activity.getActivityTime(), stageStartTimeMap, opportunity);

        if (matchedStageName != null) {
          activitiesByStage.get(matchedStageName).add(activityVO);
        }
      }
    }

    return activitiesByStage;
  }

  /**
   * 构建审批记录 VO 列表（包含所有状态的审批记录，拆自 getOpportunityDetailById，行为等价）。
   *
   * @param opportunity 商机实体
   * @param opportunityId 商机 ID
   * @param visibility 协助可见范围
   * @return 审批记录 VO 列表
   */
  List<SalesStageApprovalVO> buildApprovalRecords(
      SalesOpportunityEntity opportunity,
      Long opportunityId,
      SalesOpportunityServiceImpl.OpportunityAssistVisibility visibility) {
    List<SalesStageApprovalEntity> allApprovals =
        visibility.fullDetail()
            ? salesStageApprovalMapper.selectAllApprovalsByOpportunityId(opportunityId)
            : List.of();

    List<SalesStageApprovalVO> approvalVOs = new ArrayList<>();
    // 批量收集审批人ID，避免N+1查询
    Set<Long> approverIds = new HashSet<>();
    for (SalesStageApprovalEntity approval : allApprovals) {
      if (approval.getApproverId() != null) {
        approverIds.add(approval.getApproverId());
      }
    }
    // 批量查询审批人姓名
    Map<Long, String> approverNameMap = dataConvertService.getUserNames(approverIds);

    for (SalesStageApprovalEntity approval : allApprovals) {
      String currentStageName = getStageName(approval.getCurrentStage());
      String targetStageName = getStageName(approval.getTargetStage());

      // 从Map中获取审批人姓名
      String approvalApproverName =
          approval.getApproverId() != null ? approverNameMap.get(approval.getApproverId()) : null;

      SalesStageApprovalVO approvalVO =
          SalesStageApprovalVO.fromEntity(
              approval,
              opportunity.getOpportunityName(),
              currentStageName,
              targetStageName,
              approvalApproverName);
      approvalVOs.add(approvalVO);
    }

    return approvalVOs;
  }

  /**
   * 为商机详情的审批记录批量填充协助人（每次审批独立展示） 可见性规则与审批列表一致：超管/申请人/审批人可见全部；协助人仅可见指派给自己的；其他人不展示
   *
   * @param approvalVOs 审批VO列表
   * @param currentId 当前登录用户ID
   */
  void fillApprovalAssistUsers(List<SalesStageApprovalVO> approvalVOs, Long currentId) {
    if (approvalVOs != null && !approvalVOs.isEmpty()) {
      doFillApprovalAssistUsers(approvalVOs, currentId);
    }
  }

  private void doFillApprovalAssistUsers(List<SalesStageApprovalVO> approvalVOs, Long currentId) {
    List<Long> approvalIds = approvalVOs.stream().map(SalesStageApprovalVO::getId).toList();
    List<com.slz.crm.pojo.vo.AssistVO> allAssists =
        assistRequestService.listAssistsByRecords(
            com.slz.crm.common.enumeration.ModelName.SALES_STAGE_APPROVAL, approvalIds);
    if (allAssists.isEmpty()) {
      approvalVOs.forEach(vo -> vo.setAssistUsers(List.of()));
    } else {
      com.slz.crm.pojo.entity.UserEntity currentUser = userMapper.selectById(currentId);
      boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
      Map<Long, List<com.slz.crm.pojo.vo.AssistVO>> byRecord =
          allAssists.stream()
              .collect(Collectors.groupingBy(com.slz.crm.pojo.vo.AssistVO::getRecordId));
      for (SalesStageApprovalVO vo : approvalVOs) {
        List<com.slz.crm.pojo.vo.AssistVO> list = byRecord.getOrDefault(vo.getId(), List.of());
        if (list.isEmpty()) {
          vo.setAssistUsers(List.of());
          continue;
        }
        // 超管/审批人/申请人可见全部；协助人仅可见指派给自己的；其他人不展示
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

  /**
   * 根据业务活动时间找到对应的阶段名称 核心逻辑：审批通过后进入新阶段，所以活动应该归入旧阶段 例如：状态1 -> 状态2的审批通过后，这段时间的活动归入状态1，而不是状态2
   *
   * @param activityTime 业务活动时间
   * @param stageStartTimeMap 阶段开始时间映射
   * @param opportunity 商机实体
   * @return 阶段名称
   */
  @SuppressWarnings("PMD.OnlyOneReturn") // 时间线区间匹配：循环内命中即返回是最自然的表达，聚合为单出口需引入额外标志变量，降低可读性
  private String findStageByActivityTime(
      java.time.LocalDateTime activityTime,
      Map<Integer, java.time.LocalDateTime> stageStartTimeMap,
      SalesOpportunityEntity opportunity) {

    // 将阶段开始时间转换为列表并排序
    List<Map.Entry<Integer, java.time.LocalDateTime>> stageStartTimes =
        new ArrayList<>(stageStartTimeMap.entrySet());
    stageStartTimes.sort(Map.Entry.comparingByValue());

    // 遍历排序后的阶段时间点，找到活动时间对应的阶段
    for (int i = 0; i < stageStartTimes.size(); i++) {
      Map.Entry<Integer, java.time.LocalDateTime> currentEntry = stageStartTimes.get(i);
      java.time.LocalDateTime currentStageStartTime = currentEntry.getValue();

      // 如果还有下一个阶段，获取下一个阶段的开始时间
      if (i + 1 < stageStartTimes.size()) {
        java.time.LocalDateTime nextStageStartTime = stageStartTimes.get(i + 1).getValue();

        // 活动时间在当前阶段开始时间（包含）到下一阶段开始时间（不包含）之间
        // 归入当前阶段
        if ((!activityTime.isBefore(currentStageStartTime))
            && activityTime.isBefore(nextStageStartTime)) {
          return getStageName(currentEntry.getKey());
        }
      } else {
        // 这是最后一个阶段，活动时间在该阶段开始时间之后都归入该阶段
        if (!activityTime.isBefore(currentStageStartTime)) {
          return getStageName(currentEntry.getKey());
        }
      }
    }

    // 如果活动时间早于所有阶段开始时间，归入初始阶段
    if (!stageStartTimes.isEmpty()) {
      return getStageName(stageStartTimes.get(0).getKey());
    }

    // 兜底：返回当前阶段
    return getStageName(opportunity.getStage());
  }

  private static String getStageName(Integer stage) {
    return com.slz.crm.pojo.vo.OpportunityDetailVO.getStageName(stage);
  }
}
