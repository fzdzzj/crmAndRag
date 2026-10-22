import { describe, expect, it } from 'vitest';
import {
  buildPermissionCategories,
  filterCategories,
  idsOfCategory,
  moduleSelectionCounts,
  subPermissionsOf,
  type PermissionCatalog,
} from '@/utils/permissionCatalog';

describe('buildPermissionCategories', () => {
  it('空 record → []', () => {
    expect(buildPermissionCategories(undefined)).toEqual([]);
    expect(buildPermissionCategories(null)).toEqual([]);
    expect(buildPermissionCategories({})).toEqual([]);
  });

  it('跳过空模块，保留非空模块且顺序不变', () => {
    const record: PermissionCatalog = {
      客户管理模块权限: { mainPermissions: [{ id: 101 }], subPermissions: [] },
      销售管理模块权限: { mainPermissions: [], subPermissions: [] },
      'AI 模块权限': { mainPermissions: [{ id: 800 }], subPermissions: [] },
    };

    expect(buildPermissionCategories(record).map((category) => category.module)).toEqual([
      '客户管理模块权限',
      'AI 模块权限',
    ]);
  });

  it('只有子权限（无主权限）的模块也不算空模块', () => {
    const record: PermissionCatalog = {
      知识库管理模块权限: {
        mainPermissions: [],
        subPermissions: [{ id: 900, parentPermissionId: 899 }],
      },
    };

    const categories = buildPermissionCategories(record);

    expect(categories).toHaveLength(1);
    expect(categories[0].module).toBe('知识库管理模块权限');
    expect(categories[0].orphanSub.map((sub) => sub.id)).toEqual([900]);
  });

  it('子权限按 parentPermissionId 归到父权限下，父不匹配的子权限不丢（模块级兜底）', () => {
    const record: PermissionCatalog = {
      客户管理模块权限: {
        mainPermissions: [{ id: 117 }],
        subPermissions: [
          { id: 1171, parentPermissionId: 117 },
          { id: 1172, parentPermissionId: 117 },
          { id: 5555, parentPermissionId: 555 },
        ],
      },
    };

    const [category] = buildPermissionCategories(record);

    expect(subPermissionsOf(category, 117).map((sub) => sub.id)).toEqual([1171, 1172]);
    expect(subPermissionsOf(category, 999)).toEqual([]);
    expect(subPermissionsOf(category, 555).map((sub) => sub.id)).toEqual([5555]);
    expect(category.orphanSub.map((sub) => sub.id)).toEqual([5555]);
    expect(category.main.length + category.sub.length).toBe(4);
  });

  it('subPermissionsOf 在分类缺失或父 ID 缺失时返回 []', () => {
    expect(subPermissionsOf(undefined, 117)).toEqual([]);

    const [category] = buildPermissionCategories({
      客户管理模块权限: {
        mainPermissions: [{ id: 117 }],
        subPermissions: [{ id: 1171, parentPermissionId: 117 }],
      },
    });

    expect(subPermissionsOf(category, undefined)).toEqual([]);
  });
});

// 搜索 / 只看已选 / 模块计数的夹具：两个模块，一个父未匹配的孤儿子权限（802，父 999 不在 main 里）
const catalog: PermissionCatalog = {
  客户管理模块权限: {
    mainPermissions: [
      { id: 117, permissionsName: 'CUSTOMER_VIEW_COMPANY', permissionsDesc: '查看客户公司详情' },
      { id: 116, permissionsName: 'CUSTOMER_QUERY_COMPANY', permissionsDesc: '查询客户公司' },
    ],
    subPermissions: [
      {
        id: 1171,
        parentPermissionId: 117,
        permissionsName: 'CUSTOMER_VIEW_COMPANY_ONLY_MY',
        permissionsDesc: '查看客户公司 - 仅查看自己的',
      },
      {
        id: 1172,
        parentPermissionId: 117,
        permissionsName: 'CUSTOMER_VIEW_COMPANY_ALL',
        permissionsDesc: '查看客户公司 - 查看全部的',
      },
      { id: 802, parentPermissionId: 999, permissionsName: 'PRIVACY_EMAIL_VIEW', permissionsDesc: '查看邮箱' },
    ],
  },
  'AI 模块权限': {
    mainPermissions: [
      { id: 800, permissionsName: 'AI_ASSIST_VIEW', permissionsDesc: '查看协助' },
    ],
    subPermissions: [],
  },
};

