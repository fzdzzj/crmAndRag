import { onMounted, onUnmounted, watch, type Ref, nextTick } from 'vue';
import * as echarts from 'echarts/core';
import {
  PieChart,
  GraphChart,
  FunnelChart,
  BarChart,
  LineChart,
} from 'echarts/charts';
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { ECharts, EChartsCoreOption, EChartsInitOpts } from 'echarts/core';

// 注册所有需要的组件
echarts.use([
  PieChart,
  GraphChart,
  FunnelChart,
  BarChart,
  LineChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  CanvasRenderer,
]);

interface UseEChartOptions {
  /**
   * 图表配置项生成函数
   */
  getOption: () => EChartsCoreOption;
  /**
   * 是否自动监听数据变化
   * @default true
   */
  autoWatch?: boolean;
  /**
   * ECharts 初始化配置
   */
  theme?: string | object;
  initOptions?: EChartsInitOpts;
}

/**
 * ECharts Hook
 * @param containerRef 图表容器 ref
 * @param options 配置选项
 * @returns
 */
export function useEChart(
  containerRef: Ref<HTMLDivElement | undefined>,
  options: UseEChartOptions,
) {
  const { getOption, autoWatch = true, theme, initOptions } = options;

  // 图表实例
  let chartInstance: ECharts | null = null;
  let resizeObserver: ResizeObserver | null = null;

  /**
   * 检查容器是否有尺寸
   */
  function checkContainerSize(): boolean {
    if (!containerRef.value) return false;
    const { clientWidth, clientHeight } = containerRef.value;
    return clientWidth > 0 && clientHeight > 0;
  }

  /**
   * 监听容器尺寸变化：有尺寸前等待初始化，初始化后自动 resize
   * 覆盖侧边栏折叠、v-show 显隐、窗口/设备宽度变化等场景
   */
  function startResizeObserver() {
    if (resizeObserver || !containerRef.value) return;
    resizeObserver = new ResizeObserver(() => {
      if (!chartInstance) {
        // 容器从无尺寸变为有尺寸（如 v-show 显示）时再初始化
        if (checkContainerSize()) {
          initChart();
        }
        return;
      }
      chartInstance.resize();
    });
    resizeObserver.observe(containerRef.value);
  }

  /**
   * 初始化图表
   */
  function initChart() {
    if (!containerRef.value) {
      console.warn('[useEChart] Chart container not found');
      return false;
    }

    if (!checkContainerSize()) {
      console.warn('[useEChart] Container has no size, waiting for layout...');
      // 等待容器出现尺寸后再初始化
      startResizeObserver();
      return false;
    }

    if (chartInstance) {
      chartInstance.dispose();
    }

    chartInstance = echarts.init(containerRef.value, theme, initOptions);
    updateChart();
    startResizeObserver();
    return true;
  }

  /**
   * 更新图表
   */
  function updateChart() {
    if (!chartInstance) {
      console.warn('[useEChart] Chart instance not initialized');
      return;
    }

    const option = getOption();
    chartInstance.setOption(option, true);
  }

  /**
   * 调整图表大小
   */
  function resizeChart() {
    if (chartInstance) {
      chartInstance.resize();
    }
  }

  /**
   * 销毁图表
   */
  function disposeChart() {
    if (chartInstance) {
      chartInstance.dispose();
      chartInstance = null;
    }
  }

  // 组件挂载时初始化图表
  onMounted(() => {
    void nextTick(() => {
      initChart();
    });
  });

  // 组件卸载时销毁图表
  onUnmounted(() => {
    resizeObserver?.disconnect();
    resizeObserver = null;
    disposeChart();
  });

  // 自动监听数据变化
  if (autoWatch) {
    watch(
      () => getOption(),
      () => {
        void nextTick(() => {
          if (!chartInstance) {
            initChart();
          } else {
            updateChart();
          }
        });
      },
      { deep: true },
    );
  }

  return {
    /**
     * 手动初始化图表
     */
    initChart,
    /**
     * 手动更新图表
     */
    updateChart,
    /**
     * 调整图表大小
     */
    resizeChart,
    /**
     * 销毁图表
     */
    disposeChart,
    /**
     * 获取图表实例
     */
    getInstance: () => chartInstance,
  };
}
