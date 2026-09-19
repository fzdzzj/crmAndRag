import apiClient from '@/api/apiClient';
import {
  getDataStatisticsOpportunityStageDistribution,
  postDataStatisticsChartData,
  postDataStatisticsSummary,
} from '@/api/axios';
import type {
  GetDataStatisticsOpportunityStageDistributionResponse,
  PostDataStatisticsChartDataData,
  PostDataStatisticsChartDataResponse,
  PostDataStatisticsSummaryResponse,
} from '@/api/axios';
import { useQuery } from '@tanstack/vue-query';
import { computed, type Ref } from 'vue';

const OPPORTUNITY_STAGE_QUERY_KEY = 'opportunity-stage-distribution';
const QUARTERLY_SUMMARY_QUERY_KEY = 'quarterly-summary';
const CHART_DATA_QUERY_KEY = 'statistics-chart-data';

export type DataStatisticsDTO = NonNullable<PostDataStatisticsChartDataData['body']>;
export type OpportunityStageDistributionVO = NonNullable<GetDataStatisticsOpportunityStageDistributionResponse['data']>;
export type StatisticsSummaryVO = NonNullable<PostDataStatisticsSummaryResponse['data']>;
export type StatisticsChartDataVO = NonNullable<PostDataStatisticsChartDataResponse['data']>;

export function useOpportunityStageDistribution() {
  const query = useQuery({
    queryKey: [OPPORTUNITY_STAGE_QUERY_KEY],
    queryFn: () => getDataStatisticsOpportunityStageDistribution({ client: apiClient }),
    select: (res) => res.data?.data,
  });

  const stageConfig = computed(() => [
    { key: 'stage0', label: '种子商机', color: '#667eea' },
    { key: 'stage1', label: '潜在商机', color: '#f093fb' },
    { key: 'stage2', label: '确认商机', color: '#4facfe' },
    { key: 'stage3', label: '储备项目', color: '#43e97b' },
    { key: 'stage4', label: '立项签约', color: '#fa709a' },
    { key: 'stage5', label: '关闭', color: '#30cfd0' },
  ]);

  const stageCards = computed(() => {
    const data = query.data.value as Record<string, unknown> | undefined;
    if (!data) return [];

    return stageConfig.value.map((stage) => {
      const countKey = `${stage.key}Count`;
      const percentageKey = `${stage.key}Percentage`;

      return {
        key: stage.key,
        label: stage.label,
        count: data[countKey] ?? 0,
        percentage: data[percentageKey] ?? 0,
        color: stage.color,
      };
    });
  });

  const chartData = computed(() => {
    const data = query.data.value as Record<string, unknown> | undefined;
    if (!data) return [];

    return stageConfig.value.map((stage) => {
      const countKey = `${stage.key}Count`;

      return {
        name: stage.label,
        value: data[countKey] ?? 0,
        itemStyle: { color: stage.color },
      };
    });
  });

  return {
    ...query,
    stageCards,
    chartData,
  };
}

export function useQuarterlySummary(yearRef?: Ref<number>) {
  const currentYear = computed(() => yearRef?.value ?? new Date().getFullYear());

  const quarters = computed(
    () =>
      [
        {
          name: 'Q1',
          label: '第一季度',
          startTime: `${currentYear.value}-01-01 00:00:00`,
          endTime: `${currentYear.value}-03-31 23:59:59`,
        },
        {
          name: 'Q2',
          label: '第二季度',
          startTime: `${currentYear.value}-04-01 00:00:00`,
          endTime: `${currentYear.value}-06-30 23:59:59`,
        },
        {
          name: 'Q3',
          label: '第三季度',
          startTime: `${currentYear.value}-07-01 00:00:00`,
          endTime: `${currentYear.value}-09-30 23:59:59`,
        },
        {
          name: 'Q4',
          label: '第四季度',
          startTime: `${currentYear.value}-10-01 00:00:00`,
          endTime: `${currentYear.value}-12-31 23:59:59`,
        },
      ] as const,
  );

  const q1Query = useQuery({
    queryKey: [QUARTERLY_SUMMARY_QUERY_KEY, currentYear, 'Q1'],
    queryFn: () =>
      postDataStatisticsSummary({
        client: apiClient,
        body: {
          startTime: quarters.value[0].startTime,
          endTime: quarters.value[0].endTime,
        },
      }),
    select: (res) => res.data?.data,
  });

  const q2Query = useQuery({
    queryKey: [QUARTERLY_SUMMARY_QUERY_KEY, currentYear, 'Q2'],
    queryFn: () =>
      postDataStatisticsSummary({
        client: apiClient,
        body: {
          startTime: quarters.value[1].startTime,
          endTime: quarters.value[1].endTime,
        },
      }),
    select: (res) => res.data?.data,
  });

  const q3Query = useQuery({
    queryKey: [QUARTERLY_SUMMARY_QUERY_KEY, currentYear, 'Q3'],
    queryFn: () =>
      postDataStatisticsSummary({
        client: apiClient,
        body: {
          startTime: quarters.value[2].startTime,
          endTime: quarters.value[2].endTime,
        },
      }),
    select: (res) => res.data?.data,
  });

  const q4Query = useQuery({
    queryKey: [QUARTERLY_SUMMARY_QUERY_KEY, currentYear, 'Q4'],
    queryFn: () =>
      postDataStatisticsSummary({
        client: apiClient,
        body: {
          startTime: quarters.value[3].startTime,
          endTime: quarters.value[3].endTime,
        },
      }),
    select: (res) => res.data?.data,
  });

  const chartData = computed(() => {
    const q1 = q1Query.data.value;
    const q2 = q2Query.data.value;
    const q3 = q3Query.data.value;
    const q4 = q4Query.data.value;

    return {
      categories: ['Q1', 'Q2', 'Q3', 'Q4'],
      invoiceAmounts: [
        q1?.totalContractAmount ?? 0,
        q2?.totalContractAmount ?? 0,
        q3?.totalContractAmount ?? 0,
        q4?.totalContractAmount ?? 0,
      ],
      paymentAmounts: [
        q1?.totalPaymentAmount ?? 0,
        q2?.totalPaymentAmount ?? 0,
        q3?.totalPaymentAmount ?? 0,
        q4?.totalPaymentAmount ?? 0,
      ],
    };
  });

  const isLoading = computed(
    () =>
      q1Query.isLoading.value ||
      q2Query.isLoading.value ||
      q3Query.isLoading.value ||
      q4Query.isLoading.value,
  );

  return {
    chartData,
    isLoading,
    quarters,
  };
}

export function useStatisticsChartData(payloadRef: Ref<unknown>) {
  return useQuery({
    queryKey: computed(() => [CHART_DATA_QUERY_KEY, payloadRef.value]),
    enabled: computed(() => !!payloadRef.value),
    queryFn: async () => {
      const res = await postDataStatisticsChartData({
        client: apiClient,
        body: payloadRef.value as DataStatisticsDTO,
      });
      return res.data?.data;
    },
  });
}
