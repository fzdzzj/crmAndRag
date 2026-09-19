<script lang="ts" setup>
import MainLayout from '@/layout/MainLayout.vue';
import { RouterView, useRouter } from 'vue-router';
import { computed, watch } from 'vue';
import { Tabs } from 'ant-design-vue';

definePage({
  name: 'contact',
});

const router = useRouter();
const activeKey = computed({
  get: () => {
    const path = router.currentRoute.value.path;
    switch (path) {
      case '/contact/activity':
        return 'activity';
      default:
        return 'task';
    }
  },
  set: (key: string) => {
    switch (key) {
      case 'activity':
        void router.push('/contact/activity');
        break;
      default:
        void router.push('/contact/task');
        break;
    }
  },
});
watch(
  () => router.currentRoute.value.path,
  (path) => {
    // 仅直接访问父级 /contact 时默认落到联络任务，不劫持子路由深链
    if (path === '/contact') {
      void router.push('/contact/task');
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
        <Tabs.TabPane key="task" tab="联络任务"/>
        <Tabs.TabPane key="activity" tab="业务活动"/>
      </Tabs>
      <router-view/>
    </div>
  </MainLayout>
</template>
