<template>
  <div class="flex w-full flex-col gap-4">
    <Card>
      <div class="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 class="m-0 text-lg font-medium">AI 用量</h2>
          <p class="mb-0 mt-1 text-sm text-gray-500">
            按用户查看 AI token 消耗（超管专享，权限 810 · AI_TOKEN_USAGE_VIEW）
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <DatePicker
            v-model:value="startDay"
            show-time
            :allow-clear="false"
            placeholder="开始时间"
          />
          <span class="text-gray-400">至</span>
          <DatePicker
            v-model:value="endDay"
            show-time
            :allow-clear="false"
            placeholder="结束时间"
          />
          <Select
            v-model:value="usageType"
            :options="typeOptions"
            style="width: 190px"
            placeholder="全部计量类型"
          />
          <Button :loading="isFetching" @click="refresh">刷新</Button>
        </div>
      </div>
    </Card>

    <Alert
      type="warning"
      show-icon
      message="口径提醒：embedding / 视觉联合 embedding 端点不回流 usage，token 恒 0——0 代表「未知」，不是免费；判断真实调用量请看「调用次数」与「失败次数」。"
      description="依据：docs/token-metering.md §1（问题）与 §6（未解决项）。"
    />

    <div class="grid grid-cols-2 gap-4 md:grid-cols-4">
      <Card v-for="item in statItems" :key="item.title">
        <Statistic :title="item.title" :value="item.value" />
      </Card>
    </div>

    <Card v-if="typeShares.length">
      <template #title>按计量类型（总 token 占比）</template>
      <div class="flex flex-wrap gap-2">
        <Tag v-for="share in typeShares" :key="share.usageType" color="blue">
          {{ share.usageType }} · {{ formatNumber(share.totalTokens) }} token ·
          {{ formatNumber(share.calls) }} 次 · {{ formatPct(share.sharePct) }}
        </Tag>
      </div>
    </Card>

    <Card>
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="isLoading"
        :pagination="pagination"
        :scroll="{ x: 1100 }"
        row-key="userRef"
        data-tour="token-usage-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'userName'">
            <span class="font-medium">{{ record.userName }}</span>
            <Tag v-if="record.userId == null" class="ml-2" color="default"
              >非实名</Tag
            >
          </template>
          <template v-else-if="column.key === 'promptTokens'">
            {{ formatNumber(record.promptTokens) }}
          </template>
          <template v-else-if="column.key === 'completionTokens'">
            {{ formatNumber(record.completionTokens) }}
          </template>
          <template v-else-if="column.key === 'totalTokens'">
            {{ formatNumber(record.totalTokens) }}
          </template>
          <template v-else-if="column.key === 'calls'">
            {{ formatNumber(record.calls) }}
          </template>
          <template v-else-if="column.key === 'failedCalls'">
            {{ formatNumber(record.failedCalls) }}
          </template>
          <template v-else-if="column.key === 'sharePct'">
            {{ formatPct(record.sharePct) }}
          </template>
          <template v-else-if="column.key === 'lastCallTime'">
            {{ formatTime(record.lastCallTime) }}
          </template>
        </template>
      </Table>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Select,
  Statistic,
  Table,
  Tag,
} from 'ant-design-vue';
import type { TableColumnType } from 'ant-design-vue';
import dayjs, { type Dayjs } from 'dayjs';
import {
  TOKEN_USAGE_TYPES,
  useQueryTokenUsageByUser,
  useQueryTokenUsageSummary,
  type TokenUsageByUserRow,
} from '@/hooks/useTokenUsage';

const DEFAULT_DAYS = 30;
const MAX_PAGE_SIZE = 200;

const startDay = ref<Dayjs | string>(dayjs().subtract(DEFAULT_DAYS, 'day'));
const endDay = ref<Dayjs | string>(dayjs());
const usageType = ref<string | undefined>(undefined);
const pageNum = ref(1);
const pageSize = ref(20);

const typeOptions = [
  { value: '', label: '全部计量类型' },
  ...TOKEN_USAGE_TYPES.map((type) => ({
    value: type.value,
    label: type.label,
  })),
];

/** 实际生效的查询窗口（清空日期时回退到缺省近 30 天，与后端缺省口径一致）。 */
const queryParams = computed(() => ({
  startTime: (startDay.value
    ? dayjs(startDay.value)
    : dayjs().subtract(DEFAULT_DAYS, 'day')
  ).format('YYYY-MM-DDTHH:mm:ss'),
  endTime: (endDay.value ? dayjs(endDay.value) : dayjs()).format(
    'YYYY-MM-DDTHH:mm:ss',
  ),
  usageType: usageType.value || undefined,
  pageNum: pageNum.value,
  pageSize: pageSize.value,
}));

const summaryParams = computed(() => ({
  startTime: queryParams.value.startTime,
  endTime: queryParams.value.endTime,
  usageType: queryParams.value.usageType,
}));

const {
  data: pageData,
  isLoading,
  isFetching,
  refetch,
} = useQueryTokenUsageByUser(queryParams);
const { data: summaryData } = useQueryTokenUsageSummary(summaryParams);

const rows = computed<TokenUsageByUserRow[]>(
  () => pageData.value?.records ?? [],
);
const total = computed(() => pageData.value?.total ?? 0);
const typeShares = computed(() => summaryData.value?.usageTypes ?? []);

const statItems = computed(() => [
  { title: '总 token', value: summaryData.value?.totalTokens ?? 0 },
  { title: '调用次数（含失败）', value: summaryData.value?.calls ?? 0 },
  { title: '失败次数', value: summaryData.value?.failedCalls ?? 0 },
  { title: '用户数', value: summaryData.value?.userCount ?? 0 },
]);

const columns: TableColumnType<TokenUsageByUserRow>[] = [
  { title: '用户', key: 'userName', dataIndex: 'userName', width: 170 },
  { title: '用户引用', key: 'userRef', dataIndex: 'userRef', width: 140 },
  { title: '输入 token', key: 'promptTokens', align: 'right', width: 120 },
  { title: '输出 token', key: 'completionTokens', align: 'right', width: 120 },
  { title: '总 token', key: 'totalTokens', align: 'right', width: 120 },
  { title: '调用次数', key: 'calls', align: 'right', width: 110 },
  { title: '失败次数', key: 'failedCalls', align: 'right', width: 110 },
  { title: '占比', key: 'sharePct', align: 'right', width: 90 },
  { title: '最近调用', key: 'lastCallTime', width: 180 },
];

const pagination = computed(() => ({
  current: pageNum.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50', '100', String(MAX_PAGE_SIZE)],
  showQuickJumper: true,
  showTotal: (value: number) => `共 ${value} 个用户`,
  onChange: (page: number, size: number) => {
    pageNum.value = page;
    pageSize.value = Math.min(size, MAX_PAGE_SIZE);
  },
}));

watch([startDay, endDay, usageType], () => {
  pageNum.value = 1;
});

function refresh() {
  void refetch();
}

function formatNumber(value: number | null | undefined) {
  return (value ?? 0).toLocaleString('zh-CN');
}

function formatPct(value: number | null | undefined) {
  return `${(value ?? 0).toFixed(2)}%`;
}

function formatTime(value: string | null | undefined) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}
</script>
