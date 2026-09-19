<template>
  <header>
    <Button
      v-if="isMobile"
      type="text"
      class="menu-btn"
      @click="toggleCollapsed"
    >
      <MenuFoldOutlined />
    </Button>
    <h2>{{ title }}</h2>
    <div class="right">
      <Button type="text" @click="openAi">
        <RobotOutlined />
        AI 助手
      </Button>
      <Button type="text" @click="openDrawer">
        <QuestionCircleOutlined />
        使用教程
      </Button>
    </div>
  </header>

  <AiAssistantDrawer v-model:open="aiOpen" />
</template>

<script setup lang="ts">
import pathTitleMap from '@/constants/pathTitleMap';
import { Button } from 'ant-design-vue';
import { MenuFoldOutlined, QuestionCircleOutlined, RobotOutlined } from '@ant-design/icons-vue';
import { computed, inject, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useTutorial } from '@/hooks/useTutorial';
import AiAssistantDrawer from '@/components/ai/AiAssistantDrawer.vue';

type PathTitleKey = keyof typeof pathTitleMap;

const route = useRoute();
const title = computed(() => {
  const section = route.path.split('/')[1] as PathTitleKey | undefined;
  return section ? pathTitleMap[section]?.title : undefined;
});

const isMobile = computed(() => window.innerWidth < 768);
const sider = inject<{ toggleCollapsed: () => void } | undefined>('sider-collapsed', undefined);
const toggleCollapsed = () => {
  sider?.toggleCollapsed();
};

const { openDrawer } = useTutorial();

const aiOpen = ref(false);
function openAi() {
  aiOpen.value = true;
}
</script>

<style scoped>
header {
  padding: 0 25px;

  h2 {
    margin: 0;
  }

  height: 64px;
  background-color: #fff;
  display: flex;
  align-items: center;
  gap: 12px;

  .menu-btn {
    display: none;
  }

  @media (max-width: 768px) {
    .menu-btn {
      display: inline-flex;
    }
  }

  .right {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-left: auto;
  }
}
</style>
