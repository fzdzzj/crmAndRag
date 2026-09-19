package com.slz.crm.pojo.vo;

import java.util.List;
import lombok.Data;

/** 权限分组VO - 将权限分为主权限和子权限两个列表 */
@Data
public class PermissionGroupedVO {
  /** 主权限列表（dataScopeLevel == NONE 的权限） */
  private List<PermissionVO> mainPermissions;

  /** 子权限列表（dataScopeLevel != NONE 的权限，携带 parentPermissionId） */
  private List<SubPermissionVO> subPermissions;
}
