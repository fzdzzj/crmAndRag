<template>
  <a-modal
    v-model:open="innerOpen"
    :title="readonly ? '查看销售记录' : '编辑销售记录'"
    :confirm-loading="isUpdating"
    :ok-text="readonly ? '关闭' : '保存'"
    cancel-text="取消"
    :cancel-button-props="readonly ? { disabled: true } : undefined"
    @ok="readonly ? close() : submit()"
    @cancel="close"
  >
    <a-form
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 18 }"
      layout="horizontal"
      class="edit-form"
    >
      <a-form-item label="销售订单名称">
        <a-input
          v-model:value="form.opportunityName"
          placeholder="请输入销售机会名称"
          :disabled="readonly"
        />
      </a-form-item>
      <a-form-item label="公司">
        <a-select
          v-model:value="form.companyId"
          style="width: 100%"
          :disabled="readonly"
        >
          <a-select-option
            v-for="item in companies?.records ?? []"
            :key="item.id"
            :value="item.id"
            >{{ item.companyName }}</a-select-option
          >
        </a-select>
      </a-form-item>
      <a-form-item label="联系人">
        <a-select
          v-model:value="form.contactId"
          style="width: 100%"
          :disabled="readonly"
        >
          <a-select-option
            v-for="item in contacts?.records ?? []"
            :key="item.id"
            :value="item.id"
            >{{ item.name }}</a-select-option
          >
        </a-select>
      </a-form-item>
      <a-form-item label="销售金额">
        <a-input-number
          v-model:value="form.amount"
          :min="0"
          :precision="2"
          style="width: 100%"
          placeholder="请输入销售金额"
          :disabled="readonly"
        />
      </a-form-item>
      <a-form-item label="预计成交日期">
        <a-date-picker
          v-model:value="form.expectedCloseDate"
          style="width: 100%"
          placeholder="请选择预计成交日期"
          value-format="YYYY-MM-DD HH:mm:ss"
          show-time
          :disabled="readonly"
        />
      </a-form-item>
      <a-form-item label="负责人">
        <a-input :value="originalSale?.ownerName" readonly />
      </a-form-item>
      <a-form-item label="销售来源">
        <a-input
          v-model:value="form.source"
          placeholder="请输入销售来源"
          :disabled="readonly"
        />
      </a-form-item>
      <a-form-item label="描述">
        <a-textarea
          v-model:value="form.description"
          placeholder="请输入描述"
          :rows="3"
          :disabled="readonly"
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import {
  Modal as AModal,
  Input as AInput,
  InputNumber as AInputNumber,
  Form as AForm,
  message,
  FormItem as AFormItem,
  Select as ASelect,
  DatePicker as ADatePicker,
  Textarea as ATextarea,
} from 'ant-design-vue';
import { useUpdateSale } from '@/hooks/useSale.ts';
import type { PutSalesData, GetSalesQueryResponse } from '@/api/axios';
import { useQueryCompanies } from '@/hooks/useCompany';
import { useQueryContacts } from '@/hooks/useContact';
const ASelectOption = ASelect.Option;

type SalesOpportunityDTO = NonNullable<PutSalesData['body']>;
type SalesOpportunityVO = NonNullable<
  NonNullable<GetSalesQueryResponse['data']>['records']
>[number];

const { data: companies } = useQueryCompanies({ all: true });
const { data: contacts } = useQueryContacts({ all: true });
const emit = defineEmits<{
  (e: 'saved'): void;
}>();

const innerOpen = ref(false);
const editingId = ref<number | undefined>(undefined);
const readonly = ref(false);
const createForm = (): SalesOpportunityDTO => ({
  id: undefined,
  opportunityName: undefined,
  companyId: undefined,
  contactId: undefined,
  stage: undefined,
  amount: undefined,
  expectedCloseDate: undefined,
  source: undefined,
  description: undefined,
  ownerId: undefined,
  approverId: undefined,
});
const form = ref<SalesOpportunityDTO>(createForm());
const originalSale = ref<SalesOpportunityVO>();

const { mutate: updateSale, isPending: isUpdating } = useUpdateSale();

function open(sale: SalesOpportunityVO, isReadonly = false) {
  editingId.value = sale.id;
  originalSale.value = sale;
  readonly.value = isReadonly;
  form.value = {
    opportunityName: sale.opportunityName,
    companyId: sale.companyId,
    contactId: sale.contactId,
    amount: sale.amount,
    stage: sale.stage,
    expectedCloseDate: sale.expectedCloseDate,
    ownerId: sale.ownerId,
    source: sale.source,
    description: sale.description,
    approverId: sale.approverId,
  };
  innerOpen.value = true;
}

function close() {
  innerOpen.value = false;
  editingId.value = undefined;
  form.value = createForm();
  originalSale.value = undefined;
}

function submit() {
  const id = editingId.value;
  if (!id) {
    message.error('未选择要编辑的销售记录');
    return;
  }
  if (typeof form.value.amount !== 'number' || form.value.amount <= 0) {
    message.error('销售金额必须大于 0');
    return;
  }

  const updateData: SalesOpportunityDTO = {
    ...originalSale.value, // 继承 sale 中所有原始字段
    id,
    opportunityName: form.value.opportunityName,
    companyId: form.value.companyId,
    contactId: form.value.contactId,
    amount: form.value.amount,
    stage: form.value.stage,
    expectedCloseDate: form.value.expectedCloseDate,
    ownerId: form.value.ownerId,
    approverId: form.value.approverId,
    source: form.value.source,
    description: form.value.description,
  };

  updateSale(updateData, {
    onSuccess: () => {
      close();
      emit('saved');
    },
  });
}
defineExpose({ open, close });
</script>

<style scoped>
.edit-form {
  padding: 8px 4px;
}
:deep(.ant-form-item) {
  margin-bottom: 12px;
}
:deep(.ant-form-item-label > label) {
  font-weight: 500;
}
</style>
