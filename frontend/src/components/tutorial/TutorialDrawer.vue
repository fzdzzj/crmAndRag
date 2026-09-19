<template>
  <Drawer
    v-model:open="isDrawerOpen"
    :width="drawerWidth"
    title="使用教程"
    placement="right"
  >
    <Tabs v-model:active-key="activeCategory">
      <TabPane
        v-for="category in tourCategories"
        :key="category.key"
        :tab="category.label"
      >
        <div
          v-if="categoryTutorials(category.key).length > 0"
          class="flex flex-col gap-3"
        >
          <div
            v-for="(tutorial, tutorialIndex) in categoryTutorials(category.key)"
            :key="tutorial.id"
            class="rounded-md border border-gray-200 p-3"
          >
            <div class="flex items-start justify-between gap-3">
              <button
                type="button"
                class="flex min-w-0 flex-1 items-start gap-2 border-0 bg-transparent p-0 text-left"
                :aria-expanded="isExpanded(tutorial.id)"
                @click="toggleExpanded(tutorial.id)"
              >
                <span class="mt-0.5 text-xs font-medium text-gray-400">
                  {{ tutorialIndex + 1 }}
                </span>
                <span class="min-w-0 flex-1">
                  <span class="flex flex-wrap items-center gap-2">
                    <span class="font-medium text-gray-900">
                      {{ tutorial.title }}
                    </span>
                    <Tag v-if="isDone(tutorial.id)" color="success">已完成</Tag>
                  </span>
                  <span class="mt-1 block text-xs text-gray-500">
                    {{ tutorial.description }}
                  </span>
                  <span class="mt-1 block text-xs text-gray-400">
                    共 {{ tutorial.steps.length }} 步 ·
                    约 {{ getTutorialDurationMinutes(tutorial) }} 分钟
                  </span>
                </span>
                <span
                  class="mt-1 shrink-0 text-xs text-gray-400"
                  aria-hidden="true"
                >
                  {{ isExpanded(tutorial.id) ? '▾' : '▸' }}
                </span>
              </button>

              <div class="flex shrink-0 flex-col items-end gap-2">
                <Button
                  type="primary"
                  size="small"
                  @click="startResume(tutorial)"
                >
                  {{ resumeText(tutorial) }}
                </Button>
                <Button
                  v-if="resumeStepIndex(tutorial) !== null"
                  size="small"
                  @click="startTutorial(tutorial, 0)"
                >
                  从头开始
                </Button>
              </div>
            </div>

            <div
              v-if="isExpanded(tutorial.id)"
              class="mt-3 flex flex-col gap-1 border-t border-gray-100 pt-3"
            >
              <button
                v-for="(step, stepIndex) in tutorial.steps"
                :key="`${tutorial.id}-${stepIndex}`"
                type="button"
                class="w-full rounded border-0 bg-transparent px-2 py-1.5 text-left text-xs text-gray-600 hover:bg-gray-50 hover:text-gray-900"
                @click="startTutorial(tutorial, stepIndex)"
              >
                第 {{ stepIndex + 1 }} 步 · {{ step.title }}
              </button>
            </div>
          </div>
        </div>
        <Empty v-else description="本分类教程整理中，敬请期待" />
      </TabPane>
    </Tabs>
  </Drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Button, Drawer, Empty, Tabs, Tag } from 'ant-design-vue';
import { getTutorialDurationMinutes, tourCategories, tutorials } from '@/constants/tour';
import type { Tutorial, TourCategory } from '@/constants/tour';
import { useTutorial } from '@/hooks/useTutorial';
import { TutorialStorage } from '@/utils/tutorialStorage';

const TabPane = Tabs.TabPane;

const { isDrawerOpen, startTutorial } = useTutorial();

const activeCategory = ref<TourCategory>('quick');
const expandedIds = ref<Set<string>>(new Set());
const resumeSteps = ref<Record<string, number>>({});
const doneIds = ref<Set<string>>(new Set());

const drawerWidth = computed(() => (window.innerWidth < 768 ? '100%' : 480));

function categoryTutorials(category: TourCategory) {
  return tutorials.filter((tutorial) => tutorial.category === category);
}

function isDone(tutorialId: string) {
  return doneIds.value.has(tutorialId);
}

function isExpanded(tutorialId: string) {
  return expandedIds.value.has(tutorialId);
}

function toggleExpanded(tutorialId: string) {
  const next = new Set(expandedIds.value);
  if (next.has(tutorialId)) {
    next.delete(tutorialId);
  } else {
    next.add(tutorialId);
  }
  expandedIds.value = next;
}

function resumeStepIndex(tutorial: Tutorial) {
  const stepIndex = resumeSteps.value[tutorial.id];
  return typeof stepIndex === 'number' ? stepIndex : null;
}

function resumeText(tutorial: Tutorial) {
  const stepIndex = resumeStepIndex(tutorial);
  return stepIndex === null ? '开始' : `继续（第 ${stepIndex + 1} 步）`;
}

function startResume(tutorial: Tutorial) {
  const stepIndex = resumeStepIndex(tutorial);
  void startTutorial(tutorial, stepIndex ?? 0);
}

watch(isDrawerOpen, (open) => {
  const drawer = document.querySelector<HTMLElement>('.ant-drawer');
  const mask = document.querySelector<HTMLElement>('.ant-drawer-mask');
  if (!drawer) {
    return;
  }
  if (open) {
    drawer.style.display = '';
    mask?.style.setProperty('pointer-events', '');
    refreshTutorialState();
    return;
  }
  mask?.style.setProperty('pointer-events', 'none');
  window.setTimeout(() => {
    if (!isDrawerOpen.value) {
      drawer.style.display = 'none';
    }
  }, 450);
});

function refreshTutorialState() {
  const nextDoneIds = new Set<string>();
  const nextResumeSteps: Record<string, number> = {};
  tutorials.forEach((tutorial) => {
    const isDone = TutorialStorage.isTutorialDone(tutorial.id);
    if (isDone) {
      nextDoneIds.add(tutorial.id);
      return;
    }
    const stepIndex = TutorialStorage.getTutorialResume(tutorial.id);
    if (
      stepIndex !== null &&
      stepIndex >= 0 &&
      stepIndex < tutorial.steps.length
    ) {
      nextResumeSteps[tutorial.id] = stepIndex;
    }
  });
  doneIds.value = nextDoneIds;
  resumeSteps.value = nextResumeSteps;
}
</script>
