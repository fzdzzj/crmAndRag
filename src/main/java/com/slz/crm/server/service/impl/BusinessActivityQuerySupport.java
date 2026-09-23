package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.BusinessActivityQueryDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 商业活动查询条件构建支持类：纯静态、无状态，依赖经参数传入。 */
final class BusinessActivityQuerySupport {

  private BusinessActivityQuerySupport() {}

  /**
   * 构建查询包装器
   *
   * @param queryDTO 查询条件
   * @param userMapper 用户 Mapper（创建人名称反查与可见性过滤）
   * @param salesOpportunityMapper 商机 Mapper（商机名称反查）
   * @param businessActivityUserMapper 活动参与人 Mapper（可见性过滤）
   * @return 查询包装器
   */
  static LambdaQueryWrapper<BusinessActivityEntity> getQueryWrapper(
      BusinessActivityQueryDTO queryDTO,
      UserMapper userMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      BusinessActivityUserMapper businessActivityUserMapper) {
    LambdaQueryWrapper<BusinessActivityEntity> queryWrapper = new LambdaQueryWrapper<>();

    // 按活动标题查询
    if (queryDTO.getActivityTitle() != null && !queryDTO.getActivityTitle().isEmpty()) {
      queryWrapper.like(BusinessActivityEntity::getActivityTitle, queryDTO.getActivityTitle());
    }
    // 按活动类型查询
    if (queryDTO.getActivityType() != null && !queryDTO.getActivityType().isEmpty()) {
      queryWrapper.eq(BusinessActivityEntity::getActivityType, queryDTO.getActivityType());
    }
    // 按活动内容查询
    if (queryDTO.getActivityContent() != null && !queryDTO.getActivityContent().isEmpty()) {
      queryWrapper.like(BusinessActivityEntity::getActivityContent, queryDTO.getActivityContent());
    }
    // 创建人名称模糊搜索
    if (queryDTO.getCreatorName() != null && !queryDTO.getCreatorName().isEmpty()) {
      applyCreatorNameFilter(queryWrapper, userMapper, queryDTO.getCreatorName());
    }
    // 销售机会名称模糊搜索
    if (queryDTO.getOpportunityName() != null && !queryDTO.getOpportunityName().isEmpty()) {
      applyOpportunityNameFilter(
          queryWrapper, salesOpportunityMapper, queryDTO.getOpportunityName());
    }
    // 备注模糊搜索
    if (queryDTO.getRemark() != null && !queryDTO.getRemark().isEmpty()) {
      queryWrapper.like(BusinessActivityEntity::getRemark, queryDTO.getRemark());
    }
    applyTimeRangeFilters(queryWrapper, queryDTO);
    // 活动时长搜索
    if (queryDTO.getActivityDuration() != null) {
      queryWrapper.eq(BusinessActivityEntity::getActivityDuration, queryDTO.getActivityDuration());
    }

    // 默认按创建时间倒序
    queryWrapper.orderByDesc(BusinessActivityEntity::getCreateTime);

    // 普通列表只按正常业务关系过滤；协助人从协助页进入单条详情，不扩大列表范围
    applyVisibilityFilter(queryWrapper, userMapper, businessActivityUserMapper);

    return queryWrapper;
  }

  /** 创建人名称模糊搜索：先反查用户 ID 集，查不到任何人时固定条件恒假 */
  private static void applyCreatorNameFilter(
      LambdaQueryWrapper<BusinessActivityEntity> queryWrapper,
      UserMapper userMapper,
      String creatorName) {
    Set<Long> creatorIds = userMapper.selectUserIdsByUserName(creatorName);
    if (!creatorIds.isEmpty()) {
      queryWrapper.in(BusinessActivityEntity::getCreatorId, creatorIds);
    } else {
      queryWrapper.eq(BusinessActivityEntity::getCreatorId, -1);
    }
  }

  /** 销售机会名称模糊搜索：先反查商机 ID 集，查不到任何商机时固定条件恒假 */
  private static void applyOpportunityNameFilter(
      LambdaQueryWrapper<BusinessActivityEntity> queryWrapper,
      SalesOpportunityMapper salesOpportunityMapper,
      String opportunityName) {
    Set<Long> opportunityIds = salesOpportunityMapper.selectOpportunityIdsByName(opportunityName);
    if (!opportunityIds.isEmpty()) {
      queryWrapper.in(BusinessActivityEntity::getOpportunityId, opportunityIds);
    } else {
      queryWrapper.eq(BusinessActivityEntity::getOpportunityId, -1);
    }
  }

  /** 创建时间与活动日期两个范围条件（两者同时给出上下界才生效） */
  private static void applyTimeRangeFilters(
      LambdaQueryWrapper<BusinessActivityEntity> queryWrapper, BusinessActivityQueryDTO queryDTO) {
    // 创建时间范围搜索
    if (queryDTO.getMinCreateTime() != null && queryDTO.getMaxCreateTime() != null) {
      queryWrapper.between(
          BusinessActivityEntity::getCreateTime,
          queryDTO.getMinCreateTime(),
          queryDTO.getMaxCreateTime());
    }
    // 活动日期范围搜索
    if (queryDTO.getMinActivityTime() != null && queryDTO.getMaxActivityTime() != null) {
      queryWrapper.between(
          BusinessActivityEntity::getActivityTime,
          queryDTO.getMinActivityTime(),
          queryDTO.getMaxActivityTime());
    }
  }

  /**
   * 普通列表可见性过滤：创建人/参与人可见，超级管理员豁免。 协助关系不进入普通列表范围，协助人只能从协助页访问关联单条详情。
   *
   * @param queryWrapper 查询包装器
   */
  private static void applyVisibilityFilter(
      LambdaQueryWrapper<BusinessActivityEntity> queryWrapper,
      UserMapper userMapper,
      BusinessActivityUserMapper businessActivityUserMapper) {
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    if (isAdmin) {
      return;
    }
    Set<Long> visibleIds = new HashSet<>();
    java.util.List<BusinessActivityUserEntity> participated =
        businessActivityUserMapper.selectList(
            new LambdaQueryWrapper<BusinessActivityUserEntity>()
                .eq(BusinessActivityUserEntity::getUserId, currentId));
    participated.forEach(rel -> visibleIds.add(rel.getActivityId()));
    queryWrapper.and(
        w ->
            w.eq(BusinessActivityEntity::getCreatorId, currentId)
                .or()
                .in(!visibleIds.isEmpty(), BusinessActivityEntity::getId, visibleIds));
  }
}
