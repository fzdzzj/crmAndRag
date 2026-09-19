<template>
  <Layout class="layout">
    <Layout.Sider
      v-model:collapsed="collapsed"
      collapsible
      :trigger="null"
      breakpoint="md"
      collapsed-width="0"
      style="background-color: #fff"
      theme="light"
    >
      <aside class="layout-sider">
        <div class="logo-area">
          <Logo />
        </div>
        <SideBarNav />
        <UserBriefCard />
      </aside>
    </Layout.Sider>
    <!-- 移动端菜单展开时的遮罩，点击收起菜单 -->
    <div
      v-if="isMobile && !collapsed"
      class="sider-mask"
      @click="collapsed = true"
    ></div>
    <Layout>
      <Layout.Content class="layout-content">
        <Header></Header>
        <router-view></router-view>
      </Layout.Content>
    </Layout>
    <TutorialDrawer />
  </Layout>
</template>

<script setup lang="ts">
import { Grid, Layout } from 'ant-design-vue';
import Header from '@/layout/components/Header.vue';
import Logo from '@/layout/components/Logo.vue';
import SideBarNav from '@/layout/components/SideBarNav.vue';
import UserBriefCard from '@/layout/components/UserBriefCard.vue';
import TutorialDrawer from '@/components/tutorial/TutorialDrawer.vue';
import { computed, onMounted, provide, ref } from 'vue';
import { useTutorial } from '@/hooks/useTutorial';
import { TutorialStorage } from '@/utils/tutorialStorage';

const collapsed = ref(false);
const screens = Grid.useBreakpoint();
const isMobile = computed(() => !screens.value.md);
const toggleCollapsed = () => {
  collapsed.value = !collapsed.value;
};
provide('sider-collapsed', { collapsed, toggleCollapsed });

const { openDrawer } = useTutorial();

// 每个用户首次进入主界面时自动弹出一次使用教程
onMounted(() => {
  if (!TutorialStorage.hasSeenIntro()) {
    TutorialStorage.markIntroSeen();
    openDrawer();
  }
});
</script>

<style scoped>
.layout {
  --header-height: 64px;
  --sider-width: 200px;
  height: 100%;
}
.layout-sider {
  display: flex;
  flex-direction: column;
  background-color: #fff;
  width: var(--sider-width);
  height: 100vh;
  position: relative;
}
.logo-area {
  display: flex;
  align-items: center;
  justify-content: start;
  padding-left: 20px;
  height: 80px;
  width: var(--sider-width);
}
.layout-content {
  overflow: auto;
}

/* 移动端：菜单悬浮在内容之上，不挤开列表页 */
@media (max-width: 768px) {
  .layout :deep(.ant-layout-sider) {
    position: fixed;
    top: 0;
    left: 0;
    height: 100vh;
    z-index: 1000;
  }
  .sider-mask {
    position: fixed;
    inset: 0;
    z-index: 999;
    background: rgba(0, 0, 0, 0.45);
  }
}
</style>
