<script setup lang="ts">
import type {
  GetPermissionGetByRoleResponse,
  GetRoleListResponse,
} from '@/api/axios';
import { ref, computed } from 'vue';
import {
  Modal as AModal,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Tag as ATag,
} from 'ant-design-vue';
import { useQueryPermissionsByRole } from '@/hooks/usePremission';

type RoleRecord = NonNullable<NonNullable<GetRoleListResponse['data']>['records']>[number];
type PermissionGroup = NonNullable<GetPermissionGetByRoleResponse['data']>;

const role = ref<RoleRecord>();
const innerOpen = ref(false);
const currentId = ref<number>();

const { data: rolePermissionsData, isLoading: isLoadingRolePermissions } =
  useQueryPermissionsByRole(currentId);

const grouped = computed<PermissionGroup>(() => rolePermissionsData.value ?? {
  mainPermissions: [],
  subPermissions: [],
});
const hasMainPermissions = computed(
  () => (grouped.value.mainPermissions?.length ?? 0) > 0,
);
const hasSubPermissions = computed(
  () => (grouped.value.subPermissions?.length ?? 0) > 0,
);

const open = (data: RoleRecord) => {
  role.value = data;
  currentId.value = data.id;
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  role.value = undefined;
  currentId.value = undefined;
};

defineExpose({ open, close });
</script>

<template>
  <AModal
    v-model:open="innerOpen"
    title="查看角色详情"
    :footer="null"
    width="600px"
  >
    <ADescriptions v-if="role" bordered :column="1">
      <ADescriptionsItem label="角色名称">
        {{ role.roleName }}
      </ADescriptionsItem>
      <ADescriptionsItem label="角色描述">
        {{ role.roleDesc || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="主权限">
        <div v-if="isLoadingRolePermissions">加载中...</div>
        <div v-else-if="hasMainPermissions">
          <ATag
            v-for="permission in grouped.mainPermissions"
            :key="permission.id"
            color="blue"
            style="margin: 4px"
          >
            {{ permission.permissionsDesc }}
          </ATag>
        </div>
        <div v-else>-</div>
      </ADescriptionsItem>
      <ADescriptionsItem v-if="hasSubPermissions" label="子权限（数据范围）">
        <ATag
          v-for="permission in grouped.subPermissions"
          :key="permission.id"
          color="cyan"
          style="margin: 4px"
        >
          {{ permission.permissionsDesc }}
        </ATag>
      </ADescriptionsItem>
    </ADescriptions>
  </AModal>
</template>
