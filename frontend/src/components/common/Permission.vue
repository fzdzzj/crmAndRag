<script setup lang="ts">
import { computed } from 'vue';
import { useQueryMyPermissions } from '@/hooks/usePremission';
import type { PermissionNameArray } from '@/types/permission';
import { extractPermissionNames } from '@/types/permission';

interface Props {
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
