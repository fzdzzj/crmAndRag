<script setup lang="ts">
import type { PostContactTaskCreateData } from '@/api/axios';
import { ref } from 'vue';
import type { Rule } from 'ant-design-vue/es/form';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Modal as AModal,
  message,
  Select as ASelect,
  DatePicker as ADatePicker,
  Textarea as ATextarea,
} from 'ant-design-vue';
import { useCreateContactTask } from '@/hooks/useContactTask';
import { useQueryContacts } from '@/hooks/useContact';
import { useQuerySales } from '@/hooks/useSale';
import { useQueryUsers } from '@/hooks/useUser';
import { validateForm } from '@/utils/formValidate';
import AssistApplyEditor from '@/components/assist/AssistApplyEditor.vue';
import type { AssistApplyItem } from '@/hooks/useAssist';

type ContactTaskForm = NonNullable<PostContactTaskCreateData['body']> & {
  applyPurpose?: string;
  applyRequirement?: string;
};

const createDefaultForm = (): ContactTaskForm => ({
  taskTitle: '',
  taskContent: '',
  companyId: undefined,
  contactId: undefined,
  opportunityId: undefined,
  taskType: undefined,
  startTime: undefined,
  endTime: undefined,
  priority: 1,
  status: 0,
  assigneeId: undefined,
  assistApplyList: [],
});

const form = ref<ContactTaskForm>(createDefaultForm());
const { mutate: createContactTask, isPending: isCreating } =
  useCreateContactTask();
const innerOpen = ref(false);
const formRef = ref();

// 表单验证规则
const rules: Record<string, Rule[]> = {
  taskTitle: [{ required: true, message: '请输入任务标题', trigger: 'blur' }],
  taskContent: [{ required: true, message: '请输入任务内容', trigger: 'blur' }],
  contactId: [{ required: true, message: '请选择联系人', trigger: 'change' }],
  opportunityId: [{ required: true, message: '请选择销售机会', trigger: 'change' }],
  taskType: [{ required: true, message: '请输入任务类型', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [
    { required: true, message: '请选择结束时间', trigger: 'change' },
    {
      validator: (_rule, value) => {
        if (value && form.value.startTime && value < form.value.startTime) {
          return Promise.reject(new Error('结束时间不能早于开始时间'));
        }
        return Promise.resolve();
      },
      trigger: 'change',
    },
  ],
  priority: [{ required: true, message: '请选择优先级', trigger: 'change' }],
  assigneeId: [{ required: true, message: '请选择任务执行人', trigger: 'change' }],
};

// 获取所有联系人列表
const { data: contactsData } = useQueryContacts({
  all: true,
  current: 1,
  pageSize: 1000,
});

// 获取所有销售机会列表
const { data: salesData } = useQuerySales({
  all: true,
});

// 获取所有用户列表（用于执行人）
const { data: usersData, isLoading: isUsersLoading } = useQueryUsers({
  all: true,
  current: 1,
  pageSize: 1000,
});


// 优先级映射
const priorityOptions = [
  { label: '低', value: 0 },
  { label: '中', value: 1 },
  { label: '高', value: 2 },
  { label: '紧急', value: 3 },
];

const open = () => {
  form.value = createDefaultForm();
  innerOpen.value = true;
  // 重置表单验证
  formRef.value?.resetFields();
};

const close = () => {
  innerOpen.value = false;
  formRef.value?.resetFields();
};

defineExpose({ open, close });

const submit = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }
  const applyList = (form.value.assistApplyList ?? []) as AssistApplyItem[];
  const validList = applyList.filter(
    (item) =>
      item.assistUserId != null &&
      item.applyPurpose?.trim() &&
      item.applyRequirement?.trim(),
  );
  if (applyList.length > 0 && validList.length !== applyList.length) {
    message.warning('每位协助人都需要填写协作目的与协作要求');
    return;
  }
  form.value.assistApplyList = validList;

  // 顶层 applyPurpose/applyRequirement 只是编辑态字段；协助人一律走 assistApplyList 逐条提交。
  const {
    applyPurpose: _applyPurpose,
    applyRequirement: _applyRequirement,
    ...payload
  } = form.value;

  createContactTask(payload, {
    onSuccess: () => {
      message.success('创建任务成功');
      close();
    },
  });
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="新增联络任务"
    :confirm-loading="isCreating"
    width="600px"
    @ok="submit"
    @cancel="close"
  >
    <a-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 16 }"
      layout="horizontal"
    >
      <a-form-item label="任务标题" name="taskTitle" required>
        <a-input
          v-model:value="form.taskTitle"
          placeholder="请输入任务标题"
        />
      </a-form-item>
      <a-form-item label="任务内容" name="taskContent" required>
        <a-textarea
          v-model:value="form.taskContent"
          placeholder="请输入任务内容"
          :rows="3"
        />
      </a-form-item>
      <a-form-item label="关联联系人" name="contactId" required>
        <a-select
          v-model:value="form.contactId"
          placeholder="请选择联系人"
          show-search
          :filter-option="(input, option) =>
            option?.label?.toLowerCase().includes(input.toLowerCase())
          "
          :options="
            contactsData?.records?.map((contact) => ({
              value: contact.id,
              label: contact.name,
            })) ?? []
          "
        />
      </a-form-item>
      <a-form-item label="关联销售机会" name="opportunityId" required>
        <a-select
          v-model:value="form.opportunityId"
          placeholder="请选择销售机会"
          show-search
          :filter-option="(input, option) =>
            option?.label?.toLowerCase().includes(input.toLowerCase())
          "
          :options="
            salesData?.records?.map((sale) => ({
              value: sale.id,
              label: sale.opportunityName,
            })) ?? []
          "
        />
      </a-form-item>
      <a-form-item label="任务类型" name="taskType" required>
        <a-input
          v-model:value="form.taskType"
          placeholder="请输入任务类型"
        />
      </a-form-item>
      <a-form-item label="开始时间" name="startTime" required>
        <a-date-picker
          v-model:value="form.startTime"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="请选择开始时间"
          style="width: 100%"
        />
      </a-form-item>
      <a-form-item label="结束时间" name="endTime" required>
        <a-date-picker
          v-model:value="form.endTime"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="请选择结束时间"
          style="width: 100%"
        />
      </a-form-item>
      <a-form-item label="优先级" name="priority" required>
        <a-select
          v-model:value="form.priority"
          placeholder="请选择优先级"
          :options="priorityOptions"
        />
      </a-form-item>
      <a-form-item label="任务执行人" name="assigneeId" required>
        <a-select
          v-model:value="form.assigneeId"
          placeholder="请选择任务执行人"
          show-search
          :loading="isUsersLoading"
          :filter-option="(input, option) =>
            option?.label?.toLowerCase().includes(input.toLowerCase())
          "
          :options="
            usersData?.records?.map((user) => ({
              value: user.id,
              label: user.realName,
            })) ?? []
          "
        />
      </a-form-item>
      <a-form-item label="协助申请" name="assistApplyList">
        <AssistApplyEditor v-model:value="form.assistApplyList" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
