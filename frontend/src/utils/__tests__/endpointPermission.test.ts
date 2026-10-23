import { describe, expect, it, vi } from 'vitest';
import UI, { defineUiPermissionTable } from '@/constants/uiEndpointPermissions';
import type { EndpointKey } from '@/types/endpointPermission.gen';
import { lookupPermission, permissionOfEndpoint } from '@/utils/endpointPermission';

describe('endpointPermission（任务 2.2.2/2.2.3 四类语义）', () => {
  it('已登记端点返回运行期权限名（含模块前缀，与用户 permissions_name 全等比较）', () => {
    expect(lookupPermission(UI, 'GET /company/custom')).toBe('customer:CUSTOMER_QUERY_COMPANY');
    expect(lookupPermission(UI, 'POST /contactTask/create')).toBe('task:TASK_CREATE_TASK');
    expect(permissionOfEndpoint('POST /permission/addORDeletePermissionsToRole')).toBe(
      'system:SYSTEM_ASSIGN_PERMISSION',
    );
  });

  it('未登记端点 fail-closed：返回 undefined，且 console.warn 同键只发一次', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      expect(permissionOfEndpoint('GET /assist/my')).toBeUndefined();
      expect(warn).toHaveBeenCalledTimes(1);
      expect(permissionOfEndpoint('GET /assist/my')).toBeUndefined();
      expect(warn).toHaveBeenCalledTimes(1);
      expect(warn.mock.calls[0]?.[0]).toContain('GET /assist/my');
    } finally {
      warn.mockRestore();
    }
  });

  it('OPEN 端点登记为 null：视为放行（OPEN 只能配 null，由 ContractPermission 在编译期钉死）', () => {
    const openTable = defineUiPermissionTable({
      'GET /permission/getMyPermission': null,
    });
    expect(lookupPermission(openTable, 'GET /permission/getMyPermission')).toBeNull();
  });

  it('空值/无效键：返回 undefined（fail-closed 不渲染，不误放行）', () => {
    expect(lookupPermission(UI, '' as EndpointKey)).toBeUndefined();
    expect(
      lookupPermission(UI, 'GET /definitely/not/a/contract/endpoint' as EndpointKey),
    ).toBeUndefined();
  });
});
