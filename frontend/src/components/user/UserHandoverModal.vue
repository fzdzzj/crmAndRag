<script setup lang="ts">
import type {
  GetUserHandoverStatisticsByUserIdResponse,
  PostUserFindResponse,
  PostUserHandoverExecuteData,
  PostUserHandoverExecuteResponse,
} from '@/api/axios';
import {
  useExecuteUserHandover,
  useQueryUsers,
  useUserHandoverRecords,
  useUserHandoverStatistics,
} from '@/hooks/useUser';
import { computed, ref } from 'vue';
import {
  Alert as AAlert,
  Card as ACard,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Modal as AModal,
  Select as ASelect,
  Spin,
  Table as ATable,
  Textarea as ATextarea,
  message,
} from 'ant-design-vue';

const emit = defineEmits<{
  success: [];
}>();

type UserRecord = NonNullable<NonNullable<PostUserFindResponse['data']>['records']>[number];
type UserHandoverPayload = NonNullable<PostUserHandoverExecuteData['body']>;
type UserHandoverResult = NonNullable<PostUserHandoverExecuteResponse['data']>;
type HandoverStatisticsItem = NonNullable<GetUserHandoverStatisticsByUserIdResponse['data']>[number];

const innerOpen = ref(false);
const currentUser = ref<UserRecord>();
const form = ref<UserHandoverPayload>({});

const modalEnabled = computed(() => innerOpen.value);
const fromUserId = computed(() => form.value.fromUserId);

const { data: allUsers, isLoading: isLoadingUsers } = useQueryUsers({ all: true });
const { data: handoverStatistics, isLoading: isLoadingStatistics } =
  useUserHandoverStatistics(fromUserId, modalEnabled);
const {
  data: handoverHistory,
  isLoading: isLoadingHistory,
  current,
  pageSize,
} = useUserHandoverRecords(fromUserId, modalEnabled);
const { mutate: executeHandover, isPending: isSubmitting } =
  useExecuteUserHandover();

const summaryBase = [
  { type: 'task', typeName: '任务' },
  { type: 'customer', typeName: '客户' },
  { type: 'opportunity', typeName: '销售机会' },
];

const summaryCards = computed(() => {
  const statisticsMap = new Map(
    (handoverStatistics.value ?? []).map((item: HandoverStatisticsItem) => [
      item.type ?? '',
      item,
    ]),
  );

  return summaryBase.map((item) => {
    const matched = statisticsMap.get(item.type);

    return {
      ...item,
      typeName: matched?.typeName || item.typeName,
      count: matched?.count ?? 0,
    };
  });
});

const totalResources = computed(() =>
  summaryCards.value.reduce((total, item) => total + item.count, 0),
);

const availableUsers = computed(
  () =>
    (allUsers.value?.records?.filter(
      (user) => user.id !== form.value.fromUserId && user.status === 1,
    ) ?? []),
);

const fromUserDisplayName = computed(() => {
  if (!currentUser.value) {
    return '-';
  }

  return currentUser.value.realName || `用户#${currentUser.value.id}`;
});

const canSubmit = computed(
  () =>
    !!form.value.fromUserId &&
    !!form.value.toUserId &&
    form.value.fromUserId !== form.value.toUserId,
);

const historyColumns = [
  { title: '接收人', dataIndex: 'toUserName', key: 'toUserName' },
  { title: '任务', dataIndex: 'taskCount', key: 'taskCount' },
  { title: '客户', dataIndex: 'customerCount', key: 'customerCount' },
  { title: '销售机会', dataIndex: 'opportunityCount', key: 'opportunityCount' },
  { title: '交接时间', dataIndex: 'handoverTime', key: 'handoverTime' },
  { title: '操作人', dataIndex: 'operatorName', key: 'operatorName' },
];

const open = (user: UserRecord) => {
  currentUser.value = user;
  form.value = {
    fromUserId: user.id,
    toUserId: undefined,
    remark: '',
  };
  current.value = 1;
  pageSize.value = 5;
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  currentUser.value = undefined;
  form.value = {};
};

defineExpose({ open, close });

const submit = () => {
  if (!canSubmit.value) {
    message.warning('请选择有效的接收用户');
    return;
  }

  executeHandover(form.value, {
    onSuccess: (result: UserHandoverResult | undefined) => {
      message.success(
        `交接完成，已转移 ${result?.taskCount ?? 0} 个任务、${result?.customerCount ?? 0} 个客户、${result?.opportunityCount ?? 0} 个销售机会`,
      );
      emit('success');
      close();
    },
    onError: (error: unknown) => {
      message.error(error instanceof Error ? error.message : '交接失败');
    },
  });
};

const handleHistoryTableChange = (pagination: {
  current?: number;
  pageSize?: number;
}) => {
  current.value = pagination.current ?? 1;
  pageSize.value = pagination.pageSize ?? 5;
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="离职交接"
    width="900px"
    ok-text="确认交接"
    cancel-text="取消"
    :confirm-loading="isSubmitting"
    :ok-button-props="{ disabled: !canSubmit }"
    @ok="submit"
    @cancel="close"
  >
    <a-alert
      type="warning"
      show-icon
      class="mb-4"
      message="执行后会将该用户名下的联络任务、客户和销售机会转移给接收用户。"
    />

    <a-form
      :model="form"
      :label-col="{ span: 5 }"
      :wrapper-col="{ span: 18 }"
      layout="horizontal"
      class="mb-4"
    >
      <a-form-item label="离职用户">
        <a-input :value="fromUserDisplayName" disabled />
      </a-form-item>
      <a-form-item label="接收用户">
        <a-select
          v-model:value="form.toUserId"
          :loading="isLoadingUsers"
          placeholder="请选择接收用户"
          show-search
          option-filter-prop="label"
          :options="
            availableUsers.map((user) => ({
              value: user.id,
              label: user.realName || `用户#${user.id}`,
            }))
          "
        />
      </a-form-item>
      <a-form-item label="交接备注">
        <a-textarea
          v-model:value="form.remark"
          :rows="3"
          placeholder="可选，记录本次交接的背景或说明"
        />
      </a-form-item>
    </a-form>

    <a-card title="待交接资源" size="small" class="mb-4">
      <Spin :spinning="isLoadingStatistics">
        <div class="mb-3 text-sm text-slate-500">
          当前共检测到 {{ totalResources }} 项可交接资源。
        </div>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
          <div
            v-for="item in summaryCards"
            :key="item.type"
            class="rounded-md border border-slate-200 bg-slate-50 px-4 py-3"
          >
            <div class="text-sm text-slate-500">{{ item.typeName }}</div>
            <div class="mt-1 text-2xl font-semibold text-slate-800">
              {{ item.count }}
            </div>
          </div>
        </div>
      </Spin>
    </a-card>

    <a-card title="最近交接记录" size="small">
      <a-table
        :scroll="{ x: 'max-content' }"
        :columns="historyColumns"
        :data-source="handoverHistory?.records ?? []"
        :loading="isLoadingHistory"
        :pagination="{
          current,
          pageSize,
          total: handoverHistory?.total ?? 0,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
        }"
        row-key="id"
        size="small"
        @change="handleHistoryTableChange"
      />
    </a-card>
  </a-modal>
</template>
