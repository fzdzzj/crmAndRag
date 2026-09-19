package com.slz.crm.server.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 统一数据转换服务 提供ID转名称的通用方法，支持批量转换以优化性能（避免N+1查询问题） */
public interface DataConvertService {

  // ===== 单个转换（内部走缓存） =====

  /**
   * 根据用户ID获取用户真实姓名
   *
   * @param userId 用户ID
   * @return 用户真实姓名，不存在则返回null
   */
  String getUserName(Long userId);

  /**
   * 根据部门ID获取部门名称
   *
   * @param deptId 部门ID
   * @return 部门名称，不存在则返回null
   */
  String getDeptName(Long deptId);

  /**
   * 根据公司ID获取公司名称
   *
   * @param companyId 公司ID
   * @return 公司名称，不存在则返回null
   */
  String getCompanyName(Long companyId);

  /**
   * 根据联系人ID获取联系人姓名
   *
   * @param contactId 联系人ID
   * @return 联系人姓名，不存在则返回null
   */
  String getContactName(Long contactId);

  /**
   * 根据商机ID获取商机名称
   *
   * @param opportunityId 商机ID
   * @return 商机名称，不存在则返回null
   */
  String getOpportunityName(Long opportunityId);

  /**
   * 根据合同ID获取合同名称
   *
   * @param contractId 合同ID
   * @return 合同名称，不存在则返回null
   */
  String getContractName(Long contractId);

  // ===== 批量转换（核心，避免N+1） =====

  /**
   * 批量获取用户ID→姓名映射
   *
   * @param userIds 用户ID集合
   * @return ID→姓名映射
   */
  Map<Long, String> getUserNames(Collection<Long> userIds);

  /**
   * 批量获取部门ID→名称映射
   *
   * @param deptIds 部门ID集合
   * @return ID→名称映射
   */
  Map<Long, String> getDeptNames(Collection<Long> deptIds);

  /**
   * 批量获取公司ID→名称映射
   *
   * @param companyIds 公司ID集合
   * @return ID→名称映射
   */
  Map<Long, String> getCompanyNames(Collection<Long> companyIds);

  /**
   * 批量获取联系人ID→姓名映射
   *
   * @param contactIds 联系人ID集合
   * @return ID→姓名映射
   */
  Map<Long, String> getContactNames(Collection<Long> contactIds);

  /**
   * 批量获取商机ID→名称映射
   *
   * @param opportunityIds 商机ID集合
   * @return ID→名称映射
   */
  Map<Long, String> getOpportunityNames(Collection<Long> opportunityIds);

  /**
   * 批量获取合同ID→名称映射
   *
   * @param contractIds 合同ID集合
   * @return ID→名称映射
   */
  Map<Long, String> getContractNames(Collection<Long> contractIds);

  // ===== 通用辅助 =====

  /**
   * 从实体集合中提取指定字段的不重复ID列表
   *
   * @param entities 实体集合
   * @param idExtractor ID提取函数
   * @return 不重复的ID列表
   */
  <T> List<Long> collectIds(List<T> entities, Function<T, Long> idExtractor);
}
