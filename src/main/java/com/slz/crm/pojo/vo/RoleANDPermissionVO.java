package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.entity.RoleEntity;
import lombok.Data;
import org.springframework.beans.BeanUtils;

@Data
public class RoleANDPermissionVO {
  /** 角色ID */
  private Long id;

  /** 角色名称 */
  private String roleName;

  /** 角色描述 */
  private String roleDesc;

  /** 拥有权限（分组：主权限 + 子权限） */
  private PermissionGroupedVO permissions;

  public static RoleANDPermissionVO formEntity(RoleEntity role, PermissionGroupedVO permissions) {

    RoleANDPermissionVO vo = new RoleANDPermissionVO();
    BeanUtils.copyProperties(role, vo);
    vo.setPermissions(permissions);

    return vo;
  }

  public static RoleANDPermissionVO fromEntity(RoleVO role, PermissionGroupedVO permissions) {
    RoleANDPermissionVO vo = new RoleANDPermissionVO();
    BeanUtils.copyProperties(role, vo);
    vo.setPermissions(permissions);

    return vo;
  }
}
