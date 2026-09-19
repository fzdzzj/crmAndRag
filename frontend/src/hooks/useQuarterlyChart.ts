import { watch, type Ref, nextTick, onUnmounted } from 'vue';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import {
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsCoreOption, ECharts } from 'echarts/core';

// 注册需要的组件
echarts.use([BarChart, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer]);

interface QuarterlyChartData {
  categories: string[];
  invoiceAmounts: number[];
  paymentAmounts: number[];
}

interface UseQuarterlyChartOptions {
  chartData: Ref<QuarterlyChartData>;
  isLoading: Ref<boolean>;
}

export function useQuarterlyChart(
  containerRef: Ref<HTMLDivElement | undefined>,
  options: UseQuarterlyChartOptions,
) {
  const { chartData, isLoading } = options;

  // 图表实例
  let chartInstance: ECharts | null = null;
  let resizeObserver: ResizeObserver | null = null;

  /**
   * 监听容器尺寸变化：容器出现尺寸后初始化，之后自动 resize
   */
  const startResizeObserver = () => {
    if (resizeObserver || !containerRef.value) return;
    resizeObserver = new ResizeObserver(() => {
      if (!chartInstance) {
        if (
          containerRef.value?.clientWidth &&
          containerRef.value.clientHeight
        ) {
          initChart();
        }
        return;
      }
      chartInstance.resize();
    });
    resizeObserver.observe(containerRef.value);
  };

  /**
   * 初始化图表
   */
  const initChart = () => {
    if (!containerRef.value) return;

    // 如果已存在实例，先销毁
    if (chartInstance) {
      chartInstance.dispose();
    }

    chartInstance = echarts.init(containerRef.value);

    const option: EChartsCoreOption = {
      tooltip: {
        trigger: 'axis',
        confine: true,
        axisPointer: {
          type: 'shadow',
        },
      },
      legend: {
        data: ['发票金额', '回款金额'],
        top: 10,
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        containLabel: true,
      },
      xAxis: {
        type: 'category',
        data: chartData.value.categories,
      },
      yAxis: {
        type: 'value',
        name: '金额（元）',
        axisLabel: {
          formatter: (value: number) => {
            if (value >= 10000) {
              return (value / 10000).toFixed(0) + '万';
            }
            return value.toString();
          },
        },
      },
      series: [
        {
          name: '发票金额',
          type: 'bar',
          data: chartData.value.invoiceAmounts,
          itemStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: '#83bff6' },
                { offset: 1, color: '#188df0' },
              ],
            },
          },
        },
        {
          name: '回款金额',
          type: 'bar',
          data: chartData.value.paymentAmounts,
          itemStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: '#7dd3c0' },
                { offset: 1, color: '#42b883' },
              ],
            },
          },
        },
      ],
    };

    chartInstance.setOption(option);
    startResizeObserver();
  };

  /**
   * 更新图表数据
   */
  const updateChart = () => {
    if (!chartInstance) return;

    chartInstance.setOption({
      xAxis: {
        data: chartData.value.categories,
      },
      series: [
        {
          data: chartData.value.invoiceAmounts,
        },
        {
          data: chartData.value.paymentAmounts,
        },
      ],
    });
  };

  // 监听数据变化，更新图表
  watch(chartData, updateChart);

  // 监听 loading 状态，loading 结束后初始化图表
  watch(
    isLoading,
    async (newVal) => {
      if (!newVal) {
        await nextTick();
        if (!containerRef.value?.clientWidth) {
          startResizeObserver();
          return;
        }
        void initChart();
      }
    },
    { immediate: true },
  );

  onUnmounted(() => {
    resizeObserver?.disconnect();
    resizeObserver = null;
    chartInstance?.dispose();
    chartInstance = null;
  });

  return {
    initChart,
    updateChart,
  };
}
