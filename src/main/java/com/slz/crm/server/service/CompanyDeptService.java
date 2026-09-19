package com.slz.crm.server.service;

import com.slz.crm.pojo.dto.CompanyDeptDTO;
import com.slz.crm.pojo.entity.CompanyDeptEntity;
import java.util.List;

/**
 * 部门主数据服务
 *
 * @author CRM Team
 */
public interface CompanyDeptService {

  /**
   * 查询部门列表
   *
   * @param groupId 所属集团ID（可空，空则查询全部）
   * @param keyword 部门名称模糊关键字（可空）
   * @param status 状态过滤（可空；1-启用，0-停用）
   * @return 部门列表
   */
  List<CompanyDeptEntity> list(Long groupId, String keyword, Integer status);

  /**
   * 新增部门（集团下名称去重）
   *
   * @param dto 部门 DTO
   * @return 创建后的部门（含ID）
   */
  CompanyDeptEntity add(CompanyDeptDTO dto);

  /**
   * 编辑部门（集团内名称查重，排除自身）
   *
   * @param dto 部门 DTO（需携带 id）
   * @return 更新后的部门
   */
  CompanyDeptEntity update(CompanyDeptDTO dto);

  /**
   * 启用/停用部门
   *
   * @param id 部门ID
   * @param status 1-启用，0-停用
   */
  void updateStatus(Long id, Integer status);

  /**
   * 删除部门（不影响客户历史文本数据）
   *
   * @param id 部门ID
   */
  void deleteById(Long id);
}
