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

/** 主权限（PermissionVo）与子权限（SubPermissionVo）共有的可检索字段。 */
type CatalogMainPermission = PermissionCategory['main'][number];
type CatalogSubPermission = PermissionCategory['sub'][number];

/** 授权页过滤条件（搜索 / 只看已选），与模块级批量操作共用同一组纯函数的入参。 */
export interface PermissionFilterOptions {
  /** 关键词：按空格分词，全部词都命中才算命中（交集语义） */
  keyword?: string;
  /** 只看已选：只保留含已选权限的项（父权限仅作容器保留） */
  onlySelected?: boolean;
  /** 已选权限 id（onlySelected 与计数用；顺序无关） */
  selectedIds?: readonly number[];
}

/** 单个模块的「已选/总数」（面板标题与模块级全选用）。 */
export interface ModuleSelectionCount {
  /** 模块展示名（与接口 Map 键逐字一致） */
  module: string;
  /** 该模块已选权限数 */
  selected: number;
  /** 该模块权限总数（主权限 + 子权限，含父未匹配的兜底子权限） */
  total: number;
}

/**
 * 按关键词 / 只看已选过滤权限目录，保持输入顺序。
 *
 * <p>关键词按空格分词后取<b>交集</b>（每个词都要命中），每条权限的检索面 = 中文描述 + 权限名 + ID；
 * 模块展示名命中时整块保留（搜「客户」能直接定位到客户模块）。
 *
 * <p>命中父权限时其子权限整组保留（树形上下文不断）；父未命中的子权限单独命中时，父权限作为容器保留，
 * 只带孩子不丢。`onlySelected` 下父权限仅作容器，子权限只留已选。孤儿权限（父不在本模块）始终参与检索。
 */
export function filterCategories(
  categories: PermissionCategory[],
  options: PermissionFilterOptions = {},
): PermissionCategory[] {
  const tokens = tokenizeKeyword(options.keyword);
  const selected = new Set(options.selectedIds ?? []);
  const onlySelected = options.onlySelected === true;
  if (tokens.length === 0 && !onlySelected) return categories;

  const result: PermissionCategory[] = [];
  for (const category of categories) {
    const moduleHit = tokens.length > 0 && matchesAll(category.module, tokens);
    const main: CatalogMainPermission[] = [];
    const sub: CatalogSubPermission[] = [];
    for (const permission of category.main) {
      const ownSubs = subPermissionsOf(category, permission.id);
      const selfHit = matchesAll(searchText(permission), tokens);
      const keptSubs = ownSubs.filter((item) =>
        onlySelected
          ? item.id != null && selected.has(item.id)
          : moduleHit || selfHit || matchesAll(searchText(item), tokens),
      );
      const keepSelf = onlySelected
        ? permission.id != null && selected.has(permission.id)
        : moduleHit || selfHit;
      if (keepSelf || keptSubs.length > 0) {
        main.push(permission);
        sub.push(...keptSubs);
      }
    }
    const orphanSub = category.orphanSub.filter((item) =>
      onlySelected
        ? item.id != null && selected.has(item.id)
        : moduleHit || matchesAll(searchText(item), tokens),
    );
    sub.push(...orphanSub);
    if (main.length > 0 || orphanSub.length > 0) {
      result.push({ module: category.module, main, sub, orphanSub });
    }
  }
  return result;
}

/**
 * 每个模块的已选/总数（按输入顺序）。
 *
 * <p>`total` 含父未匹配的兜底子权限；`selected` 只数落在 `selectedIds` 里的权限（id 去重，孤儿也算）。
 */
export function moduleSelectionCounts(
  categories: PermissionCategory[],
  selectedIds: readonly number[] = [],
): ModuleSelectionCount[] {
  const selected = new Set(selectedIds);
  return categories.map((category) => {
    const ids = idsOfCategory(category);
    return {
      module: category.module,
      selected: ids.filter((id) => selected.has(id)).length,
      total: ids.length,
    };
  });
}

/**
 * 模块级「全选 / 清空」的 id 集合来源：主权限 + 子权限（含孤儿），去重保序。
 *
 * <p>用<b>未过滤</b>的分类算，保证搜索态下点「全选」仍是整模块全选，不会只选中当前可见项。
 */
export function idsOfCategory(category: PermissionCategory | undefined): number[] {
  if (!category) return [];
  const ids: number[] = [];
  const seen = new Set<number>();
  for (const item of [...category.main, ...category.sub]) {
    if (item.id != null && !seen.has(item.id)) {
      seen.add(item.id);
      ids.push(item.id);
    }
  }
  return ids;
}

/** 关键词 → 小写词元；空串/全空白 → 空数组。 */
function tokenizeKeyword(keyword?: string): string[] {
  return (keyword ?? '')
    .toLowerCase()
    .split(/\s+/)
    .filter((token) => token.length > 0);
}

/** 单条权限的可检索文本：中文描述 + 权限名 + ID（任一字段含该词即算命中）。 */
function searchText(permission: CatalogMainPermission | CatalogSubPermission): string {
  return [permission.permissionsDesc, permission.permissionsName, permission.id]
    .filter((value) => value != null)
    .join(' ')
    .toLowerCase();
}

/** 全部词元都命中（交集语义）；词元为空时恒真。 */
function matchesAll(text: string, tokens: string[]): boolean {
  const haystack = text.toLowerCase();
  return tokens.every((token) => haystack.includes(token));
}
