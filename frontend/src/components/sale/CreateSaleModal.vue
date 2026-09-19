<script setup lang="ts">
import type { PostSalesData } from '@/api/axios';
import { ref, computed, watch } from 'vue';
import type { Rule } from 'ant-design-vue/es/form';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputNumber as AInputNumber,
  DatePicker as ADatePicker,
  Modal as AModal,
  Select as ASelect,
  SelectOption as ASelectOption,
} from 'ant-design-vue';
import { useCreateSale } from '@/hooks/useSale';
import { useQueryCompanies } from '@/hooks/useCompany';
import { useQueryContacts } from '@/hooks/useContact';
import { useQueryAuditors } from '@/hooks/usePremission';
import { TokenManager } from '@/utils/token';
import { validateForm } from '@/utils/formValidate';

type CreateSaleForm = NonNullable<PostSalesData['body']>;

const { data: companies } = useQueryCompanies({ all: true });
const { data: auditors } = useQueryAuditors();
const createForm = (): CreateSaleForm => ({
  opportunityName: undefined,
  companyId: undefined,
  contactId: undefined,
  stage: 0,
  amount: undefined,
  expectedCloseDate: undefined,
  source: undefined,
  description: undefined,
  ownerId: TokenManager.getPayload()?.userID,
  approverId: undefined,
});
const form = ref<CreateSaleForm>(createForm());

// 根据选择的公司筛选联系人
const { data: contacts, setFilters: setContactFilters } = useQueryContacts({
  all: true,
});

// 联系人选择框是否禁用
const contactDisabled = computed(() => !form.value.companyId);

// 监听公司变化，更新联系人筛选并清空已选择的联系人
watch(
  () => form.value.companyId,
  (newCompanyId) => {
    setContactFilters({ companyId: newCompanyId });
    form.value.contactId = undefined;
  }
);

const { mutateAsync: createSale } = useCreateSale();
const innerOpen = ref(false);
const formRef = ref();

const rules: Record<string, Rule[]> = {
  opportunityName: [{ required: true, message: '请输入销售机会名称', trigger: 'blur' }],
  companyId: [{ required: true, message: '请选择公司', trigger: 'change' }],
  approverId: [{ required: true, message: '请选择审批人', trigger: 'change' }],
  amount: [
    { required: true, message: '请输入预计金额', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (typeof value !== 'number' || !(value > 0)) {
          return Promise.reject(new Error('金额必须大于 0'));
        }
        return Promise.resolve();
      },
      trigger: 'blur',
    },
  ],
};
const open = () => {
  form.value = createForm();
  innerOpen.value = true;
};
const close = () => {
  innerOpen.value = false;
  form.value = createForm();
};
defineExpose({ open });
const submit = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }
  try {
    await createSale(form.value);
    close();
  } catch {
    // 后端失败：由全局提示，弹窗保持打开
  }
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="创建销售订单"
    @ok="submit"
    @cancel="close"
  >
    <a-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :label-col="{ span: 5 }"
      :wrapper-col="{ span: 16 }"
      layout="horizontal"
    >
      <!-- 销售机会名称 -->
      <a-form-item label="名称" name="opportunityName">
        <a-input
          v-model:value="form.opportunityName"
          placeholder="请输入销售机会名称"
        />
      </a-form-item>
      <!-- 公司ID -->
      <a-form-item label="公司" name="companyId">
        <a-select
          v-model:value="form.companyId"
          style="width: 100%"
          placeholder="请选择公司"
        >
          <a-select-option
            v-for="item in companies?.records ?? []"
            :key="item.id"
            :value="item.id"
            >{{ item.companyName }}</a-select-option
          >
        </a-select>
      </a-form-item>
      <!-- 联系人ID -->
      <a-form-item label="联系人" name="contactId">
        <a-select
          v-model:value="form.contactId"
          :disabled="contactDisabled"
          style="width: 100%"
          :placeholder="contactDisabled ? '请先选择公司' : '请选择联系人'"
        >
          <a-select-option
            v-for="item in contacts?.records ?? []"
            :key="item.id"
            :value="item.id"
            >{{ item.name }}</a-select-option
          >
        </a-select>
      </a-form-item>
      <!-- 审批人 -->
      <a-form-item label="审批人" name="approverId">
        <a-select
          v-model:value="form.approverId"
          style="width: 100%"
          placeholder="请选择审批人"
        >
          <a-select-option
            v-for="item in auditors ?? []"
            :key="item.id"
            :value="item.id"
            >{{ item.username }}</a-select-option
          >
        </a-select>
      </a-form-item>
      <!-- 预计金额 -->
      <a-form-item label="预计金额" name="amount">
          <a-input-number
            v-model:value="form.amount"
            :min="0"
            style="width: 100%"
            placeholder="请输入预计金额"
          />
      </a-form-item>
      <!-- 预计成交日期 -->
      <a-form-item label="预计成交日期" name="expectedCloseDate">
        <a-date-picker
          v-model:value="form.expectedCloseDate"
          style="width: 100%"
          placeholder="请选择预计成交日期"
          value-format="YYYY-MM-DD HH:mm:ss"
          show-time
        />
      </a-form-item>
      <!-- 来源 -->
      <a-form-item label="来源" name="source">
        <a-input v-model:value="form.source" placeholder="请输入销售机会来源" />
      </a-form-item>
      <!-- 描述 -->
      <a-form-item label="描述" name="description">
        <a-input v-model:value="form.description" placeholder="请输入描述" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
