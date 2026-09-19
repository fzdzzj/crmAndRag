<template>
  <div class="p-5 rounded-xl bg-white space-y-6">
    <!-- 商机阶段分布 -->
    <div>
      <div class="flex justify-between items-center mb-4">
        <h3 class="font-extrabold text-lg">商机阶段分布</h3>
      </div>

      <!-- 卡片展示 -->
      <Spin :spinning="stageStats.isLoading.value">
        <div
          v-if="stageStats.stageCards.value?.length"
          class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-6"
        >
          <div
            v-for="card in stageStats.stageCards.value"
            :key="card.key"
            class="p-4 rounded-lg text-center"
            :style="{
              background: `linear-gradient(135deg, ${card.color} 0%, ${card.color}dd 100%)`,
            }"
          >
            <div class="text-sm text-white/90 mb-2">{{ card.label }}</div>
            <div class="text-2xl font-bold text-white">{{ card.count }}</div>
            <div class="text-sm text-white/80 mt-1">{{ card.percentage }}%</div>
          </div>
        </div>
        <Empty v-else description="暂无数据" />
      </Spin>

      <!-- 饼图展示 -->
      <Spin :spinning="stageStats.isLoading.value">
        <div
          v-show="stageStats.chartData.value?.length"
          ref="chartRef"
          class="w-full h-[400px]"
        ></div>
        <Empty
          v-if="!stageStats.chartData.value?.length"
          description="暂无图表数据"
        />
      </Spin>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { Spin, Empty } from 'ant-design-vue';
import type { EChartsCoreOption } from 'echarts/core';
import { useOpportunityStageDistribution } from '@/hooks/useStatistics';
import { useEChart } from '@/hooks/useEChart';

// 使用商机阶段分布数据
const stageStats = useOpportunityStageDistribution();

// 图表容器
const chartRef = ref<HTMLDivElement>();

// 使用 ECharts hook
useEChart(chartRef, {
  getOption: () => {
    if (!stageStats.chartData.value?.length) {
      return {};
    }

    const option: EChartsCoreOption = {
      title: {
        text: '商机阶段分布',
        left: 'center',
        top: 10,
      },
      tooltip: {
        trigger: 'item',
        formatter: '{b}: {c} ({d}%)',
        confine: true,
      },
      legend: {
        orient: 'vertical',
        left: 'left',
        top: 'middle',
      },
      series: [
        {
          type: 'pie',
          radius: ['40%', '70%'],
          center: ['60%', '55%'],
          data: stageStats.chartData.value,
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)',
            },
          },
          label: {
            formatter: '{b}: {c}\n({d}%)',
          },
        },
      ],
    };

    return option;
  },
});
</script>
