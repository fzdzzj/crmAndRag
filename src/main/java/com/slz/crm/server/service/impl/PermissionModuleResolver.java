package com.slz.crm.server.service.impl;

/**
 * {@link PermissionServiceImpl} 的权限模块名解析单元（只读、确定性）。
 *
 * <p>tighten-pmd-violations 分片
 * B：把模块名从权限前缀（customer:/sales:/finance:/task:/report:/system:/project:）映射为 展示名。行为等价于原内联实现，仅作为类级
 * NCSS 下沉的独立单元。
 */
final class PermissionModuleResolver {

  private PermissionModuleResolver() {}

  /** 根据权限名称前缀解析展示模块名；未知前缀归入「其他模块权限」。 */
  static String resolve(String permissionName) {
    String result;
    if (permissionName.startsWith("customer:")) {
      result = "客户管理模块权限";
    } else if (permissionName.startsWith("sales:")) {
      result = "销售管理模块权限";
    } else if (permissionName.startsWith("finance:")) {
      result = "财务管理模块权限";
    } else if (permissionName.startsWith("task:")) {
      result = "联络任务模块权限";
    } else if (permissionName.startsWith("report:")) {
      result = "统计报表模块权限";
    } else if (permissionName.startsWith("system:")) {
      result = "权限管理模块权限";
    } else if (permissionName.startsWith("project:")) {
      result = "隐私信息查看权限";
    } else {
      result = "其他模块权限";
    }
    return result;
  }
}