const categories = buildPermissionCategories(catalog);
const mainIdsOf = (list: ReturnType<typeof filterCategories>) =>
  list.map((category) => category.main.map((permission) => permission.id));
const subIdsOf = (list: ReturnType<typeof filterCategories>) =>
  list.map((category) => category.sub.map((sub) => sub.id));

describe('filterCategories', () => {
  it('无关键词且不看已选 → 原样返回（同一引用，不复制）', () => {
    expect(filterCategories(categories, {})).toBe(categories);
    expect(filterCategories(categories, { keyword: '   ' })).toBe(categories);
  });

  it('关键词按空格分词取交集（顺序无关、多空格折叠）', () => {
    const filtered = filterCategories(categories, { keyword: '  查看   客户 ' });

    expect(filtered).toHaveLength(1);
    expect(filtered[0].module).toBe('客户管理模块权限');
    expect(mainIdsOf(filtered)).toEqual([[117]]);
    // 父命中 → 子权限整组保留（树形上下文不断）
    expect(subIdsOf(filtered)).toEqual([[1171, 1172]]);
  });

  it('中文描述可命中（含父未匹配的孤儿子权限）', () => {
    const filtered = filterCategories(categories, { keyword: '邮箱' });

    expect(filtered).toHaveLength(1);
    expect(filtered[0].main).toEqual([]);
    expect(filtered[0].orphanSub.map((sub) => sub.id)).toEqual([802]);
  });

  it('权限名可命中（CUSTOMER_QUERY_COMPANY → 116，无子权限）', () => {
    const filtered = filterCategories(categories, { keyword: 'CUSTOMER_QUERY_COMPANY' });

    expect(filtered).toHaveLength(1);
    expect(mainIdsOf(filtered)).toEqual([[116]]);
    expect(subIdsOf(filtered)).toEqual([[]]);
  });

  it('ID 可命中：子权限命中时父权限作为容器保留，只带孩子', () => {
    const filtered = filterCategories(categories, { keyword: '1171' });

    expect(filtered).toHaveLength(1);
    expect(mainIdsOf(filtered)).toEqual([[117]]);
    expect(subIdsOf(filtered)).toEqual([[1171]]);
  });

  it('模块展示名命中时整块保留', () => {
    const filtered = filterCategories(categories, { keyword: 'AI 模块权限' });

    expect(filtered).toHaveLength(1);
    expect(filtered[0].module).toBe('AI 模块权限');
    expect(mainIdsOf(filtered)).toEqual([[800]]);
  });

  it('只看已选：父仅作容器，子只留已选', () => {
    const filtered = filterCategories(categories, {
      onlySelected: true,
      selectedIds: [1172, 800],
    });

    expect(filtered.map((category) => category.module)).toEqual([
      '客户管理模块权限',
      'AI 模块权限',
    ]);
    expect(mainIdsOf(filtered)).toEqual([[117], [800]]);
    expect(subIdsOf(filtered)).toEqual([[1172], []]);
  });

  it('只看已选且一个都没选 → 空结果（不是全量）', () => {
    expect(filterCategories(categories, { onlySelected: true, selectedIds: [] })).toEqual([]);
  });

  it('无匹配关键词 → 空结果，不残留任何模块', () => {
    expect(filterCategories(categories, { keyword: 'zzz-no-such-permission' })).toEqual([]);
  });
});

describe('moduleSelectionCounts / idsOfCategory', () => {
  it('每模块 已选/总数（含孤儿，按输入顺序）', () => {
    expect(moduleSelectionCounts(categories, [117, 1172, 800, 802])).toEqual([
      { module: '客户管理模块权限', selected: 3, total: 5 },
      { module: 'AI 模块权限', selected: 1, total: 1 },
    ]);
    expect(moduleSelectionCounts(categories)).toEqual([
      { module: '客户管理模块权限', selected: 0, total: 5 },
      { module: 'AI 模块权限', selected: 0, total: 1 },
    ]);
  });

  it('模块全选 id 集合 = 主 + 子去重保序，且不影响其他模块', () => {
    const customerIds = idsOfCategory(categories[0]);

    expect(customerIds).toEqual([117, 116, 1171, 1172, 802]);
    expect(idsOfCategory(categories[1])).toEqual([800]);
    expect(customerIds.some((id) => id === 800)).toBe(false);
    expect(idsOfCategory(undefined)).toEqual([]);
  });
});
