<script setup lang="ts">
import { computed } from 'vue';
import { ArrowRightOutlined } from '@ant-design/icons-vue';
import { stageMap } from '@/constants/sale/constant';

const props = defineProps<{
  currentStage: number;
}>();

defineEmits<{
  select: [index: number];
}>();

const stageList = computed(() =>
  Object.values(stageMap),
);

const getStageClass = (index: number) => {
  const base = [
    'inline-flex items-center justify-center px-3 py-1.5 rounded-full text-sm font-medium cursor-pointer transition-colors whitespace-nowrap',
  ];
  if (index < props.currentStage) {
    base.push('bg-green-100 text-green-700 border border-green-300');
  } else if (index === props.currentStage) {
    base.push('bg-blue-500 text-white border border-blue-500 shadow-sm');
  } else {
    base.push('bg-gray-100 text-gray-400 border border-gray-200');
  }
  return base;
};

const getArrowClass = (index: number) => {
  const base = ['text-lg'];
  if (index < props.currentStage) {
    base.push('text-green-400');
  } else if (index === props.currentStage) {
    base.push('text-blue-400');
  } else {
    base.push('text-gray-300');
  }
  return base;
};
</script>

<template>
  <div class="flex items-center justify-center gap-2 py-4 flex-wrap">
    <template v-for="(label, index) in stageList" :key="index">
      <div :class="getStageClass(index)" @click="$emit('select', index)">
        <span>{{ label }}</span>
      </div>
      <ArrowRightOutlined v-if="index < stageList.length - 1" :class="getArrowClass(index)" />
    </template>
  </div>
</template>
