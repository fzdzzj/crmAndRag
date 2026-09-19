<script lang="ts" setup>
import { computed } from 'vue';
import { Modal, Descriptions, DescriptionsItem, Grid, Tag } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import { getPaymentStatusInfo } from '@/constants/finance/constants';

const screens = Grid.useBreakpoint();
const isMobile = computed(() => !screens.value.md);
import type {
  GetPaymentRecordByIdResponse,
} from '@/api/axios';
type PaymentDetail = NonNullable<GetPaymentRecordByIdResponse['data']>;
type PaymentViewRecord = PaymentDetail;

const props = defineProps<{
  open: boolean;
  payment?: PaymentViewRecord;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

const router = useRouter();

const handleCancel = () => {
  emit('update:open', false);
};

const paymentStatusInfo = computed(() => {
  if (!props.payment) return { text: '未知', color: 'default' };
  return getPaymentStatusInfo(
    props.payment.paymentStatus,
    props.payment.paymentStatusDesc,
  );
});

function openContractDetail(contractId?: number) {
  if (contractId === undefined) {
    return;
  }
  void router.push(`/sale/contract-details/${contractId}`);
}

function getContractLabel(payment: PaymentViewRecord) {
  const parts = [payment.contractNo, payment.contractName].filter(Boolean);
  if (parts.length) {
    return parts.join(' - ');
  }
  return payment.contractId !== undefined ? `合同#${payment.contractId}` : '-';
}
</script>

<template>
  <Modal
    :open="open"
    title="查看回款记录"
    width="700px"
    :footer="null"
    @cancel="handleCancel"
  >
    <Descriptions v-if="payment" bordered :column="isMobile ? 1 : 2">
      <DescriptionsItem label="回款编号" :span="2">
        {{ payment.paymentNo }}
      </DescriptionsItem>
      <DescriptionsItem label="回款金额" :span="2">
        <span class="text-lg font-semibold text-green-600">
          ¥{{ payment.paymentAmount?.toFixed(2) }}
        </span>
      </DescriptionsItem>
      <DescriptionsItem label="回款日期" :span="2">
        {{ payment.paymentDate }}
      </DescriptionsItem>
      <DescriptionsItem label="回款方式" :span="2">
        {{ payment.paymentMethod }}
      </DescriptionsItem>
      <DescriptionsItem label="回款状态" :span="2">
        <Tag :color="paymentStatusInfo.color">
          {{ paymentStatusInfo.text }}
        </Tag>
      </DescriptionsItem>
      <DescriptionsItem label="关联合同" :span="2">
        <a
          v-if="payment.contractId !== undefined"
          class="cursor-pointer text-blue-500 hover:text-blue-700"
          @click="openContractDetail(payment.contractId)"
        >
          {{ getContractLabel(payment) }}
        </a>
        <span v-else>-</span>
      </DescriptionsItem>
      <DescriptionsItem label="创建时间" :span="2">
        {{ payment.createTime }}
      </DescriptionsItem>
      <DescriptionsItem label="备注" :span="2">
        {{ payment.remark || '-' }}
      </DescriptionsItem>
    </Descriptions>
  </Modal>
</template>
