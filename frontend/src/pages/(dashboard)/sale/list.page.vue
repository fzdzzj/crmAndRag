<template>
  <div class="w-full">
    <Spin :spinning="isLoading" tip="Loading...">
      <Table
        data-tour="sale-table"
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="salesColumns"
        :data-source="salesPageData?.records ?? []"
        :row-key="(record: SalesOpportunityVO) => record.id!"
        :pagination="{
          current: current,
          pageSize: pageSize,
          total: salesPageData?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex items-center justify-between">
            <h2>销售订单</h2>
            <div class="flex flex-wrap gap-2">
              <Button @click="reset">重置筛选</Button>
              <Button :loading="isRefetching" @click="refetch()">刷新</Button>
              <Permission :allow="['sales:SALES_CREATE_SALE_OPPORTUNITY']">
                <Button type="primary" data-tour="sale-create" @click="openCreateSaleModal"
                  >创建销售订单</Button
                >
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <Permission :allow="['sales:SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY']">
              <Popover content="查看" placement="top">
                <EyeOutlined
                  data-tour="sale-view"
                  class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                  @click="handleView(record as SalesOpportunityVO)"
                />
              </Popover>
            </Permission>
            <Permission :allow="['sales:SALES_UPDATE_SALE_OPPORTUNITY']">
              <Popover content="编辑" placement="top">
                <EditOutlined
                  class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                  @click="openEditModal(record as SalesOpportunityVO)"
                />
              </Popover>
            </Permission>
            <Permission :allow="['sales:SALES_PROGRESS_SALE_OPPORTUNITY_STAGE']">
              <Tooltip title="申请推进到下一阶段">
                <Button
                  type="primary"
                  size="small"
                  class="mr-2"
                  data-tour="sale-advance"
                  @click="handlePromote(record as SalesOpportunityVO)"
                >
                  <ArrowUpOutlined class="mr-1" />
                  推进
                </Button>
              </Tooltip>
            </Permission>
            <Permission :allow="['sales:SALES_DELETE_SALE_OPPORTUNITY']">
              <Popover content="删除" placement="top">
                <DeleteOutlined
                  class="cursor-pointer text-red-500 hover:text-red-700"
                  @click="handleDelete(record as SalesOpportunityVO)"
                />
              </Popover>
            </Permission>
          </template>
        </template>
      </Table>
    </Spin>
    <SaleEditModal ref="editModalRef" @saved="refetch"></SaleEditModal>
    <CreateSaleModal ref="createSaleModalRef" @submit="refetch" />
    <FileUploadModal ref="fileUploadModalRef" @submit="refetch" />
  </div>
</template>

<script setup lang="ts">
import Permission from '@/components/common/Permission.vue';
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { Button, Modal, Popover, Spin, Tooltip, message, Table } from 'ant-design-vue';
import {
  useQuerySalesWithURLSearchParamsAsync,
  useDeleteSale,
} from '@/hooks/useSale';
import SaleEditModal from '@/components/sale/SaleEditModal.vue';
import { createSaleColumns } from '@/constants/sale/constant';
import CreateSaleModal from '@/components/sale/CreateSaleModal.vue';
import type { GetSalesQueryResponse } from '@/api/axios';
import {
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
} from '@ant-design/icons-vue';
import FileUploadModal from '@/components/sale/FileUploadModal.vue';

type SalesOpportunityVO = NonNullable<
  NonNullable<GetSalesQueryResponse['data']>['records']
>[number];

definePage({
  name: 'saleList',
});

const router = useRouter();

// 创建销售订单
const createSaleModalRef = ref<InstanceType<typeof CreateSaleModal>>();
const openCreateSaleModal = () => {
  createSaleModalRef.value?.open();
};

// 修改编辑信息
const editModalRef = ref<InstanceType<typeof SaleEditModal>>();
const openEditModal = (sale: SalesOpportunityVO) => {
  editModalRef.value?.open(sale);
};

const fileUploadModalRef = ref<InstanceType<typeof FileUploadModal>>();
const handlePromote = (record: SalesOpportunityVO) => {
  fileUploadModalRef.value?.open(record);
};

// 查看详情 - 导航到详情页面
const handleView = (sale: SalesOpportunityVO) => {
  if (sale.id) {
    void router.push(`/sale/detail/${sale.id}`);
  }
};

// 查询销售数据
const {
  current,
  pageSize,
  data: salesPageData,
  isLoading,
  refetch,
  isRefetching,
  filters,
  setFilters,
  reset,
} = useQuerySalesWithURLSearchParamsAsync();

const salesColumns = createSaleColumns({
  filters,
  setFilters,
});

// 删除功能
const { mutate: deleteSale } = useDeleteSale();

// 处理删除操作
const handleDelete = (record: SalesOpportunityVO) => {
  if (record.stage === 5) {
    Modal.confirm({
      title: '确认删除',
      content: `确定要删除销售订单"${record.opportunityName}"吗？此操作不可恢复。`,
      okText: '确认',
      cancelText: '取消',
      onOk: () => {
        if (!record.id) {
          return;
        }
        deleteSale(record.id, {
          onSuccess: () => {
            void refetch().then(() => {
              message.success('删除成功');
            });
          },
          onError: () => {
            message.error('删除失败');
          },
        });
      },
    });
    return;
  }
  message.info(`${record.opportunityName},此订单阶段暂不可删除！`);
};

const handleTableChange = (pagination: { current?: number; pageSize?: number }) => {
  if (pagination.current !== undefined) {
    current.value = pagination.current;
  }
  if (pagination.pageSize !== undefined) {
    pageSize.value = pagination.pageSize;
  }
};
</script>
