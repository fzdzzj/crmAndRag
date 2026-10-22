package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.PermissionModule;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PermissionServiceImpl} 的权限模块名解析单元（只读、确定性）。
 *
 * <p>close-permission-module-gap：模块归属的唯一事实源已收敛到 {@link PermissionModule}（按权限 ID 号段判定），
 * 本类只做委托，不再持有任何前缀 / 名称判断。原按 {@code customer:} / {@code sales:} / … 英文冒号 前缀匹配的实现自 {@code
 * PermissionSyncRunner} 改写为枚举裸名起即死代码，已删除。
 */
final class PermissionModuleResolver {

  private PermissionModuleResolver() {}

  /**
   * 有序模块展示名：顺序与 {@link PermissionModule} 声明顺序一致，「其他模块权限」恒末位。
   *
   * @return 模块展示名列表
   */
  static List<String> moduleNames() {
    List<String> names = new ArrayList<>();
    for (PermissionModule module : PermissionModule.values()) {
      names.add(module.displayName());
    }
    return names;
  }

  /**
   * 根据权限 ID 解析展示模块名；枚举中不存在的 ID 归入「其他模块权限」。
   *
   * @param permissionId 权限 ID，允许 null
   * @return 模块展示名
   */
  static String resolve(Long permissionId) {
    return PermissionModule.fromPermissionId(permissionId).displayName();
  }
}
