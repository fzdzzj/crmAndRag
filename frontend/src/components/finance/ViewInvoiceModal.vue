<script lang="ts" setup>
import { computed } from 'vue';
import { Modal, Descriptions, DescriptionsItem, Grid, Tag } from 'ant-design-vue';
import { useRouter } from 'vue-router';

const screens = Grid.useBreakpoint();
const isMobile = computed(() => !screens.value.md);
import type {
  GetInvoiceInfoByIdResponse,
} from '@/api/axios';

type InvoiceDetail = NonNullable<GetInvoiceInfoByIdResponse['data']>;
type InvoiceViewRecord = InvoiceDetail;

const props = defineProps<{
  open: boolean;
  invoice?: InvoiceViewRecord;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

const router = useRouter();

const handleCancel = () => {
  emit('update:open', false);
};

// 发票类型映射
const invoiceTypeMap: Record<string, string> = {
  增值税专用发票: 'blue',
  增值税普通发票: 'green',
  增值税电子发票: 'orange',
};

const invoiceTypeColor = computed(() => {
  if (!props.invoice) return 'blue';
  return invoiceTypeMap[props.invoice.invoiceType || ''] || 'default';
});

function openContractDetail(contractId?: number) {
  if (contractId === undefined) {
    return;
  }
  void router.push(`/sale/contract-details/${contractId}`);
}

function getContractLabel(invoice: InvoiceViewRecord) {
  const parts = [invoice.contractNo, invoice.contractName].filter(Boolean);
  if (parts.length) {
    return parts.join(' - ');
  }
  return invoice.contractId !== undefined ? `合同#${invoice.contractId}` : '-';
}
</script>

<template>
  <Modal
    :open="open"
    title="查看开票信息"
    width="700px"
    :footer="null"
    @cancel="handleCancel"
  >
    <Descriptions v-if="invoice" bordered :column="isMobile ? 1 : 2">
      <DescriptionsItem label="发票编号" :span="2">
        {{ invoice.invoiceNo }}
      </DescriptionsItem>
      <DescriptionsItem label="开票金额" :span="2">
        <span class="text-lg font-semibold text-green-600">
          ¥{{ invoice.invoiceAmount?.toFixed(2) }}
        </span>
      </DescriptionsItem>
      <DescriptionsItem label="开票日期" :span="2">
        {{ invoice.invoiceDate }}
      </DescriptionsItem>
      <DescriptionsItem label="发票类型" :span="2">
        <Tag :color="invoiceTypeColor">
          {{ invoice.invoiceType }}
        </Tag>
      </DescriptionsItem>
      <DescriptionsItem label="关联合同" :span="2">
        <a
          v-if="invoice.contractId !== undefined"
          class="cursor-pointer text-blue-500 hover:text-blue-700"
          @click="openContractDetail(invoice.contractId)"
        >
          {{ getContractLabel(invoice) }}
        </a>
        <span v-else>-</span>
      </DescriptionsItem>
      <DescriptionsItem label="创建时间" :span="2">
        {{ invoice.createTime || '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="备注" :span="2">
        {{ invoice.remark || '-' }}
      </DescriptionsItem>
    </Descriptions>
  </Modal>
</template>
