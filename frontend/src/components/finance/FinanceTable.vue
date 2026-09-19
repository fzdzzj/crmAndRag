<script lang="ts" setup>
import Permission from '@/components/common/Permission.vue';
import { Spin, Table, Button, Popover, message, Modal,Tag } from 'ant-design-vue';
import type { TableColumnType } from 'ant-design-vue';
import {
  DeleteOutlined,
  EyeOutlined,
  EditOutlined,
} from '@ant-design/icons-vue';
import { computed, ref } from 'vue';
import { useDeleteInvoice, useDeletePayment } from '@/hooks/useFinance';
import { getPaymentStatusInfo } from '@/constants/finance/constants';
import CreateInvoiceModal from '@/components/finance/CreateInvoiceModal.vue';
import CreatePaymentModal from '@/components/finance/CreatePaymentModal.vue';
import ViewInvoiceModal from '@/components/finance/ViewInvoiceModal.vue';
import ViewPaymentModal from '@/components/finance/ViewPaymentModal.vue';
import EditInvoiceModal from '@/components/finance/EditInvoiceModal.vue';
import EditPaymentModal from '@/components/finance/EditPaymentModal.vue';
import type {
  PostInvoiceInfoQueryResponse,
  PostPaymentRecordQueryResponse,
} from '@/api/axios';

type InvoicePageData = NonNullable<PostInvoiceInfoQueryResponse['data']>;
type InvoiceRecord = NonNullable<InvoicePageData['records']>[number];
type PaymentPageData = NonNullable<PostPaymentRecordQueryResponse['data']>;
type PaymentRecord = NonNullable<PaymentPageData['records']>[number];
type FinanceRecord = {
  id?: number;
  invoiceNo?: string;
  paymentNo?: string;
  paymentStatus?: number;
  paymentStatusDesc?: string;
};

interface Props {
  data?: { records?: FinanceRecord[]; total?: number; current?: number; size?: number };
  columns: TableColumnType<FinanceRecord>[];
  loading: boolean;
  type: 'invoice' | 'payment';
}

const props = defineProps<Props>();

const { mutate: deleteInvoice } = useDeleteInvoice();
const { mutate: deletePayment } = useDeletePayment();

const createInvoiceModalOpen = ref(false);
const createPaymentModalOpen = ref(false);
const viewInvoiceModalOpen = ref(false);
const viewPaymentModalOpen = ref(false);
const editInvoiceModalOpen = ref(false);
const editPaymentModalOpen = ref(false);

const selectedInvoiceId = ref<number>();
const selectedPaymentId = ref<number>();
const selectedInvoice = ref<InvoiceRecord>();
const selectedPayment = ref<PaymentRecord>();

const tableData = computed<FinanceRecord[]>(() => {
  const records = props.data?.records;
  return Array.isArray(records) ? records : [];
});

function getPaymentStatus(record: FinanceRecord) {
  if (props.type !== 'payment') {
    return getPaymentStatusInfo();
  }
  const payment = record;
  return getPaymentStatusInfo(payment.paymentStatus, payment.paymentStatusDesc);
}

function openCreateModal() {
  if (props.type === 'invoice') {
    createInvoiceModalOpen.value = true;
  } else {
    createPaymentModalOpen.value = true;
  }
}

function openViewModal(record: FinanceRecord) {
  if (props.type === 'invoice') {
    selectedInvoice.value = record;
    viewInvoiceModalOpen.value = true;
  } else {
    selectedPayment.value = record;
    viewPaymentModalOpen.value = true;
  }
}

function openEditModal(record: FinanceRecord) {
  if (record.id === undefined) {
    return;
  }

  if (props.type === 'invoice') {
    selectedInvoiceId.value = record.id;
    editInvoiceModalOpen.value = true;
  } else {
    selectedPaymentId.value = record.id;
    editPaymentModalOpen.value = true;
  }
}

function handleDelete(record: FinanceRecord) {
  if (record.id === undefined) {
    return;
  }

  const label =
    props.type === 'invoice'
      ? (record).invoiceNo || `发票#${record.id}`
      : (record).paymentNo || `回款#${record.id}`;

  Modal.confirm({
    title: '确认删除',
    content: `确定要删除${props.type === 'invoice' ? '发票' : '回款'}"${label}"吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      if (props.type === 'invoice') {
        deleteInvoice([record.id!], {
          onSuccess: () => {
            message.success('删除成功');
          },
        });
      } else {
        deletePayment([record.id!], {
          onSuccess: () => {
            message.success('删除成功');
          },
        });
      }
    },
  });
}
</script>

<template>
  <Spin :spinning="loading">
    <Table
      :scroll="{ x: 'max-content' }"
      size="small"
      :columns="columns"
      :data-source="tableData"
      :row-key="(record: FinanceRecord) => record.id ?? 0"
      :pagination="false"
    >
      <template #title>
        <div class="flex items-center justify-between">
          <h2>{{ type === 'invoice' ? '发票列表' : '回款列表' }}</h2>
          <Permission
            :allow="
              type === 'invoice'
                ? ['finance:FINANCE_RECORD_INVOICE']
                : ['finance:FINANCE_RECORD_PAYMENT']
            "
          >
            <Button type="primary" @click="openCreateModal">
              新增{{ type === 'invoice' ? '发票' : '回款' }}
            </Button>
          </Permission>
        </div>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <Permission
            :allow="
              type === 'invoice'
                ? ['finance:FINANCE_VIEW_INVOICE']
                : ['finance:FINANCE_VIEW_PAYMENT']
            "
          >
            <Popover content="查看" placement="top">
              <EyeOutlined
                class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                @click="openViewModal(record)"
              />
            </Popover>
          </Permission>
          <Permission
            :allow="
              type === 'invoice'
                ? ['finance:FINANCE_EDIT_INVOICE']
                : ['finance:FINANCE_EDIT_PAYMENT']
            "
          >
            <Popover content="编辑" placement="top">
              <EditOutlined
                class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                @click="openEditModal(record)"
              />
            </Popover>
          </Permission>
          <Permission
            :allow="
              type === 'invoice'
                ? ['finance:FINANCE_DELETE_INVOICE']
                : ['finance:FINANCE_DELETE_PAYMENT']
            "
          >
            <Popover content="删除" placement="top">
              <DeleteOutlined
                class="cursor-pointer text-red-500 hover:text-red-700"
                @click="handleDelete(record)"
              />
            </Popover>
          </Permission>
        </template>
        <template v-else-if="column.key === 'paymentStatus'">
          <Tag :color="getPaymentStatus(record).color">
            {{ getPaymentStatus(record).text }}
          </Tag>
        </template>
      </template>
    </Table>
  </Spin>

  <CreateInvoiceModal
    v-if="type === 'invoice'"
    v-model:open="createInvoiceModalOpen"
  />
  <CreatePaymentModal
    v-if="type === 'payment'"
    v-model:open="createPaymentModalOpen"
  />

  <ViewInvoiceModal
    v-if="type === 'invoice'"
    v-model:open="viewInvoiceModalOpen"
    :invoice="selectedInvoice"
  />
  <ViewPaymentModal
    v-if="type === 'payment'"
    v-model:open="viewPaymentModalOpen"
    :payment="selectedPayment"
  />

  <EditInvoiceModal
    v-if="type === 'invoice'"
    v-model:open="editInvoiceModalOpen"
    :invoice-id="selectedInvoiceId"
  />
  <EditPaymentModal
    v-if="type === 'payment'"
    v-model:open="editPaymentModalOpen"
    :payment-id="selectedPaymentId"
  />
</template>
