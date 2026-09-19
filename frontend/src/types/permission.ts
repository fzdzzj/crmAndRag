import type PERMISSIONS from '@/constants/premission';

// 从权限常量中提取所有权限名称
type PermissionCategory = typeof PERMISSIONS;
type CategoryValue = PermissionCategory[keyof PermissionCategory];

// 从 mainPermissions 和 subPermissions 中提取
type MainPermissions = CategoryValue['mainPermissions'];
type SubPermissions = CategoryValue['subPermissions'];
type AllMainPermissions = MainPermissions[number];
type AllSubPermissions = SubPermissions[number];
type PermissionItem = AllMainPermissions | AllSubPermissions;

// 提取所有 permissionsName 作为联合类型
export type PermissionName = PermissionItem['permissionsName'];

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
