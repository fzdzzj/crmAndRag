package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.entity.RoleEntity;
import lombok.Data;

/** 角色视图对象 */
@Data
public class RoleVO {
  /** 角色ID */
  private Long id;

  /** 角色名称 */
  private String roleName;

  /** 角色描述 */
  private String roleDesc;

  /**
   * 从Entity创建VO
   *
   * @param entity 角色实体
   * @return RoleVO
   */
  public static RoleVO fromEntity(RoleEntity entity) {
    if (entity == null) {
      return null;
    }

    RoleVO vo = new RoleVO();
    vo.setId(entity.getId());
    vo.setRoleName(entity.getRoleName());
    vo.setRoleDesc(entity.getRoleDesc());

    return vo;
  }
}
