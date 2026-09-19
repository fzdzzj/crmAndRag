package com.slz.crm.server.service;

import com.slz.crm.pojo.dto.SysDeptDTO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import java.util.List;

/** 部门服务 */
public interface SysDeptService {

  /**
   * 查询全部启用部门（按排序）
   *
   * @return 启用部门列表
   */
  List<SysDeptEntity> listEnabled();

  /**
   * 查询全部部门（含停用，供部门管理维护）
   *
   * @return 部门列表
   */
  List<SysDeptEntity> listAll();

  /**
   * 新增部门
   *
   * @param dto 部门信息
   * @return 是否成功
   */
  Boolean add(SysDeptDTO dto);

  /**
   * 编辑部门
   *
   * @param dto 部门信息
   * @return 是否成功
   */
  Boolean update(SysDeptDTO dto);

  /**
   * 删除部门（有用户引用的部门禁止删除）
   *
   * @param id 部门ID
   * @return 是否成功
   */
  Boolean delete(Long id);
}
