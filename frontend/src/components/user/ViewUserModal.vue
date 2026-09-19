<script setup lang="ts">
import type { PostUserFindResponse } from '@/api/axios';
import { computed, ref } from 'vue';
import {
  Modal as AModal,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Tag as ATag,
} from 'ant-design-vue';
import { useQueryRoles } from '@/hooks/useUser';

type UserDetail = NonNullable<NonNullable<PostUserFindResponse['data']>['records']>[number];

const props = defineProps<{
  roleMap?: Record<number, string>;
}>();

const { data: roleData } = useQueryRoles({ all: true });
const localRoleMap = computed(() =>
  roleData.value?.records?.reduce(
    (acc, role) => {
      if (role.id !== undefined) {
        acc[role.id] = role.roleName ?? '';
      }
      return acc;
    },
    {} as Record<number, string>,
  ),
);

const user = ref<UserDetail>();
const innerOpen = ref(false);

const statusMap = {
  0: { text: '禁用', color: 'error' },
  1: { text: '启用', color: 'success' },
  2: { text: '离职', color: 'default' },
} as const;

const getStatusInfo = (status?: number) => {
  const key = (status ?? 0) as keyof typeof statusMap;
  return statusMap[key] ?? statusMap[0];
};

const getRoleName = (data: UserDetail) => {
  const name =
    (data.roleId !== undefined && props.roleMap?.[data.roleId]) ||
    (data.roleId !== undefined && localRoleMap.value?.[data.roleId]) ||
    data.roleName;
  return name || '-';
};

const open = (data: UserDetail) => {
  user.value = data;
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  user.value = undefined;
};

defineExpose({ open, close });
</script>

<template>
  <AModal
    v-model:open="innerOpen"
    title="查看用户详情"
    :footer="null"
    width="600px"
  >
    <ADescriptions v-if="user" bordered :column="1">
      <ADescriptionsItem label="用户ID">
        {{ user.id ?? '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="姓名">
        {{ user.realName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="电话">
        {{ user.phone || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="邮箱">
        {{ user.email || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="部门">
        {{ user.deptName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="角色">
        {{ getRoleName(user) }}
      </ADescriptionsItem>
      <ADescriptionsItem label="状态">
        <ATag :color="getStatusInfo(user.status).color">
          {{ getStatusInfo(user.status).text }}
        </ATag>
      </ADescriptionsItem>
    </ADescriptions>
  </AModal>
</template>
