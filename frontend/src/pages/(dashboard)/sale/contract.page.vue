<template>
  <div class="w-full">
    <Spin :spinning="viewLoading()" tip="Loading...">
      <Table
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="viewColumns"
        :data-source="viewRows ?? []"
        :row-key="(record: { id?: number }) => record.id!"
        :pagination="{
          current: viewCurrent,
          pageSize: viewPageSize,
          total: viewTotal ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex items-center justify-between">
            <h2>销售合同</h2>
            <div class="flex flex-wrap items-center gap-2">
              <Segmented
                :value="viewMode"
                :options="[
                  { value: 'all', label: '全部合同' },
                  { value: 'expiring', label: '临近到期' },
                  { value: 'overdue', label: '逾期账款' },
                ]"
                @change="handleViewModeChange"
              />
              <Permission :endpoint="'POST /contract/warning/trigger'">
                <Button
                  type="primary"
                  :loading="triggerWarningPending"
                  data-tour="contract-warning-trigger"
                  @click="handleTriggerWarning"
                  >生成预警待办</Button
                >
              </Permission>
              <Button :loading="isRefetching" @click="handleRefresh">刷新</Button>
              <Permission :endpoint="'POST /contract'">
                <Button type="primary" data-tour="contract-create" @click="openCreateContractModal"
                  >快速创建合同</Button
                >
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <template v-if="viewMode === 'all'">
              <Permission :endpoint="'GET /contract/{id}'">
                <Popover content="查看" placement="top">
                  <EyeOutlined
                    class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                    @click="handleViewDetail(record as ContractVO)"
                  />
                </Popover>
              </Permission>
              <Permission :endpoint="'PUT /contract'">
                <Popover content="编辑" placement="top">
                  <EditOutlined
                    class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                    @click="handleEdit(record as ContractVO)"
                  />
                </Popover>
              </Permission>
              <Popover content="删除" placement="top">
                <DeleteOutlined
                  class="cursor-pointer text-red-500 hover:text-red-700"
                  @click="handleDelete(record as ContractVO)"
                />
              </Popover>
            </template>
          </template>
          <template v-else-if="column.key === 'remainDays'">
            <span :class="(record as WarningRow).remainDays! < 0 ? 'text-red-500' : 'text-orange-500'">
              {{ (record as WarningRow).remainDays }}
            </span>
          </template>
        </template>
      </Table>
    </Spin>
    <CreateContractModal ref="createContractModalRef" />
    <EditContractModal ref="editContractModalRef" />
  </div>
</template>

<script setup lang="ts">
import Permission from '@/components/common/Permission.vue';
import { computed, ref } from 'vue';
import { message, Button, Modal, Popover, Segmented, Spin, Table } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import {
  useQueryContracts,
  useDeleteContract,
  useQueryContractWarningExpiring,
  useQueryContractWarningOverduePayment,
  useTriggerContractWarning,
} from '@/hooks/useContract';
import type { ContractExpiringVo, ContractOverduePaymentVo, GetContractResponse } from '@/api/axios';
import { contractColumns } from '@/constants/sale/constant';
import CreateContractModal from '@/components/sale/CreateContractModal.vue';
import EditContractModal from '@/components/sale/EditContractModal.vue';
import {
  EyeOutlined,
  DeleteOutlined,
  EditOutlined,
} from '@ant-design/icons-vue';

type ContractVO = NonNullable<
  NonNullable<GetContractResponse['data']>['records']
>[number];

type WarningRow = ContractExpiringVo | ContractOverduePaymentVo;
type ViewMode = 'all' | 'expiring' | 'overdue';

const warningColumns = [
  { title: '合同编号', dataIndex: 'contractNo', key: 'contractNo' },
  { title: '合同名称', dataIndex: 'contractName', key: 'contractName' },
  { title: '客户公司', dataIndex: 'companyName', key: 'companyName' },
  { title: '负责人', dataIndex: 'ownerName', key: 'ownerName' },
  { title: '合同总额', dataIndex: 'totalAmount', key: 'totalAmount' },
  { title: '已回款', dataIndex: 'paidAmount', key: 'paidAmount' },
  { title: '未结敞口', dataIndex: 'remainingAmount', key: 'remainingAmount' },
  { title: '剩余天数', dataIndex: 'remainDays', key: 'remainDays' },
  { title: '到期日', dataIndex: 'endDate', key: 'endDate' },
];

