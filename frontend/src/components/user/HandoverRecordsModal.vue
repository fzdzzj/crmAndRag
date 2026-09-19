<script setup lang="ts">
import { useHandoverRecords } from '@/hooks/useHandover';
import { useQueryUsers } from '@/hooks/useUser';
import type { PostUserFindResponse, PostUserHandoverQueryData } from '@/api/axios';
import { computed, ref } from 'vue';
import {
  Button as AButton,
  DatePicker as ADatePicker,
  Modal as AModal,
  Select as ASelect,
  Table as ATable,
} from 'ant-design-vue';
import type { Dayjs } from 'dayjs';

const ARangePicker = ADatePicker.RangePicker;

type UserRecord = NonNullable<NonNullable<PostUserFindResponse['data']>['records']>[number];
type UserHandoverQueryParams = NonNullable<PostUserHandoverQueryData['body']>;

const innerOpen = ref(false);
const current = ref(1);
const pageSize = ref(10);

const fromUserId = ref<number | undefined>(undefined);
const toUserId = ref<number | undefined>(undefined);
const dateRange = ref<[Dayjs, Dayjs]>();

const params = computed<UserHandoverQueryParams>(() => ({
  fromUserId: fromUserId.value,
  toUserId: toUserId.value,
  startTime: dateRange.value?.[0]?.format('YYYY-MM-DD HH:mm:ss'),
  endTime: dateRange.value?.[1]?.format('YYYY-MM-DD HH:mm:ss'),
  pageNum: current.value,
  pageSize: pageSize.value,
}));

const { data: records, isLoading } = useHandoverRecords(params);
const { data: allUsers, isLoading: isLoadingUsers } = useQueryUsers({ all: true });

const userOptions = computed(() =>
  (allUsers.value?.records ?? []).map((user: UserRecord) => ({
    value: user.id,
    label: user.realName || `用户#${user.id}`,
  })),
);

const columns = [
  { title: '交接用户', dataIndex: 'fromUserName', key: 'fromUserName' },
  { title: '接收用户', dataIndex: 'toUserName', key: 'toUserName' },
  { title: '任务数', dataIndex: 'taskCount', key: 'taskCount' },
  { title: '客户数', dataIndex: 'customerCount', key: 'customerCount' },
  { title: '销售机会数', dataIndex: 'opportunityCount', key: 'opportunityCount' },
  { title: '交接时间', dataIndex: 'handoverTime', key: 'handoverTime' },
  { title: '操作人', dataIndex: 'operatorName', key: 'operatorName' },
  { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
];

const open = () => {
  fromUserId.value = undefined;
  toUserId.value = undefined;
  dateRange.value = undefined;
  current.value = 1;
  pageSize.value = 10;
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
};

const resetFilters = () => {
  fromUserId.value = undefined;
  toUserId.value = undefined;
  dateRange.value = undefined;
  current.value = 1;
};

const handleTableChange = (pagination: { current?: number; pageSize?: number }) => {
  current.value = pagination.current ?? 1;
  pageSize.value = pagination.pageSize ?? 10;
};

defineExpose({ open, close });
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="交接记录"
    width="1000px"
    :footer="null"
    @cancel="close"
  >
    <div class="mb-4 flex flex-wrap items-end gap-3">
      <div class="flex flex-col gap-1">
        <span class="text-xs text-slate-500">交接用户</span>
        <a-select
          v-model:value="fromUserId"
          :loading="isLoadingUsers"
          placeholder="全部"
          allow-clear
          show-search
          option-filter-prop="label"
          :options="userOptions"
          class="w-44"
        />
      </div>
      <div class="flex flex-col gap-1">
        <span class="text-xs text-slate-500">接收用户</span>
        <a-select
          v-model:value="toUserId"
          :loading="isLoadingUsers"
          placeholder="全部"
          allow-clear
          show-search
          option-filter-prop="label"
          :options="userOptions"
          class="w-44"
        />
      </div>
      <div class="flex flex-col gap-1">
        <span class="text-xs text-slate-500">交接时间</span>
        <a-range-picker
          v-model:value="dateRange"
          :show-time="false"
          format="YYYY-MM-DD"
          class="w-60"
        />
      </div>
      <a-button @click="resetFilters">重置</a-button>
    </div>

    <a-table
      :scroll="{ x: 'max-content' }"
      :columns="columns"
      :data-source="records?.records ?? []"
      :loading="isLoading"
      :pagination="{
        current,
        pageSize,
        total: records?.total ?? 0,
        showSizeChanger: true,
        showQuickJumper: true,
        showTotal: (total: number) => `共 ${total} 条`,
      }"
      row-key="id"
      size="small"
      @change="handleTableChange"
    />
  </a-modal>
</template>
