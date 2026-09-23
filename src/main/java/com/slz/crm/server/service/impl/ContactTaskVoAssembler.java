package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 联络任务 VO 装配支持类：批量关联数据查询与 VO 组装簇，纯静态、无状态，依赖经参数传入。 */
final class ContactTaskVoAssembler {

  private ContactTaskVoAssembler() {}

  /** 单条详情的 VO 装配：完整关联数据 + 关联业务活动 + 可见协助人。 */
  static ContactTaskVO buildDetailVo(
      ContactTaskEntity entity,
      Long id,
      Long currentId,
      DataConvertService dataConvertService,
      BusinessActivityMapper businessActivityMapper,
      AssistRequestService assistRequestService) {
    // 查询单个任务时，也需要查询关联数据，确保返回完整的 VO 信息
    ContactTaskVO vo =
        convertToVOListWithBatchQuery(Collections.singletonList(entity), dataConvertService).get(0);

    // 查询相关的业务活动列表
    List<BusinessActivityEntity> businessActivities = businessActivityMapper.selectByTaskId(id);

    if (!businessActivities.isEmpty()) {
      vo.setBusinessActivities(
          convertBusinessActivitiesToVOs(businessActivities, dataConvertService));
    }

    vo.setAssistUsers(
        assistRequestService.getVisibleAssists(ModelName.CONTACT_TASK, id, currentId));
    return vo;
  }

  /** 将商业活动实体列表转换为VO列表 */
  static List<BusinessActivityVO> convertBusinessActivitiesToVOs(
      List<BusinessActivityEntity> entities, DataConvertService dataConvertService) {
    List<BusinessActivityVO> voList = new ArrayList<>();
    if (entities != null && !entities.isEmpty()) {
      // 收集所有需要查询的ID
      Set<Long> creatorIds = new HashSet<>();
      Set<Long> opportunityIds = new HashSet<>();

      for (BusinessActivityEntity entity : entities) {
        if (entity.getCreatorId() != null) creatorIds.add(entity.getCreatorId());
        if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
      }

      // 使用 DataConvertService 批量查询用户名称和销售机会名称
      Map<Long, String> creatorNameMap = dataConvertService.getUserNames(creatorIds);
      Map<Long, String> opportunityNameMap = dataConvertService.getOpportunityNames(opportunityIds);

      // 构建VO列表
      for (BusinessActivityEntity entity : entities) {
        BusinessActivityVO vo = new BusinessActivityVO();
        vo.setId(entity.getId());
        vo.setActivityTitle(entity.getActivityTitle());
        vo.setActivityContent(entity.getActivityContent());
        vo.setActivityTime(entity.getActivityTime());
        vo.setActivityType(entity.getActivityType());
        vo.setActivityDuration(entity.getActivityDuration());
        vo.setOpportunityId(entity.getOpportunityId());
        vo.setCreateTime(entity.getCreateTime());
        vo.setRemark(entity.getRemark());
        vo.setCreatorId(entity.getCreatorId());
        vo.setTaskId(entity.getTaskId());

        // 设置创建者名称
        vo.setCreatorName(creatorNameMap.get(entity.getCreatorId()));

        // 设置销售机会名称
        vo.setOpportunityName(opportunityNameMap.get(entity.getOpportunityId()));

        voList.add(vo);
      }
    }

    return voList;
  }

  /** 批量查询关联数据并转换为VO列表（性能优化） */
  static List<ContactTaskVO> convertToVOListWithBatchQuery(
      List<ContactTaskEntity> entities, DataConvertService dataConvertService) {
    List<ContactTaskVO> voList = new ArrayList<>();
    if (entities != null && !entities.isEmpty()) {
      // 收集所有需要查询的ID
      Set<Long> userIds = new HashSet<>();
      Set<Long> companyIds = new HashSet<>();
      Set<Long> contactIds = new HashSet<>();
      Set<Long> opportunityIds = new HashSet<>();

      for (ContactTaskEntity entity : entities) {
        if (entity.getCreatorId() != null) userIds.add(entity.getCreatorId());
        if (entity.getAssigneeId() != null) userIds.add(entity.getAssigneeId());
        if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
        if (entity.getContactId() != null) contactIds.add(entity.getContactId());
        if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
      }

      // 使用 DataConvertService 批量查询
      Map<Long, String> userNameMap = dataConvertService.getUserNames(userIds);
      Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
      Map<Long, String> contactNameMap = dataConvertService.getContactNames(contactIds);
      Map<Long, String> opportunityNameMap = dataConvertService.getOpportunityNames(opportunityIds);

      // 构建VO列表
      for (ContactTaskEntity entity : entities) {
        voList.add(
            convertToVOWithMaps(
                entity, userNameMap, companyNameMap, contactNameMap, opportunityNameMap));
      }
    }

    return voList;
  }

