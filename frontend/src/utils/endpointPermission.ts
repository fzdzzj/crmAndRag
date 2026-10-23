import uiEndpointPermissions, { type PermissionLookupTable } from '@/constants/uiEndpointPermissions';
import type { EndpointKey } from '@/types/endpointPermission.gen';

/** 未登记端点的 fail-closed 告警只发一次（模块级去重，组件重挂载不重复刷屏）。 */
const warnedKeys = new Set<string>();

/**
 * 端点 → 权限判定三元值（优先查指定表）。
 *
 * <p>语义（任务 2.2.2）：
 *
 * <ul>
 *   <li>表内登记为权限名 → 返回该权限名（运行时与 `模块:枚举名` 全等比较）；
 *   <li>表内登记为 `null`（OPEN 端点）→ 返回 `null`，视为放行；
 *   <li>未登记 → 返回 {@code undefined}，调用方必须 fail-closed（不渲染）。
 * </ul>
 */
export function lookupPermission(
  table: PermissionLookupTable,
  key: EndpointKey,
): string | null | undefined {
  if (!Object.prototype.hasOwnProperty.call(table, key)) {
    return undefined;
  }
  const value = table[key];
  return value === undefined ? undefined : value;
}

/**
 * 端点 → 权限判定三元值（查 UI 运行期小表 `uiEndpointPermissions.ts`）。
 *
 * <p>未登记端点会触发一次 `console.warn` 留痕并返回 {@code undefined}（fail-closed）。
 */
export function permissionOfEndpoint(key: EndpointKey): string | null | undefined {
  const permission = lookupPermission(uiEndpointPermissions, key);
  if (permission === undefined && !warnedKeys.has(key)) {
    warnedKeys.add(key);
    console.warn(`[Permission] 端点未登记运行期权限表（fail-closed 不渲染）: ${key}`);
  }
  return permission;
}
