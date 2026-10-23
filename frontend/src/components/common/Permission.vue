<script setup lang="ts">
import { computed } from 'vue';
import { useQueryMyPermissions } from '@/hooks/usePremission';
import type { PermissionNameArray } from '@/types/permission';
import type { EndpointKey } from '@/types/endpointPermission.gen';
import { extractPermissionNames } from '@/types/permission';
import { permissionOfEndpoint } from '@/utils/endpointPermission';

interface Props {
  /**
   * 端点键 `'METHOD /path'`（生成物 endpointPermission.gen.ts 的 EndpointKey 联合）：优先于 `allow`。
   * 端点 → 权限名的映射在 `uiEndpointPermissions.ts` 编译期钉死；未登记端点 fail-closed（不渲染并告警一次）。
   */
  endpoint?: EndpointKey;
  /**
   * 允许的权限名（受控逃生口，优先级低于 `endpoint`）：`模块:枚举名`（如 `customer:CUSTOMER_QUERY_CONTACT`）或裸枚举名。
   * 枚举段由生成物 `PermissionName` 收窄（types/permission.gen.ts），拼错权限名在编译期报错。
   * 仅当权限名不在任何契约端点上时才允许使用（例外清单见 PermissionSiteAuditTest 白名单）。
   */
  allow?: PermissionNameArray;
}

const props = defineProps<Props>();

const { data: userPermissions } = useQueryMyPermissions();

const hasPermission = computed(() => {
  if (props.endpoint) {
    const permission = permissionOfEndpoint(props.endpoint);
    if (permission === undefined) return false;
    if (permission === null) return true;
    return extractPermissionNames(userPermissions.value).includes(permission);
  }
  if (!props.allow || props.allow.length === 0) return false;
  const userPermissionNames = extractPermissionNames(userPermissions.value);
  return props.allow.some((name) => userPermissionNames.includes(name));
});
</script>

<template>
  <slot v-if="hasPermission" />
</template>
