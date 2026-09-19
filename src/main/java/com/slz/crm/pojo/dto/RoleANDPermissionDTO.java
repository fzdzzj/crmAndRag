package com.slz.crm.pojo.dto;

import java.util.List;
import lombok.Data;

@Data
public class RoleANDPermissionDTO {
  /** 角色ID */
  private Integer roleId;

  /** 权限ID集合 */
  private List<Integer> permissionIds;

  /** 是否新增权限（true: 新增权限，false: 删除权限） */
  private Boolean isAdd;
}
