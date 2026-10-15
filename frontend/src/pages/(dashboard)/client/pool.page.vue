<template>
  <div class="w-full">
    <Spin :spinning="isLoading" tip="Loading...">
      <Table
        data-tour="client-pool-table"
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="poolColumns"
        :data-source="poolData?.records ?? []"
        :row-key="(record: PoolListItem) => record.id"
        :pagination="{
          current: current,
          pageSize: pageSize,
          total: poolData?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex items-center justify-between">
            <h2>客户公海池</h2>
            <div class="flex flex-wrap gap-2">
              <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                <Button :loading="isRecycling" @click="triggerRecycle">手动回收超时客户</Button>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_QUERY_COMPANY']">
                <Button @click="refetch()">刷新</Button>
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <span class="inline-flex items-center gap-2">
              <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                <Button type="link" size="small" @click="onClaim(record as PoolListItem)">
                  认领
                </Button>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                <Button type="link" size="small" @click="openAssign(record as PoolListItem)">
                  分派
                </Button>
              </Permission>
            </span>
          </template>
        </template>
      </Table>
    </Spin>

    <Modal
      v-model:open="assignVisible"
      title="分派公海客户"
      ok-text="确认分派"
      cancel-text="取消"
      :confirm-loading="assignLoading"
      @ok="confirmAssign"
    >
      <Alert
        v-if="assignTarget"
        class="mb-3"
        type="info"
        show-icon
        :message="`将「${assignTarget.companyName || assignTarget.id}」分派给指定销售`"
      />
      <Select
        v-model:value="assignUserId"
        class="w-full"
        show-search
        option-filter-prop="label"
        placeholder="请选择目标销售人员"
      >
        <SelectOption
          v-for="u in userOptions"
          :key="u.id"
          :value="u.id"
          :label="u.realName || `用户${u.id}`"
        />
      </Select>
      <Input.TextArea
        v-model:value="assignReason"
        class="mt-3"
        :rows="2"
        placeholder="分派原因（可选）"
      />
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import Permission from '@/components/common/Permission.vue';
import { onMounted, ref } from 'vue';
import { Alert, Input, message, Modal, Select, Spin, Table } from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
const SelectOption = Select.Option;
import {
  useQueryCustomerPool,
  useClaimPoolCustomer,
  useAssignPoolCustomer,
  useTriggerPoolRecycle,
} from '@/hooks/useCustomerPool.ts';
import { getUserOptions } from '@/api/axios';
import apiClient from '@/api/apiClient.ts';

definePage({
  name: 'clientPool',
});

interface PoolListItem {
  id: number;
  companyName?: string;
  industry?: string;
  grade?: number;
  customerType?: string;
  belongGroup?: string;
  dept?: string;
  poolReleaseTime?: string;
  poolReleaseReason?: string;
  ownerId?: number;
  ownerName?: string;
}
type PoolTablePagination = Parameters<NonNullable<TableProps<PoolListItem>['onChange']>>[0];

const {
  current,
  pageSize,
  data: poolData,
  isLoading,
  refetch,
} = useQueryCustomerPool();

const claimMutate = useClaimPoolCustomer();
const assignMutate = useAssignPoolCustomer();
const { mutate: triggerRecycle, isPending: isRecycling } = useTriggerPoolRecycle();

const assignVisible = ref(false);
const assignTarget = ref<PoolListItem | null>(null);
const assignUserId = ref<number | undefined>(undefined);
const assignReason = ref<string | undefined>(undefined);
const assignLoading = ref(false);
const userOptions = ref<{ id?: number; realName?: string }[]>([]);

onMounted(async () => {
  try {
    const res = await getUserOptions({ client: apiClient });
    userOptions.value = res.data?.data ?? [];
  } catch {
    userOptions.value = [];
  }
});

const poolColumns = [
  { title: '公司名称', dataIndex: 'companyName', key: 'companyName' },
  { title: '行业', dataIndex: 'industry', key: 'industry' },
  { title: '级别', dataIndex: 'grade', key: 'grade' },
  { title: '客户属性', dataIndex: 'customerType', key: 'customerType', width: 110 },
  { title: '进入公海时间', dataIndex: 'poolReleaseTime', key: 'poolReleaseTime', width: 180 },
  { title: '进入原因', dataIndex: 'poolReleaseReason', key: 'poolReleaseReason', width: 240 },
  {
    title: '操作',
    key: 'action',
    dataIndex: 'action',
    width: 160,
    fixed: 'right' as const,
  },
];

function handleTableChange(pagination: PoolTablePagination) {
  current.value = pagination.current ?? current.value;
  pageSize.value = pagination.pageSize ?? pageSize.value;
}

function onClaim(record: PoolListItem) {
  Modal.confirm({
    title: '确认认领',
    content: `确定认领公海客户「${record.companyName || record.id}」吗？认领后将成为你的私海客户。`,
    okText: '认领',
    cancelText: '取消',
    onOk: () => {
      claimMutate.mutate(record.id, {
        onSuccess() {
          void refetch();
        },
      });
    },
  });
}

function openAssign(record: PoolListItem) {
  assignTarget.value = record;
  assignUserId.value = undefined;
  assignReason.value = undefined;
  assignVisible.value = true;
}

function confirmAssign() {
  if (!assignTarget.value) {
    message.warning('请选择要分派的客户');
    return;
  }
  if (!assignUserId.value) {
    message.warning('请选择目标销售人员');
    return;
  }
  assignLoading.value = true;
  assignMutate.mutate(
    {
      companyId: assignTarget.value.id,
      targetUserId: assignUserId.value,
      reason: assignReason.value || undefined,
    },
    {
      onSuccess() {
        assignVisible.value = false;
        void refetch();
      },
      onSettled() {
        assignLoading.value = false;
      },
    },
  );
}
</script>