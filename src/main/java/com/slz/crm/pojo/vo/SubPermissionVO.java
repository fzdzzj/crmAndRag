package com.slz.crm.pojo.vo;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.entity.PermissionsEntity;
import lombok.Data;

/** 子权限VO - 携带主权限ID */
@Data
public class SubPermissionVO {
  /** 权限ID */
  private Long id;

  /** 权限名字 */
  private String permissionsName;

  /** 权限描述 */
  private String permissionsDesc;

  /** 主权限ID */
  private Long parentPermissionId;

  public SubPermissionVO() {}

  public SubPermissionVO(PermissionsEntity entity, Long parentPermissionId) {
    this.id = entity.getId();
    this.permissionsName = entity.getPermissionsName();
    this.permissionsDesc = entity.getPermissionsDesc();
    this.parentPermissionId = parentPermissionId;
  }

  /**
   * 从权限实体创建子权限VO，自动从枚举中获取父权限ID
   *
   * @param entity 权限实体
   * @return SubPermissionVO
   */
  public static SubPermissionVO fromEntity(PermissionsEntity entity) {
    Long parentId = null;
    if (entity != null) {
      PermissionOperates operates = PermissionOperates.fromId(entity.getId());
      if (operates != null) {
        parentId = operates.getParentPermissionId();
      }
    }
    return entity == null ? null : new SubPermissionVO(entity, parentId);
  }
}
