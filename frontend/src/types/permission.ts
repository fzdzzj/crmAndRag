// 权限名称：来源已从「冻结快照字面量联合」放宽为 string。
// 快照（utils/tutorial/mockPermissionCatalog.ts）只是演示数据，用它推导类型等于用过期数据换假安全；
// 运行时真相由 `GET /permission/list`（后端 PermissionOperates 枚举）提供。
export type PermissionName = string;

// 权限名称数组类型
export type PermissionNameArray = Array<PermissionName>;

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
