<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import {
  Modal,
  Form,
  FormItem,
  Input,
  InputNumber,
  Select,
  SelectOption,
  DatePicker,
  Spin,
} from 'ant-design-vue';

const { TextArea } = Input;
import {
  useContractList,
  useInvoiceDetail,
  useUpdateInvoice,
} from '@/hooks/useFinance';
import type { Rule } from 'ant-design-vue/es/form';
import { validateForm } from '@/utils/formValidate';
import dayjs from 'dayjs';

const props = defineProps<{
  open: boolean;
  invoiceId?: number;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'success'): void;
}>();

const form = ref({
  id: undefined as number | undefined,
  contractId: undefined as number | undefined,
  paymentId: undefined as number | undefined,
  invoiceNo: '',
  invoiceAmount: undefined as number | undefined,
  invoiceDate: undefined as string | undefined,
  invoiceType: undefined as string | undefined,
  remark: '',
});

const isSubmitting = ref(false);
const formRef = ref();

const rules: Record<string, Rule[]> = {
  contractId: [{ required: true, message: '请选择合同', trigger: 'change' }],
  invoiceAmount: [
    { required: true, message: '请输入开票金额', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (typeof value !== 'number' || !(value > 0)) {
          return Promise.reject(new Error('开票金额必须大于 0'));
        }
        return Promise.resolve();
      },
      trigger: 'blur',
    },
  ],
  invoiceDate: [{ required: true, message: '请选择开票日期', trigger: 'change' }],
  invoiceType: [{ required: true, message: '请选择发票类型', trigger: 'change' }],
};

// 获取合同列表
const { data: contractList, isLoading: isLoadingContracts } = useContractList();

// 合同选项
const contractOptions = computed(() => {
  if (!contractList.value || contractList.value.length === 0) {
    return [];
  }
  return contractList.value.map((contract) => ({
    label: `${contract.contractNo} - ${contract.contractName}`,
    value: contract.id,
  }));
});

const invoiceTypes = [
  { label: '增值税专用发票', value: '增值税专用发票' },
  { label: '增值税普通发票', value: '增值税普通发票' },
  { label: '增值税电子发票', value: '增值税电子发票' },
];

// 使用 hook 获取发票详情
const invoiceIdRef = computed(() => props.invoiceId ?? null);
const { data: invoiceDetail, isLoading: isLoadingDetail } =
  useInvoiceDetail(invoiceIdRef);

// 获取发票详情后填充表单
watch(
  () => invoiceDetail.value,
  (data) => {
    if (data && props.open) {
      form.value = {
        id: data.id,
        contractId: data.contractId,
        paymentId: data.paymentId,
        invoiceNo: data.invoiceNo || '',
        invoiceAmount: data.invoiceAmount,
        invoiceDate: data.invoiceDate,
        invoiceType: data.invoiceType,
        remark: data.remark || '',
      };
    }
  },
);

// 监听合同选择变化，清空已选择的回款
watch(
  () => form.value.contractId,
  () => {
    form.value.paymentId = undefined;
  },
);

// 使用 hook 更新开票信息
const updateInvoiceMutation = useUpdateInvoice();

const handleOk = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }
  await updateInvoiceMutation.mutateAsync({
    id: form.value.id,
    contractId: form.value.contractId,
    paymentId: form.value.paymentId,
    invoiceNo: form.value.invoiceNo,
    invoiceAmount: form.value.invoiceAmount,
    invoiceDate: form.value.invoiceDate,
    invoiceType: form.value.invoiceType,
    remark: form.value.remark,
  });

  emit('success');
  handleCancel();
};

const handleCancel = () => {
  emit('update:open', false);
  resetForm();
};

const resetForm = () => {
  form.value = {
    id: undefined,
    contractId: undefined,
    paymentId: undefined,
    invoiceNo: '',
    invoiceAmount: undefined,
    invoiceDate: undefined,
    invoiceType: undefined,
    remark: '',
  };
};

const disabledDate = (current: dayjs.Dayjs) => {
  return current && current > dayjs().endOf('day');
};
</script>

<template>
  <Modal
    :open="open"
    title="编辑开票信息"
    :confirm-loading="isSubmitting || isLoadingDetail"
    width="600px"
    @ok="handleOk"
    @cancel="handleCancel"
  >
    <Spin :spinning="isLoadingDetail">
      <Form
        ref="formRef"
        :model="form"
        :rules="rules"
        :label-col="{ span: 6 }"
        :wrapper-col="{ span: 16 }"
      >
        <FormItem label="选择合同" name="contractId" required>
          <Select
            v-model:value="form.contractId"
            placeholder="请选择合同"
            :loading="isLoadingContracts"
            :options="contractOptions"
            show-search
            :filter-option="
              (input: string, option: any) => {
                return option.label.toLowerCase().includes(input.toLowerCase());
              }
            "
          />
        </FormItem>



        <FormItem label="发票编号" name="invoiceNo">
          <Input v-model:value="form.invoiceNo" placeholder="请输入发票编号" />
        </FormItem>

        <FormItem label="开票金额" name="invoiceAmount" required>
          <InputNumber
            v-model:value="form.invoiceAmount"
            placeholder="请输入开票金额"
            :min="0"
            :precision="2"
            style="width: 100%"
          />
        </FormItem>

        <FormItem label="开票日期" name="invoiceDate" required>
          <DatePicker
            v-model:value="form.invoiceDate"
            placeholder="请选择开票日期"
            :disabled-date="disabledDate"
            style="width: 100%"
            value-format="YYYY-MM-DD HH:mm:ss"
            show-time
          />
        </FormItem>

        <FormItem label="发票类型" name="invoiceType" required>
          <Select v-model:value="form.invoiceType" placeholder="请选择发票类型">
            <SelectOption
              v-for="type in invoiceTypes"
              :key="type.value"
              :value="type.value"
            >
              {{ type.label }}
            </SelectOption>
          </Select>
        </FormItem>

        <FormItem label="备注">
          <TextArea
            v-model:value="form.remark"
            placeholder="请输入备注信息"
            :rows="3"
          />
        </FormItem>
      </Form>
    </Spin>
  </Modal>
</template>
