package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistRequestService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 商业活动 VO 装配支持类：批量查询收集与 VO 组装簇，纯静态、无状态，依赖经参数传入。 */
final class BusinessActivityVoAssembler {

  private BusinessActivityVoAssembler() {}

  /**
   * 分页查询结果的批量 VO 装配（businessActivityQuery 与 getAllActivity 共用）： 收集 ID → 批量查询 → 按活动填充联系人/用户 →
   * 批量组装协助人。
   *
   * @param records 当前页活动实体
   */
  static List<BusinessActivityVO> assembleVoList(
      List<BusinessActivityEntity> records,
      UserMapper userMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      BusinessActivityContactMapper businessActivityContactMapper,
      BusinessActivityUserMapper businessActivityUserMapper,
      CustomerContactMapper customerContactMapper,
      AssistRequestService assistRequestService) {
    // 收集所有需要查询的ID
    Set<Long> userIds = new HashSet<>();
    Set<Long> opportunityIds = new HashSet<>();
    List<Long> activityIds = new ArrayList<>();

    for (BusinessActivityEntity entity : records) {
      if (entity.getCreatorId() != null) userIds.add(entity.getCreatorId());
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
      activityIds.add(entity.getId());
    }

    // 批量查询
    Map<Long, UserEntity> userMap =
        userIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    Map<Long, SalesOpportunityEntity> opportunityMap =
        opportunityIds.isEmpty()
            ? Collections.emptyMap()
            : salesOpportunityMapper.selectBatchIds(opportunityIds).stream()
                .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));

    // 批量查询联系人和用户关联
    List<BusinessActivityContactEntity> contactRelations =
        activityIds.isEmpty()
            ? Collections.emptyList()
            : businessActivityContactMapper.selectList(
                new LambdaQueryWrapper<BusinessActivityContactEntity>()
                    .in(BusinessActivityContactEntity::getActivityId, activityIds));

    List<BusinessActivityUserEntity> userRelations =
        activityIds.isEmpty()
            ? Collections.emptyList()
            : businessActivityUserMapper.selectByActivityIds(activityIds);

    // 收集联系人ID和用户ID，并按活动ID分组
    Map<Long, List<BusinessActivityContactEntity>> contactRelationMap =
        contactRelations.stream()
            .collect(Collectors.groupingBy(BusinessActivityContactEntity::getActivityId));

    Map<Long, List<BusinessActivityUserEntity>> userRelationMap =
        userRelations.stream()
            .collect(Collectors.groupingBy(BusinessActivityUserEntity::getActivityId));

    Set<Long> contactIds =
        contactRelations.stream()
            .map(BusinessActivityContactEntity::getContactId)
            .collect(Collectors.toSet());

    Set<Long> activityUserIds =
        userRelations.stream()
            .map(BusinessActivityUserEntity::getUserId)
            .collect(Collectors.toSet());

    // 批量查询联系人和用户信息
    Map<Long, CustomerContactEntity> contactMap =
        contactIds.isEmpty()
            ? Collections.emptyMap()
            : customerContactMapper.selectBatchIds(contactIds).stream()
                .collect(Collectors.toMap(CustomerContactEntity::getId, Function.identity()));

    // 复用已查询的用户信息，添加活动中关联的用户
    Map<Long, UserEntity> activityUserMap =
        mergeActivityUserMap(userMapper, userMap, activityUserIds);

    // 构建VO列表
    List<BusinessActivityVO> voList =
        records.stream()
            .map(
                entity -> {
                  String[] names =
                      getBusinessActivityNamesWithMaps(entity, userMap, opportunityMap);
                  BusinessActivityVO vo = BusinessActivityVO.fromEntity(entity, names[0], names[1]);

                  // 填充联系人信息
                  fillActivityContacts(
                      vo,
                      contactRelationMap.getOrDefault(entity.getId(), Collections.emptyList()),
                      contactMap);

                  // 填充用户信息
                  fillActivityUsers(
                      vo,
                      userRelationMap.getOrDefault(entity.getId(), Collections.emptyList()),
                      activityUserMap);

                  return vo;
                })
            .toList();

    // 批量组装协助人（按可见性过滤）
    fillAssistUsers(assistRequestService, voList, BaseUnit.getCurrentId());
    return voList;
  }

  /** 单条详情的 VO 装配：真实查询创建人与商机名称（不能用空 map，否则详情永远为空）并组装可见协助人。 */
  static BusinessActivityVO buildDetailVo(
      BusinessActivityEntity entity,
      Long id,
      Long currentId,
      UserMapper userMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      AssistRequestService assistRequestService) {
    Map<Long, UserEntity> userMap =
        entity.getCreatorId() == null
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(Collections.singletonList(entity.getCreatorId())).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    Map<Long, SalesOpportunityEntity> opportunityMap =
        entity.getOpportunityId() == null
            ? Collections.emptyMap()
            : salesOpportunityMapper
                .selectBatchIds(Collections.singletonList(entity.getOpportunityId()))
                .stream()
                .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));

    String[] names = getBusinessActivityNamesWithMaps(entity, userMap, opportunityMap);
    BusinessActivityVO vo = BusinessActivityVO.fromEntity(entity, names[0], names[1]);
    vo.setAssistUsers(
        assistRequestService.getVisibleAssists(ModelName.BUSINESS_ACTIVITY, id, currentId));
    return vo;
  }

  /** 获取商业活动相关的名称（批量查询版本） */
  private static String[] getBusinessActivityNamesWithMaps(
      BusinessActivityEntity entity,
      Map<Long, UserEntity> userMap,
      Map<Long, SalesOpportunityEntity> opportunityMap) {
    String creatorName = null;
    String opportunityName = null;

    // 创建者名称
    if (entity.getCreatorId() != null) {
      UserEntity userEntity = userMap.get(entity.getCreatorId());
      if (userEntity != null) {
        creatorName = userEntity.getRealName();
      }
    }

    // 销售机会名称
    if (entity.getOpportunityId() != null) {
      SalesOpportunityEntity opportunityEntity = opportunityMap.get(entity.getOpportunityId());
      if (opportunityEntity != null) {
        opportunityName = opportunityEntity.getOpportunityName();
      }
    }

    return new String[] {creatorName, opportunityName};
  }

  /** 复用已查询的用户信息，合并活动中关联用户的批量查询结果 */
  private static Map<Long, UserEntity> mergeActivityUserMap(
      UserMapper userMapper, Map<Long, UserEntity> userMap, Set<Long> activityUserIds) {
    Map<Long, UserEntity> activityUserMap;
    if (!activityUserIds.isEmpty()) {
      Set<Long> allUserIds = new HashSet<>(userMap.keySet());
      allUserIds.addAll(activityUserIds);
      activityUserMap =
          userMapper.selectBatchIds(allUserIds).stream()
              .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    } else {
      activityUserMap = userMap;
    }
    return activityUserMap;
  }

  /** 为单个活动 VO 填充关联联系人（含联系人姓名） */
  private static void fillActivityContacts(
      BusinessActivityVO businessActivityVO,
      List<BusinessActivityContactEntity> contacts,
      Map<Long, CustomerContactEntity> contactMap) {
    if (!contacts.isEmpty()) {
      List<BusinessActivityVO.ActivityContactVO> contactVOList =
          contacts.stream()
              .map(
                  contactRel -> {
                    BusinessActivityVO.ActivityContactVO contactVO =
                        new BusinessActivityVO.ActivityContactVO();
                    contactVO.setContactId(contactRel.getContactId());
                    CustomerContactEntity contactEntity = contactMap.get(contactRel.getContactId());
                    if (contactEntity != null) {
                      contactVO.setContactName(contactEntity.getName());
                    }
                    contactVO.setContactRole(contactRel.getContactRole());
                    return contactVO;
                  })
              .toList();
      businessActivityVO.setContacts(contactVOList);
    }
  }

  /** 为单个活动 VO 填充关联用户（含用户姓名） */
  private static void fillActivityUsers(
      BusinessActivityVO businessActivityVO,
      List<BusinessActivityUserEntity> users,
      Map<Long, UserEntity> activityUserMap) {
    if (!users.isEmpty()) {
      List<BusinessActivityVO.ActivityUserVO> userVOList =
          users.stream()
              .map(
                  userRel -> {
                    BusinessActivityVO.ActivityUserVO userVO =
                        new BusinessActivityVO.ActivityUserVO();
                    userVO.setUserId(userRel.getUserId());
                    UserEntity userEntity = activityUserMap.get(userRel.getUserId());
                    if (userEntity != null) {
                      userVO.setUserName(userEntity.getRealName());
                    }
                    userVO.setUserRole(userRel.getUserRole());
                    return userVO;
                  })
              .toList();
      businessActivityVO.setUsers(userVOList);
    }
  }

  /**
   * 批量组装协助人列表（申请人可见全部；协助人仅可见指派给自己的；其他人不展示）
   *
   * @param voList 活动VO列表
   * @param currentId 当前用户ID
   */
  private static void fillAssistUsers(
      AssistRequestService assistRequestService, List<BusinessActivityVO> voList, Long currentId) {
    if (voList != null && !voList.isEmpty()) {
      List<Long> recordIds = voList.stream().map(BusinessActivityVO::getId).toList();
      List<AssistVO> allAssists =
          assistRequestService.listAssistsByRecords(ModelName.BUSINESS_ACTIVITY, recordIds);
      if (allAssists.isEmpty()) {
        voList.forEach(vo -> vo.setAssistUsers(Collections.emptyList()));
      } else {
        Map<Long, List<AssistVO>> byRecord =
            allAssists.stream().collect(Collectors.groupingBy(AssistVO::getRecordId));
        for (BusinessActivityVO vo : voList) {
          List<AssistVO> list = byRecord.getOrDefault(vo.getId(), Collections.emptyList());
          if (list.isEmpty()) {
            vo.setAssistUsers(Collections.emptyList());
          } else {
            vo.setAssistUsers(
                assistRequestService.getVisibleAssists(
                    ModelName.BUSINESS_ACTIVITY, vo.getId(), currentId));
          }
        }
      }
    }
  }
}
