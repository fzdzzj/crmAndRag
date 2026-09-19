package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.TageRoleBindingEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 标签角色绑定 Mapper */
@Mapper
public interface TageRoleBindingMapper extends BaseMapper<TageRoleBindingEntity> {

  /**
   * 查询角色绑定的标签ID列表
   *
   * @param roleId 角色ID
   * @return 标签ID列表
   */
  List<Long> selectTageIdsByRoleId(@Param("roleId") Long roleId);

  /**
   * 查询角色绑定的唯一标签ID（一对一关系）
   *
   * @param roleId 角色ID
   * @return 标签ID， 如果没有绑定则返回null
   */
  Long selectTageIdByRoleId(@Param("roleId") Long roleId);
}
