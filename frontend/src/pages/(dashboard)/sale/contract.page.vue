<template>
  <div class="w-full">
    <Spin :spinning="isLoading" tip="Loading...">
      <Table
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="contractColumns"
        :data-source="contracts ?? []"
        :row-key="(record: ContractVO) => record.id!"
        :pagination="{
          current: current,
          pageSize: pageSize,
          total: total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex items-center justify-between">
            <h2>销售合同</h2>
            <div class="flex flex-wrap gap-2">
              <Button :loading="isRefetching" @click="refetch()">刷新</Button>
              <Permission :allow="['sales:SALES_CREATE_CONTRACT']">
                <Button type="primary" data-tour="contract-create" @click="openCreateContractModal"
                  >快速创建合同</Button
                >
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <Permission :allow="['sales:SALES_VIEW_CONTRACT']">
              <Popover content="查看" placement="top">
                <EyeOutlined
                  class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                  @click="handleViewDetail(record as ContractVO)"
                />
              </Popover>
            </Permission>
            <Permission :allow="['sales:SALES_UPDATE_CONTRACT']">
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
      </Table>
    </Spin>
    <CreateContractModal ref="createContractModalRef" />
    <EditContractModal ref="editContractModalRef" />
  </div>
</template>

<script setup lang="ts">
import Permission from '@/components/common/Permission.vue';
import { computed, ref } from 'vue';
import { message, Button, Modal, Popover, Spin, Table } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import { useQueryContracts, useDeleteContract } from '@/hooks/useContract';
import type { GetContractResponse } from '@/api/axios';
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
  current.value = pagination.current ?? current.value;
  pageSize.value = pagination.pageSize ?? pageSize.value;
};
</script>