  /** 将Entity转换为VO，使用批量查询结果（性能优化版本） */
  private static ContactTaskVO convertToVOWithMaps(
      ContactTaskEntity entity,
      Map<Long, String> userNameMap,
      Map<Long, String> companyNameMap,
      Map<Long, String> contactNameMap,
      Map<Long, String> opportunityNameMap) {
    ContactTaskVO vo = new ContactTaskVO();

    // 复制基础字段（排除 status 和 priority，因为类型不匹配）
    vo.setId(entity.getId());
    vo.setTaskTitle(entity.getTaskTitle());
    vo.setCompanyId(entity.getCompanyId());
    vo.setContactId(entity.getContactId());
    vo.setOpportunityId(entity.getOpportunityId());
    vo.setTaskType(entity.getTaskType());
    vo.setTaskContent(entity.getTaskContent());
    vo.setAssigneeId(entity.getAssigneeId());
    vo.setCreatorId(entity.getCreatorId());

    // 设置创建人姓名
    vo.setCreatorName(userNameMap.get(entity.getCreatorId()));

    // 设置执行人姓名
    vo.setAssigneeName(userNameMap.get(entity.getAssigneeId()));

    // 设置指派人ID和姓名
    vo.setAssignerId(entity.getAssignerId());
    vo.setAssignerName(userNameMap.get(entity.getAssignerId()));

    // 设置公司名称
    vo.setCompanyName(companyNameMap.get(entity.getCompanyId()));

    // 设置联系人姓名
    vo.setContactName(contactNameMap.get(entity.getContactId()));

    // 设置销售机会标题
    vo.setOpportunityTitle(opportunityNameMap.get(entity.getOpportunityId()));

    // 设置优先级（直接设置为字符串）
    vo.setPriority(ContactTaskQuerySupport.convertPriorityToString(entity.getPriority()));

    // 设置状态（直接设置为字符串）
    vo.setStatus(ContactTaskQuerySupport.convertStatusToString(entity.getStatus()));

    // 设置时间字段（批量转换版本此前遗漏，导致详情时间为空）
    vo.setStartTime(
        entity.getStartTime() != null
            ? entity
                .getStartTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);
    vo.setEndTime(
        entity.getEndTime() != null
            ? entity
                .getEndTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);
    vo.setCreateTime(
        entity.getCreateTime() != null
            ? entity
                .getCreateTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);
    vo.setUpdateTime(
        entity.getUpdateTime() != null
            ? entity
                .getUpdateTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);

    return vo;
  }

  /**
   * 批量组装协助人列表（申请人可见全部；协助人仅可见指派给自己的；其他人不展示）
   *
   * @param voList 任务VO列表
   * @param currentId 当前用户ID
   */
  static void fillAssistUsers(
      AssistRequestService assistRequestService, List<ContactTaskVO> voList, Long currentId) {
    if (voList != null && !voList.isEmpty()) {
      List<Long> recordIds = voList.stream().map(ContactTaskVO::getId).toList();
      List<AssistVO> allAssists =
          assistRequestService.listAssistsByRecords(ModelName.CONTACT_TASK, recordIds);
      if (allAssists.isEmpty()) {
        voList.forEach(vo -> vo.setAssistUsers(Collections.emptyList()));
      } else {
        Map<Long, List<AssistVO>> byRecord =
            allAssists.stream()
                .collect(java.util.stream.Collectors.groupingBy(AssistVO::getRecordId));
        for (ContactTaskVO vo : voList) {
          List<AssistVO> list = byRecord.getOrDefault(vo.getId(), Collections.emptyList());
          if (list.isEmpty()) {
            vo.setAssistUsers(Collections.emptyList());
          } else {
            vo.setAssistUsers(
                assistRequestService.getVisibleAssists(
                    ModelName.CONTACT_TASK, vo.getId(), currentId));
          }
        }
      }
    }
  }
}
