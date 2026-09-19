<template>
  <div class="rounded-lg bg-white p-6 shadow-sm">
    <div class="mb-4 flex flex-wrap items-start justify-between gap-4">
      <div>
        <div class="flex items-center gap-2">
          <h2 class="text-lg font-semibold">动态统计图表</h2>
          <a-tag v-if="chartData?.titleEn" color="blue">
            {{ chartData.titleEn }}
          </a-tag>
        </div>
        <p class="mt-1 text-sm text-slate-500">
          使用新的 `chartData` 接口按维度和时间范围生成图表。
        </p>
      </div>
      <a-button
        :loading="isRefetching"
        :disabled="!payload"
        @click="handleRefetchClick"
      >
        刷新图表
      </a-button>
    </div>

    <div class="mb-4 flex flex-wrap items-center gap-3">
      <a-select
        v-model:value="selectedMetric"
        style="width: 220px"
      >
        <a-select-option
          v-for="item in metricOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </a-select-option>
      </a-select>

      <a-select
        v-model:value="selectedTimeRange"
        style="width: 160px"
      >
        <a-select-option
          v-for="item in timeRangeOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </a-select-option>
      </a-select>

      <ARangePicker
        v-if="selectedTimeRange === 'CUSTOM_RANGE'"
        v-model:value="customRange"
        show-time
        format="YYYY-MM-DD HH:mm:ss"
        style="width: 320px"
      />
    </div>

    <div class="mb-4 rounded-md bg-slate-50 px-4 py-3 text-sm text-slate-600">
      {{ selectedMetricOption.description }}
    </div>

    <Spin :spinning="isLoading">
      <div
        v-show="chartEntries.length > 0"
        ref="chartRef"
        class="h-[380px] w-full"
      ></div>
      <Empty
        v-if="!chartEntries.length"
        :description="
          selectedTimeRange === 'CUSTOM_RANGE' && !payload
            ? '请选择完整的自定义时间范围'
            : '暂无图表数据'
        "
      />
    </Spin>

    <div v-if="chartEntries.length > 0" class="mt-4 grid grid-cols-2 gap-3 md:grid-cols-4">
      <div
        v-for="item in chartEntries"
        :key="item.name"
        class="rounded-md border border-slate-200 bg-slate-50 px-3 py-2"
      >
        <div class="truncate text-xs text-slate-500">{{ item.name }}</div>
        <div class="mt-1 text-lg font-semibold text-slate-800">
          {{ item.value }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type {
  PostDataStatisticsChartDataData,
} from '@/api/axios';
import { useStatisticsChartData } from '@/hooks/useStatistics';
import { useEChart } from '@/hooks/useEChart';
import type { EChartsCoreOption } from 'echarts/core';
import {
  Button as AButton,
  DatePicker as ADatePicker,
  Empty,
  Select as ASelect,
  SelectOption as ASelectOption,
  Spin,
  Tag as ATag,
} from 'ant-design-vue';
import dayjs, { type Dayjs } from 'dayjs';
import { computed, ref, watch } from 'vue';

type DataStatisticsDTO = NonNullable<PostDataStatisticsChartDataData['body']>;
type StatisticMetric = NonNullable<DataStatisticsDTO['dataType']>;
type StatisticRange = NonNullable<DataStatisticsDTO['timeRange']>;

type MetricOption = {
  value: StatisticMetric;
  label: string;
  description: string;
  chartType: NonNullable<DataStatisticsDTO['chartType']>;
  defaultRange: Exclude<StatisticRange, 'CUSTOM_RANGE'>;
};

const metricOptions: MetricOption[] = [
  {
    value: 'CUSTOMER_SOURCE',
    label: '客户来源',
    description: '查看客户来源结构，更适合用饼图观察来源占比。',
    chartType: 'PIE_CHART',
    defaultRange: 'THIS_YEAR',
  },
  {
    value: 'PAYMENT_STATUS',
    label: '回款状态',
    description: '查看已确认与待确认回款的结构占比。',
    chartType: 'PIE_CHART',
    defaultRange: 'THIS_MONTH',
  },
  {
    value: 'PAYMENT_AMOUNT',
    label: '回款金额',
    description: '跟踪时间范围内的回款金额走势。',
    chartType: 'LINE_CHART',
    defaultRange: 'THIS_YEAR',
  },
  {
    value: 'PAYMENT_COUNT',
    label: '回款笔数',
    description: '查看回款笔数变化趋势，适合按周或按月观察。',
    chartType: 'LINE_CHART',
    defaultRange: 'THIS_YEAR',
  },
  {
    value: 'CONTRACT_NUM',
    label: '合同数量',
    description: '查看合同签约数量在不同时间范围内的变化。',
    chartType: 'LINE_CHART',
    defaultRange: 'THIS_YEAR',
  },
  {
    value: 'PERFORMANCE',
    label: '业绩表现',
    description: '综合查看当前时间范围内的业务表现趋势。',
    chartType: 'LINE_CHART',
    defaultRange: 'THIS_YEAR',
  },
];

const timeRangeOptions: Array<{ label: string; value: StatisticRange }> = [
  { label: '本周', value: 'THIS_WEEK' },
  { label: '本月', value: 'THIS_MONTH' },
  { label: '本年', value: 'THIS_YEAR' },
  { label: '自定义', value: 'CUSTOM_RANGE' },
];

const ARangePicker = ADatePicker.RangePicker;
const selectedMetric = ref<StatisticMetric>('CUSTOMER_SOURCE');
const selectedTimeRange = ref<StatisticRange>('THIS_YEAR');
const customRange = ref<[Dayjs, Dayjs]>();
const chartRef = ref<HTMLDivElement>();

const selectedMetricOption = computed(
  () =>
    metricOptions.find((item) => item.value === selectedMetric.value) ??
    metricOptions[0],
);

const payload = computed(() => {
  if (selectedTimeRange.value === 'CUSTOM_RANGE') {
    if (!customRange.value?.[0] || !customRange.value?.[1]) {
      return null;
    }

    return {
      dataType: selectedMetric.value,
      timeRange: selectedTimeRange.value,
      chartType: selectedMetricOption.value.chartType,
      startTime: customRange.value[0].format('YYYY-MM-DD HH:mm:ss'),
      endTime: customRange.value[1].format('YYYY-MM-DD HH:mm:ss'),
    };
  }

  return {
    dataType: selectedMetric.value,
    timeRange: selectedTimeRange.value,
    chartType: selectedMetricOption.value.chartType,
  };
});

const {
  data: chartData,
  isLoading,
  refetch,
  isRefetching,
} = useStatisticsChartData(payload);

const handleRefetchClick = () => {
  void refetch();
};

const chartEntries = computed(() => {
  const rawData = chartData.value?.data;

  if (!rawData || typeof rawData !== 'object') {
    return [];
  }

  return Object.entries(rawData)
    .map(([name, rawValue]) => {
      const parsedValue =
        typeof rawValue === 'number' ? rawValue : Number(rawValue ?? 0);

      return {
        name,
        value: Number.isFinite(parsedValue) ? parsedValue : 0,
      };
    })
    .filter((item) => item.name);
});

const normalizedChartType = computed(() => {
  const chartType = chartData.value?.chartType?.toLowerCase();

  if (chartType?.includes('pie')) {
    return 'pie';
  }

  return 'line';
});

watch(
  selectedMetric,
  (nextMetric) => {
    const nextMetricOption =
      metricOptions.find((item) => item.value === nextMetric) ??
      metricOptions[0];

    selectedTimeRange.value = nextMetricOption.defaultRange;
    customRange.value = undefined;
  },
  { flush: 'sync' },
);

useEChart(chartRef, {
  getOption: () => {
    if (!chartEntries.value.length) {
      return {};
    }

    if (normalizedChartType.value === 'pie') {
      const pieOption: EChartsCoreOption = {
        title: {
          text: chartData.value?.title || selectedMetricOption.value.label,
          left: 'center',
          top: 8,
        },
        tooltip: {
          trigger: 'item',
          formatter: '{b}: {c} ({d}%)',
          confine: true,
        },
        legend: {
          bottom: 0,
        },
        series: [
          {
            type: 'pie',
            radius: ['42%', '72%'],
            center: ['50%', '48%'],
            data: chartEntries.value,
            label: {
              formatter: '{b}: {c}',
            },
          },
        ],
      };

      return pieOption;
    }

    const lineOption: EChartsCoreOption = {
      title: {
        text: chartData.value?.title || selectedMetricOption.value.label,
        left: 'center',
        top: 8,
      },
      tooltip: {
        trigger: 'axis',
        confine: true,
      },
      grid: {
        left: 48,
        right: 24,
        top: 56,
        bottom: 36,
      },
      xAxis: {
        type: 'category',
        name: chartData.value?.xAxisLabel || '',
        data: chartEntries.value.map((item) => item.name),
      },
      yAxis: {
        type: 'value',
        name: chartData.value?.yAxisLabel || '',
      },
      series: [
        {
          type: 'line',
          smooth: true,
          symbolSize: 8,
          data: chartEntries.value.map((item) => item.value),
          areaStyle: {
            opacity: 0.08,
          },
          lineStyle: {
            width: 3,
          },
        },
      ],
    };

    return lineOption;
  },
});

if (!customRange.value) {
  customRange.value = [
    dayjs().startOf('month'),
    dayjs().endOf('month'),
  ];
}
</script>
