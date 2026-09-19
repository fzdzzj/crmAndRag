package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.entity.PermissionsEntity;
import lombok.Data;
import org.springframework.beans.BeanUtils;

/** 权限VO */
@Data
public class PermissionVO {
  /** 权限ID */
  private Long id;

  /** 权限名字 */
  private String permissionsName;

  /** 权限描述 */
  private String permissionsDesc;

  /**
   * 从Entity创建VO
   *
   * @param entity 权限实体
   * @return PermissionVO
   */
  public static PermissionVO fromEntity(PermissionsEntity entity) {
    if (entity == null) {
      return null;
    }

    PermissionVO vo = new PermissionVO();
    vo.setId(entity.getId());
    vo.setPermissionsName(entity.getPermissionsName());
    vo.setPermissionsDesc(entity.getPermissionsDesc());

    return vo;
  }

  public PermissionVO(PermissionsEntity permission) {
    BeanUtils.copyProperties(permission, this);
  }

  public PermissionVO() {
    super();
  }
}
