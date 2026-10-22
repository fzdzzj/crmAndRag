import type { PermissionGroupedVo, SubPermissionVo } from '@/api/axios';

/** `/permission/list` 的返回体：模块展示名 → 主/子权限 */
export type PermissionCatalog = Record<string, PermissionGroupedVo | undefined>;

/** 一个模块分组：主权限 + 全部子权限 + 父未匹配的兜底子权限 */
export interface PermissionCategory {
  /** 模块展示名（原样取自接口 Map 键，保证与后端逐字一致） */
  module: string;
  /** 该模块的主权限（保持接口返回顺序） */
  main: NonNullable<PermissionGroupedVo['mainPermissions']>;
  /** 该模块的全部子权限（含父未匹配项，供按父过滤） */
  sub: NonNullable<PermissionGroupedVo['subPermissions']>;
  /** 父权限不在本模块 main 里的子权限：模块级兜底渲染，绝不丢弃 */
  orphanSub: NonNullable<PermissionGroupedVo['subPermissions']>;
}

/**
 * 把 `GET /permission/list` 的 Map 响应转成有序分类数组。
 *
 * <p>跳过空模块（main 与 sub 都为空），其余保持接口返回顺序（= 后端 `PermissionModule` 声明顺序）。
 * 父权限未出现在 main 中的子权限收集到 `orphanSub`，由调用方按模块级兜底渲染——任何一条权限都不丢弃。
 */
export function buildPermissionCategories(
  record: PermissionCatalog | undefined | null,
): PermissionCategory[] {
  if (!record) return [];
  const categories: PermissionCategory[] = [];
  for (const [module, group] of Object.entries(record)) {
    const main = group?.mainPermissions ?? [];
    const sub = group?.subPermissions ?? [];
    if (main.length === 0 && sub.length === 0) continue;
    const mainIds = new Set(main.map((permission) => permission.id));
    categories.push({
      module,
      main,
      sub,
      orphanSub: sub.filter((permission) => !mainIds.has(permission.parentPermissionId)),
    });
  }
  return categories;
}

/** 取某个分类下挂到指定主权限的子权限；父 ID 缺失时返回空数组 */
export function subPermissionsOf(
  category: PermissionCategory | undefined,
  parentId: number | undefined,
): SubPermissionVo[] {
  if (!category || parentId == null) return [];
  return category.sub.filter((sub) => sub.parentPermissionId === parentId);
}
