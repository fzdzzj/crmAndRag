<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import {
  Modal,
  Form,
  FormItem,
  Input,
  InputNumber,
  DatePicker,
  Select,
  SelectOption,
} from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { useCreatePayment, useContractList } from '@/hooks/useFinance';
import { validateForm } from '@/utils/formValidate';
import dayjs from 'dayjs';

const props = defineProps<{
  open: boolean;
  contractId?: number;
  /**
   * 是否只读（合同详情页使用时传入 true，禁用合同选择）
   */
  contractReadonly?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'success'): void;
}>();

const form = ref({
  contractId: undefined as number | undefined,
  paymentNo: '',
  paymentAmount: undefined as number | undefined,
  paymentDate: undefined as string | undefined,
  paymentMethod: undefined as string | undefined,
  remark: '',
});

const isSubmitting = ref(false);
const formRef = ref();

const rules: Record<string, Rule[]> = {
  contractId: [{ required: true, message: '请选择合同', trigger: 'change' }],
  paymentAmount: [
    { required: true, message: '请输入回款金额', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (typeof value !== 'number' || !(value > 0)) {
          return Promise.reject(new Error('回款金额必须大于 0'));
        }
        return Promise.resolve();
      },
      trigger: 'blur',
    },
  ],
  paymentDate: [{ required: true, message: '请选择回款日期', trigger: 'change' }],
  paymentMethod: [{ required: true, message: '请选择回款方式', trigger: 'change' }],
};

// 使用创建回款的 hook
const { mutateAsync: createPayment } = useCreatePayment();

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

const paymentMethods = [
  { label: '现金', value: '现金' },
  { label: '银行转账', value: '银行转账' },
  { label: '支票', value: '支票' },
  { label: '汇票', value: '汇票' },
  { label: '其他', value: '其他' },
];

watch(
  () => props.open,
  (newVal) => {
    if (newVal) {
      form.value.contractId = props.contractId;
    }
  },
);

const handleOk = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }

  try {
    isSubmitting.value = true;
    await createPayment({
      contractId: form.value.contractId,
      paymentNo: form.value.paymentNo,
      paymentAmount: form.value.paymentAmount,
      paymentDate: form.value.paymentDate,
      paymentMethod: form.value.paymentMethod,
      remark: form.value.remark,
    });

    emit('success');
    handleCancel();
  } catch (error) {
    console.error('添加回款记录失败:', error);
  } finally {
    isSubmitting.value = false;
  }
};

const handleCancel = () => {
  emit('update:open', false);
  resetForm();
};

const resetForm = () => {
  form.value = {
    contractId: props.contractId,
    paymentNo: '',
    paymentAmount: undefined,
    paymentDate: undefined,
    paymentMethod: undefined,
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
    title="添加回款记录"
    :confirm-loading="isSubmitting"
    width="600px"
    @ok="handleOk"
    @cancel="handleCancel"
  >
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
          :disabled="contractReadonly"
          :options="contractOptions"
          show-search
          :filter-option="
            (input: string, option: any) => {
              return option.label.toLowerCase().includes(input.toLowerCase());
            }
          "
        />
      </FormItem>

      <FormItem label="回款金额" name="paymentAmount" required>
        <InputNumber
          v-model:value="form.paymentAmount"
          placeholder="请输入回款金额"
          :min="0"
          :precision="2"
          style="width: 100%"
        />
      </FormItem>

      <FormItem label="回款日期" name="paymentDate" required>
        <DatePicker
          v-model:value="form.paymentDate"
          placeholder="请选择回款日期"
          :disabled-date="disabledDate"
          style="width: 100%"
          value-format="YYYY-MM-DD HH:mm:ss"
          show-time
        />
      </FormItem>

      <FormItem label="回款方式" name="paymentMethod" required>
        <Select v-model:value="form.paymentMethod" placeholder="请选择回款方式">
          <SelectOption
            v-for="method in paymentMethods"
            :key="method.value"
            :value="method.value"
          >
            {{ method.label }}
          </SelectOption>
        </Select>
      </FormItem>

      <FormItem label="备注">
        <Input.TextArea
          v-model:value="form.remark"
          placeholder="请输入备注"
          :rows="3"
          :maxlength="200"
          show-count
        />
      </FormItem>
    </Form>
  </Modal>
</template>
