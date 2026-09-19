<script lang="ts" setup>
import MainLayout from '@/layout/MainLayout.vue';
import { RouterView, useRouter } from 'vue-router';
import { computed, watch } from 'vue';
import { Tabs } from 'ant-design-vue';

definePage({
  name: 'client',
});

const router = useRouter();
const activeKey = computed({
  get: () => {
    const path = router.currentRoute.value.path;
    switch (path) {
      case '/client/contacts':
        return 'contacts';
      default:
        return 'list';
    }
  },
  set: (key: string) => {
    switch (key) {
      case 'contacts':
        void router.push('/client/contacts');
        break;
      default:
        void router.push('/client/list');
        break;
    }
  },
});
watch(
  () => router.currentRoute.value.path,
  (path) => {
    // 仅直接访问父级 /client 时默认落到客户列表，不劫持子路由深链
    if (path === '/client') {
      void router.push('/client/list');
    }
  },
  { immediate: true },
);
</script>

<template>
  <MainLayout>
    <div class="w-full md:p-4">
      <Tabs
        v-model:active-key="activeKey"
        type="card"
        :tab-bar-style="{ margin: 0 }"
      >
        <Tabs.TabPane key="list" tab="客户列表"/>
        <Tabs.TabPane key="contacts">
          <template #tab>
            <span data-tour="client-contacts-tab">联系人管理</span>
          </template>
        </Tabs.TabPane>
      </Tabs>
      <router-view/>
    </div>
  </MainLayout>
</template>
