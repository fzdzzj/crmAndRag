<script setup lang="ts">
import { computed } from 'vue';
import { useQueryMyPermissions } from '@/hooks/usePremission';
import type { PermissionNameArray } from '@/types/permission';
import { extractPermissionNames } from '@/types/permission';

interface Props {
  /**
   * 允许的权限名：`模块:枚举名`（如 `customer:CUSTOMER_QUERY_CONTACT`）或裸枚举名。
   * 枚举段由生成物 `PermissionName` 收窄（types/permission.gen.ts），拼错权限名在编译期报错。
   */
  allow: PermissionNameArray;
}

const props = defineProps<Props>();

const { data: userPermissions } = useQueryMyPermissions();

const hasPermission = computed(() => {
  if (!props.allow || props.allow.length === 0) return false;
  const userPermissionNames = extractPermissionNames(userPermissions.value);
  return props.allow.some((name) => userPermissionNames.includes(name));
});
</script>

<template>
  <slot v-if="hasPermission" />
</template>
