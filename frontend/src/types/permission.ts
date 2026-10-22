import type { PermissionName } from './permission.gen';

// 权限名称：由后端 PermissionOperates 枚举生成的字面量联合（120 项，见 types/permission.gen.ts，勿手改）。
// 生成/校验 = com.slz.crm.unit.permission.PermissionTypeCatalogGenTest（默认校验，-Dpermission.types.update=true 重写）。
// 运行时真相仍由 `GET /permission/list` 提供；快照（utils/tutorial/mockPermissionCatalog.ts）只是演示数据，不作类型来源。
export type { PermissionModuleName, PermissionName } from './permission.gen';

/**
 * 带模块前缀的权限名：运行时 `permissions_name` 形如 `customer:CUSTOMER_QUERY_CONTACT`。
 *
 * <p>前缀自由（历史库里 `system:` / `sales:` / `customer:` 等写法并存），但枚举段必须是 {@link PermissionName}，
 * 因此把权限名写错（或后端删了常量）在编译期就报错。
 */
export type QualifiedPermissionName = `${string}:${PermissionName}`;

/** 权限名称数组类型：组件 `allow` 同时接受裸枚举名与 `模块:枚举名` 两种写法。 */
export type PermissionNameArray = Array<PermissionName | QualifiedPermissionName>;

// 后端 PermissionGroupedVO 对应的类型
export interface PermissionGrouped {
  mainPermissions?: { id?: number; permissionsName?: string; permissionsDesc?: string }[];
  subPermissions?: { id?: number; permissionsName?: string; permissionsDesc?: string; parentPermissionId?: number }[];
}

/** 从 PermissionGrouped 中提取所有权限名称 */
export function extractPermissionNames(grouped: PermissionGrouped | undefined | null): string[] {
  if (!grouped) return [];
  const names: string[] = [];
  if (Array.isArray(grouped.mainPermissions)) {
    grouped.mainPermissions.forEach((p) => { if (p.permissionsName) names.push(p.permissionsName); });
  }
  if (Array.isArray(grouped.subPermissions)) {
    grouped.subPermissions.forEach((p) => { if (p.permissionsName) names.push(p.permissionsName); });
  }
  return names;
}

/** 从 PermissionGrouped 中提取所有权限 ID */
export function extractPermissionIds(grouped: PermissionGrouped | undefined | null): number[] {
  if (!grouped) return [];
  const ids: number[] = [];
  if (Array.isArray(grouped.mainPermissions)) {
    grouped.mainPermissions.forEach((p) => { if (p.id != null) ids.push(p.id); });
  }
  if (Array.isArray(grouped.subPermissions)) {
    grouped.subPermissions.forEach((p) => { if (p.id != null) ids.push(p.id); });
  }
  return ids;
}
