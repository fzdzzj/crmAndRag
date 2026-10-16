<template>
  <div class="flex flex-col gap-3 text-sm">
    <!-- 健康概览 -->
    <div v-if="insight?.healthScore != null" class="flex items-center gap-2">
      <span
        class="inline-flex h-6 min-w-6 items-center justify-center rounded-full px-2 text-xs font-medium"
        :class="healthBadgeClass"
      >
        {{ insight.healthScore }}
      </span>
      <span class="text-neutral-500">{{ healthLevelText }}</span>
    </div>

    <div v-if="insight?.summary" class="text-neutral-700">{{ insight.summary }}</div>

    <div v-if="riskAlerts.length" class="flex flex-col gap-1">
      <div class="text-xs font-medium text-neutral-500">风险点</div>
      <ul class="list-disc pl-5 text-neutral-700">
        <li v-for="(r, i) in riskAlerts" :key="i">{{ r }}</li>
      </ul>
    </div>

    <!-- 行动建议：每条附 转待办 / 转活动 -->
    <div v-if="suggestions.length" class="flex flex-col gap-1">
      <div class="text-xs font-medium text-neutral-500">行动建议</div>
      <div
        v-for="(s, i) in suggestions"
        :key="i"
        class="flex items-start justify-between gap-2 rounded border border-neutral-200 p-2"
      >
        <span class="flex-1 text-neutral-700">{{ s }}</span>
        <div class="flex shrink-0 gap-1">
          <Button size="small" :loading="convertingIndex === i" @click="convert(i, 'CREATE_CONTACT_TASK')">转待办</Button>
          <Button size="small" :loading="convertingIndex === i" @click="convert(i, 'CREATE_BUSINESS_ACTIVITY')">转活动</Button>
        </div>
      </div>
    </div>

    <div v-else-if="loading" class="text-xs text-neutral-400">诊断生成中…</div>
    <div v-else-if="error" class="text-xs text-red-500">{{ error }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Button } from 'ant-design-vue';
import { useCustomerInsight } from '@/hooks/useCustomerInsight';
import {
  useCustomerInsightToAction,
  type InsightActionType,
} from '@/hooks/useCustomerInsightToAction';

const props = defineProps<{ companyId: number }>();

const emit = defineEmits<{ created: [pendingId: string] }>();

const { loading, error, data, load } = useCustomerInsight();
const convertInsightAction = useCustomerInsightToAction();

const convertingIndex = ref<number | null>(null);

const insight = computed(() => data.value);
const riskAlerts = computed(() => insight.value?.riskAlerts ?? []);
const suggestions = computed(() => insight.value?.actionSuggestions ?? []);

const healthBadgeClass = computed(() => {
  switch (insight.value?.healthLevel) {
    case 'EXCELLENT':
      return 'bg-emerald-100 text-emerald-700';
    case 'HEALTHY':
      return 'bg-green-100 text-green-700';
    case 'WARNING':
      return 'bg-amber-100 text-amber-700';
    case 'CRITICAL':
      return 'bg-red-100 text-red-700';
    default:
      return 'bg-neutral-100 text-neutral-600';
  }
});

const healthLevelText = computed(() => {
  const level = insight.value?.healthLevel;
  const label: Record<string, string> = {
    EXCELLENT: '健康状况优秀',
    HEALTHY: '健康状况良好',
    WARNING: '健康状况预警',
    CRITICAL: '健康状况危急',
  };
  return (level && label[level]) || '';
});

watch(
  () => props.companyId,
  (id) => {
    if (id != null) {
      void load(id);
    }
  },
  { immediate: true },
);

async function convert(index: number, actionType: InsightActionType) {
  if (convertingIndex.value != null) return;
  convertingIndex.value = index;
  try {
    const result = await convertInsightAction.convert({
      companyId: props.companyId,
      suggestionIndex: index,
      actionType,
    });
    if (result?.pendingId) {
      emit('created', result.pendingId);
    }
  } finally {
    convertingIndex.value = null;
  }
}
</script>