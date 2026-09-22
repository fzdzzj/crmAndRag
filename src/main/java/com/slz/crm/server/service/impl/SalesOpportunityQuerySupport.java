package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商机查询条件构建与 VO 批量装配支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * SalesOpportunityServiceImpl，行为等价）。纯静态、无状态，mapper 与数据转换服务经参数传入。
 */
final class SalesOpportunityQuerySupport {

  private SalesOpportunityQuerySupport() {}

  /**
   * 构造自定义销售机会查询条件
   *
   * @param queryDTO 查询条件DTO
   * @param customerCompanyMapper 公司 mapper（公司名称反查 ID）
   * @param customerContactMapper 联系人 mapper（联系人名称反查 ID）
   * @param userMapper 用户 mapper（用户名反查 ID）
   * @return 查询条件
   */
  static LambdaQueryWrapper<SalesOpportunityEntity> getQueryWrapper(
      SalesOpportunityQueryDTO queryDTO,
      CustomerCompanyMapper customerCompanyMapper,
      CustomerContactMapper customerContactMapper,
      UserMapper userMapper) {
    LambdaQueryWrapper<SalesOpportunityEntity> queryWrapper = new LambdaQueryWrapper<>();

    // 销售机会名称模糊搜索
    if (queryDTO.getOpportunityName() != null && !queryDTO.getOpportunityName().isEmpty()) {
      queryWrapper.like(SalesOpportunityEntity::getOpportunityName, queryDTO.getOpportunityName());
    }
    // 公司名称模糊搜索
    if (queryDTO.getCompanyName() != null && !queryDTO.getCompanyName().isEmpty()) {
      applyIdSetFilter(
          queryWrapper,
          customerCompanyMapper.selectCompanyIdsByName(queryDTO.getCompanyName()),
          SalesOpportunityEntity::getCompanyId);
    }
    // 联系人名称模糊搜索
    if (queryDTO.getContactName() != null && !queryDTO.getContactName().isEmpty()) {
      applyIdSetFilter(
          queryWrapper,
          customerContactMapper.selectContactIdsByName(queryDTO.getContactName()),
          SalesOpportunityEntity::getContactId);
    }
    // 负责人/审批人/创建人名称模糊搜索
    applyUserRelatedFilters(queryWrapper, queryDTO, userMapper);
    // 销售机会阶段搜索
    applyStageFilter(queryWrapper, queryDTO.getStage());
    // 销售机会描述模糊搜索
    if (queryDTO.getDescription() != null && !queryDTO.getDescription().isEmpty()) {
      queryWrapper.like(SalesOpportunityEntity::getDescription, queryDTO.getDescription());
    }
    // 来源模糊搜索
    if (queryDTO.getSource() != null && !queryDTO.getSource().isEmpty()) {
      queryWrapper.like(SalesOpportunityEntity::getSource, queryDTO.getSource());
    }
    // 金额/创建时间/关闭日期范围搜索
    applyRangeFilters(queryWrapper, queryDTO);
    // 负责人用户ID精确匹配
    if (queryDTO.getOwnerId() != null) {
      queryWrapper.eq(SalesOpportunityEntity::getOwnerId, queryDTO.getOwnerId());
    }
    // 负责人用户名模糊搜索
    if (queryDTO.getOwnerUserName() != null && !queryDTO.getOwnerUserName().isEmpty()) {
      applyIdSetFilter(
          queryWrapper,
          userMapper.selectUserIdsByUserName(queryDTO.getOwnerUserName()),
          SalesOpportunityEntity::getOwnerId);
    }
    return queryWrapper;
  }

