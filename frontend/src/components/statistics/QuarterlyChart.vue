<template>
  <div class="bg-white p-6 rounded-lg shadow-sm">
    <div class="flex justify-between items-center mb-4">
      <h2 class="text-lg font-semibold">季度发票与回款金额统计</h2>
      <a-select v-model:value="selectedYear" style="width: 120px" @change="handleYearChange">
        <a-select-option v-for="year in availableYears" :key="year" :value="year">
          {{ year }}年
        </a-select-option>
      </a-select>
    </div>
    <div v-if="isLoading" class="flex justify-center items-center h-80">
      <Spin size="large" />
    </div>
    <div v-else ref="chartRef" class="w-full h-80"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useQuarterlySummary } from '@/hooks/useStatistics';
import { useQuarterlyChart } from '@/hooks/useQuarterlyChart';
import { Spin, Select as ASelect, SelectOption as ASelectOption } from 'ant-design-vue';

// 生成年份列表（当前年份往前5年）
const currentYear = new Date().getFullYear();
const availableYears = computed(() => {
  const years = [];
  for (let i = 0; i < 5; i++) {
    years.push(currentYear - i);
  }
  return years;
});

const selectedYear = ref(currentYear);
const { chartData, isLoading } = useQuarterlySummary(selectedYear);
const chartRef = ref<HTMLDivElement>();

// 使用季度统计图表 hook
useQuarterlyChart(chartRef, { chartData, isLoading });

const handleYearChange = () => {
  // 年份变化时，watch 会自动处理数据更新
};
</script>
