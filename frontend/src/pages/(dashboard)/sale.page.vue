<script lang="ts" setup>
import MainLayout from '@/layout/MainLayout.vue';
import { RouterView, useRouter } from 'vue-router';
import { computed, watch } from 'vue';
import { Tabs } from 'ant-design-vue';

definePage({
  name: 'sale',
});

const router = useRouter();
const activeKey = computed({
  get: () => {
    const path = router.currentRoute.value.path;
    if (path.startsWith('/sale/contract-details')) {
      return 'contract';
    }
    if (path.startsWith('/sale/detail')) {
      return 'list';
    }
    switch (path) {
      case '/sale/contract':
        return 'contract';
      case '/sale/stage-approval':
        return 'stage-approval';
      case '/sale/list':
        return 'list';
      default: return undefined
    }
  },
  set: (key: string) => {
    switch (key) {
      case 'contract':
        void router.push('/sale/contract');
        break;
      case 'stage-approval':
        void router.push('/sale/stage-approval');
        break;
      case 'list':
        void router.push('/sale/list');
        break;
    }
  },
});
watch(
  () => router.currentRoute.value.path,
  (path) => {
    // 仅直接访问父级 /sale 时默认落到销售订单列表，不劫持子路由深链
    if (path === '/sale') {
      void router.push('/sale/list');
    }
  },
  { immediate: true },
);
</script>

<template>
  <MainLayout>
    <div class="w-full md:p-4">
      <Tabs v-model:active-key="activeKey" type="card" :tab-bar-style="{ margin: 0 }">
        <Tabs.TabPane key="list" tab="销售订单" />
        <Tabs.TabPane key="contract" tab="销售合同" />
        <Tabs.TabPane key="stage-approval">
          <template #tab>
            <span data-tour="sale-approval-tab">阶段审批</span>
          </template>
        </Tabs.TabPane>
      </Tabs>
      <router-view />
    </div>
  </MainLayout>
</template>