  /** 批量收集实体关联 ID 并查询名称映射后构建 VO 列表（拆自分页查询的重复块，行为等价）。 */
  static List<SalesOpportunityVO> buildVoRecords(
      List<SalesOpportunityEntity> records, DataConvertService dataConvertService) {
    // 批量收集ID
    Set<Long> allUserIds = new HashSet<>();
    Set<Long> companyIds = new HashSet<>();
    Set<Long> contactIds = new HashSet<>();
    for (SalesOpportunityEntity entity : records) {
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
    for (SalesOpportunityEntity entity : records) {
      SalesOpportunityVO vo =
          SalesOpportunityVO.fromEntity(
              entity,
              companyNameMap.get(entity.getCompanyId()),
              contactNameMap.get(entity.getContactId()),
              userNameMap.get(entity.getOwnerId()),
              userNameMap.get(entity.getCreatorId()),
              userNameMap.get(entity.getApproverId()));
      salesOpportunityVOs.add(vo);
    }
    return salesOpportunityVOs;
  }

  /**
   * 负责人/审批人/创建人名称模糊搜索（拆自 getQueryWrapper，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param queryDTO 查询条件DTO
   * @param userMapper 用户 mapper
   */
  private static void applyUserRelatedFilters(
      LambdaQueryWrapper<SalesOpportunityEntity> queryWrapper,
      SalesOpportunityQueryDTO queryDTO,
      UserMapper userMapper) {
    // 负责人名称模糊搜索
    if (queryDTO.getOwnerName() != null && !queryDTO.getOwnerName().isEmpty()) {
      applyIdSetFilter(
          queryWrapper,
          userMapper.selectUserIdsByUserName(queryDTO.getOwnerName()),
          SalesOpportunityEntity::getOwnerId);
    }
    // 审批人名称模糊搜索
    if (queryDTO.getApproverName() != null && !queryDTO.getApproverName().isEmpty()) {
      applyIdSetFilter(
          queryWrapper,
          userMapper.selectUserIdsByUserName(queryDTO.getApproverName()),
          SalesOpportunityEntity::getApproverId);
    }
    // 创建人名称模糊搜索
    if (queryDTO.getCreatorName() != null && !queryDTO.getCreatorName().isEmpty()) {
      applyIdSetFilter(
          queryWrapper,
          userMapper.selectUserIdsByUserName(queryDTO.getCreatorName()),
          SalesOpportunityEntity::getCreatorId);
    }
  }

  /**
   * ID 集合过滤：非空用 in，否则置 -1 保证查不到（拆自 getQueryWrapper，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param ids 解析出的 ID 集合
   * @param column 实体列
   */
  private static void applyIdSetFilter(
      LambdaQueryWrapper<SalesOpportunityEntity> queryWrapper,
      Set<Long> ids,
      SFunction<SalesOpportunityEntity, ?> column) {
    if (ids != null && !ids.isEmpty()) {
      queryWrapper.in(column, ids);
    } else {
      queryWrapper.eq(column, -1);
    }
  }

  /**
   * 销售机会阶段过滤：合法阶段精确匹配，非法阶段置 -1（拆自 getQueryWrapper，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param stage 阶段值
   */
  private static void applyStageFilter(
      LambdaQueryWrapper<SalesOpportunityEntity> queryWrapper, Integer stage) {
    if (stage != null) {
      if (stage >= 0 && stage <= 5) {
        queryWrapper.eq(SalesOpportunityEntity::getStage, stage);
      } else {
        queryWrapper.eq(SalesOpportunityEntity::getStage, -1);
      }
    }
  }

  /**
   * 金额/创建时间/关闭日期范围过滤（拆自 getQueryWrapper，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param queryDTO 查询条件DTO
   */
  private static void applyRangeFilters(
      LambdaQueryWrapper<SalesOpportunityEntity> queryWrapper, SalesOpportunityQueryDTO queryDTO) {
    // 金额范围搜索
    if (queryDTO.getMinAmount() != null && queryDTO.getMaxAmount() != null) {
      queryWrapper.between(
          SalesOpportunityEntity::getAmount, queryDTO.getMinAmount(), queryDTO.getMaxAmount());
    }
    // 创建时间范围搜索
    if (queryDTO.getMinCreateTime() != null && queryDTO.getMaxCreateTime() != null) {
      queryWrapper.between(
          SalesOpportunityEntity::getCreateTime,
          queryDTO.getMinCreateTime(),
          queryDTO.getMaxCreateTime());
    }
    // 关闭日期范围搜索
    if (queryDTO.getMinExpectedCloseDate() != null && queryDTO.getMaxExpectedCloseDate() != null) {
      queryWrapper.between(
          SalesOpportunityEntity::getExpectedCloseDate,
          queryDTO.getMinExpectedCloseDate(),
          queryDTO.getMaxExpectedCloseDate());
    }
  }
}