definePage({
  name: 'saleContract',
});

const createContractModalRef = ref<InstanceType<typeof CreateContractModal>>();
const editContractModalRef = ref<InstanceType<typeof EditContractModal>>();

const openCreateContractModal = () => {
  createContractModalRef.value?.open();
};

const router = useRouter();
const {
  pageSize,
  pageNum: current,
  data: contractsResponse,
  isLoading,
  refetch,
  isRefetching,
} = useQueryContracts();
const { mutate: deleteContract } = useDeleteContract();

const contracts = computed(() => contractsResponse.value?.records || []);
const total = computed(() => contractsResponse.value?.total || 0);

const viewMode = ref<ViewMode>('all');
const {
  pageNum: expiringPageNum,
  pageSize: expiringPageSize,
  data: expiringResponse,
  isLoading: expiringLoading,
  refetch: expiringRefetch,
} = useQueryContractWarningExpiring();
const {
  pageNum: overduePageNum,
  pageSize: overduePageSize,
  data: overdueResponse,
  isLoading: overdueLoading,
  refetch: overdueRefetch,
} = useQueryContractWarningOverduePayment();
const { isPending: triggerWarningPending, mutate: runTriggerWarning } =
  useTriggerContractWarning();

const viewColumns = computed(() =>
  viewMode.value === 'all' ? contractColumns : warningColumns,
);
const viewRows = computed<WarningRow[] | ContractVO[]>(() => {
  if (viewMode.value === 'expiring') return expiringResponse.value?.records ?? [];
  if (viewMode.value === 'overdue') return overdueResponse.value?.records ?? [];
  return contracts.value;
});
const viewTotal = computed(() => {
  if (viewMode.value === 'expiring') return expiringResponse.value?.total ?? 0;
  if (viewMode.value === 'overdue') return overdueResponse.value?.total ?? 0;
  return total.value;
});
const viewCurrent = computed(() => {
  if (viewMode.value === 'expiring') return expiringPageNum.value;
  if (viewMode.value === 'overdue') return overduePageNum.value;
  return current.value;
});
const viewPageSize = computed(() => {
  if (viewMode.value === 'expiring') return expiringPageSize.value;
  if (viewMode.value === 'overdue') return overduePageSize.value;
  return pageSize.value;
});
const viewLoading = () =>
  isLoading.value ||
  (viewMode.value === 'expiring' && expiringLoading.value) ||
  (viewMode.value === 'overdue' && overdueLoading.value);

const handleViewModeChange = (value: string | number) => {
  viewMode.value = (value as ViewMode) ?? 'all';
};

const handleRefresh = () => {
  void refetch();
  if (viewMode.value === 'expiring') void expiringRefetch();
  if (viewMode.value === 'overdue') void overdueRefetch();
};

const handleTriggerWarning = () => {
  Modal.confirm({
    title: '生成预警待办',
    content: '将为到期/临期与逾期应收账款合同负责人生成待办任务（已存在进行中任务的将自动跳过），是否继续？',
    okText: '生成',
    cancelText: '取消',
    onOk: () => runTriggerWarning(),
  });
};

const handleDelete = (contract: ContractVO) => {
  if (contract.contractStatus !== 4) {
    message.info(`${contract.contractName},此合同阶段暂不可删除！`);
    return;
  }
  if (!contract.id) {
    return;
  }
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除合同"${contract.contractName}"吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    onOk: () => {
      deleteContract([contract.id] as number[]);
    },
  });
};

const handleViewDetail = (contract: ContractVO) => {
  if (!contract.id) {
    message.warning('缺少合同ID，无法查看详情');
    return;
  }
  void router.push(`/sale/contract-details/${contract.id}`);
};

const handleEdit = (contract: ContractVO) => {
  editContractModalRef.value?.open(contract.id);
};

const handleTableChange = (pagination: { current?: number; pageSize?: number }) => {
  const nextCurrent = pagination.current ?? 1;
  const nextPageSize = pagination.pageSize ?? 10;
  if (viewMode.value === 'expiring') {
    expiringPageNum.value = nextCurrent;
    expiringPageSize.value = nextPageSize;
    return;
  }
  if (viewMode.value === 'overdue') {
    overduePageNum.value = nextCurrent;
    overduePageSize.value = nextPageSize;
    return;
  }
  current.value = nextCurrent;
  pageSize.value = nextPageSize;
};
</script>
