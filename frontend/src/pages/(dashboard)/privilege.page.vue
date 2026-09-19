<script lang="ts" setup>
import MainLayout from '@/layout/MainLayout.vue';
import { RouterView, useRouter } from 'vue-router';
import { computed, ref, watch } from 'vue';
import { Tabs } from 'ant-design-vue';
import DeptManagePanel from '@/components/user/DeptManagePanel.vue';
import { useQueryMyPermissions } from '@/hooks/usePremission';
import { extractPermissionNames } from '@/types/permission';

definePage({
  name: 'privilege',
});

const router = useRouter();
const activeKey = ref<'role' | 'user' | 'dept'>('role');
const { data: userPermissions } = useQueryMyPermissions();
const hasDeptManagePermission = computed(() => {
  const names = extractPermissionNames(userPermissions.value);
  return (
    names.includes('system:SYSTEM_CREATE_USER') ||
    names.includes('system:SYSTEM_UPDATE_USER')
  );
});
const selectTab = (key: string | number) => {
  if (key === 'dept') {
    activeKey.value = 'dept';
    return;
  }
  void router.push(key === 'user' ? '/privilege/user' : '/privilege/role');
};
watch(
  () => router.currentRoute.value.path,
  (path) => {
    // 仅直接访问父级 /privilege 时默认落到角色管理，不劫持子路由深链
    if (path === '/privilege') {
      void router.push('/privilege/role');
    } else if (path === '/privilege/user') {
      activeKey.value = 'user';
    } else if (path === '/privilege/role') {
      activeKey.value = 'role';
    }
  },
  { immediate: true },
);
</script>

<template>
  <MainLayout>
    <div class="w-full md:p-4">
      <Tabs
        :active-key="activeKey"
        type="card"
        :tab-bar-style="{ margin: 0 }"
        @change="selectTab"
      >
        <Tabs.TabPane key="role" tab="角色管理" />
        <Tabs.TabPane key="user" tab="用户管理" />
        <Tabs.TabPane v-if="hasDeptManagePermission" key="dept" tab="部门管理">
          <div class="mt-3"><DeptManagePanel /></div>
        </Tabs.TabPane>
      </Tabs>
      <router-view v-if="activeKey !== 'dept'" />
    </div>
  </MainLayout>
</template>
