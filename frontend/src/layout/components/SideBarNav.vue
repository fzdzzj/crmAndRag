<template>
  <Menu v-model:selected-keys="selectedKeys" style="border: none" data-tour="sidebar-nav">
    <Menu.Item
      v-for="([key, item]) in visibleMenuItems"
      :key="key"
      :active-key="key"
      :data-tour="`menu-${key}`"
      @click="router.push(`/${key}`)"
    >
      <router-link
        :to="{
          path:`/${key}`
        }"
      >
        <span>{{ item.title }}</span>
      </router-link>
    </Menu.Item>
  </Menu>
</template>
<script setup lang="ts">
import { Menu } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useRouter, RouterLink } from 'vue-router';
import pathTitleMap from '@/constants/pathTitleMap';
import { useQueryMyPermissions } from '@/hooks/usePremission';
import { extractPermissionNames } from '@/types/permission';
const router = useRouter();
const selectedKeys = ref([router.currentRoute.value.path.split('/')[1]]);

const { data: myPermissions } = useQueryMyPermissions();

const menuPermissionPrefixes: Record<string, string[]> = {
  client: ['customer'],
  sale: ['sales'],
  finance: ['finance'],
  contact: ['task', 'sales'],
  statistics: ['report'],
  privilege: ['system'],
  org: ['system'],
};

const visibleMenuItems = computed(() => {
  const names = extractPermissionNames(myPermissions.value);
  const hasPermissionData = !!myPermissions.value;
  return Object.entries(pathTitleMap).filter(([key]) => {
    // 权限管理/组织架构菜单必须拿到权限数据后才显示，避免无权限角色短暂看到
    if (key === 'privilege' || key === 'org') {
      return names.some((name) => name.startsWith('system:'));
    }
    if (!hasPermissionData) {
      return true;
    }
    const prefixes = menuPermissionPrefixes[key];
    if (!prefixes) {
      return true;
    }
    return names.some((name) =>
      prefixes.some((prefix) => name.startsWith(`${prefix}:`)),
    );
  });
});

// 监听路由变化，自动更新选中的菜单项
watch(
  () => router.currentRoute.value.path,
  (newPath) => {
    selectedKeys.value = [newPath.split('/')[1]];
  },
);
</script>
