import { describe, expect, it } from 'vitest';
import {
  buildPermissionCategories,
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
