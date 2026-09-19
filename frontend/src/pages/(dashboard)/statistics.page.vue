<template>
  <MainLayout>
    <div class="w-full rounded-lg space-y-4">
      <!-- 季度金额统计图 -->
      <QuarterlyChart />

      <!-- 动态统计图表 -->
      <DynamicStatisticsChart />

      <!-- 综合统计图表组件 -->
      <StatisticsChart />
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import MainLayout from '@/layout/MainLayout.vue';
import DynamicStatisticsChart from '@/components/statistics/DynamicStatisticsChart.vue';
import QuarterlyChart from '@/components/statistics/QuarterlyChart.vue';
import StatisticsChart from '@/components/statistics/StatisticsChart.vue';

definePage({
  name: 'statistics',
});
</script>
