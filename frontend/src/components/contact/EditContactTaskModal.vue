<script setup lang="ts">
import type {
  PostContactTaskQueryResponse,
  PutContactTaskUpdateData,
} from '@/api/axios';
import { ref } from 'vue';
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
import { useUpdateContactTask } from '@/hooks/useContactTask';
import { useQueryCompanies } from '@/hooks/useCompany';
import { useQueryContacts } from '@/hooks/useContact';
import { useQuerySales } from '@/hooks/useSale';
import { useQueryUsers } from '@/hooks/useUser';
import { validateForm } from '@/utils/formValidate';
import type { Rule } from 'ant-design-vue/es/form';

type ContactTaskListItem = NonNullable<
  NonNullable<PostContactTaskQueryResponse['data']>['records']
>[number];
type ContactTaskForm = NonNullable<PutContactTaskUpdateData['body']>;

const taskStatusLabelToValue: Record<string, NonNullable<ContactTaskForm['status']>> = {
  待处理: 0,
  未开始: 0,
  进行中: 1,
  已完成: 2,
  已取消: 3,
  '0': 0,
  '1': 1,
  '2': 2,
  '3': 3,
};

const priorityLabelToValue: Record<string, NonNullable<ContactTaskForm['priority']>> = {
  低: 0,
  中: 1,
  高: 2,
  紧急: 3,
  '0': 0,
  '1': 1,
  '2': 2,
  '3': 3,
};

const toOptionValue = <T extends number>(
  value: string | undefined,
  mapping: Record<string, T>,
): T | undefined => {
  if (!value) {
    return undefined;
  }

  return mapping[value];
};

const form = ref<ContactTaskForm>({});
const { mutate: updateContactTask, isPending: isUpdating } =
  useUpdateContactTask();
const innerOpen = ref(false);
const formRef = ref();

const rules: Record<string, Rule[]> = {
  taskTitle: [{ required: true, message: '请输入任务标题', trigger: 'blur' }],
  endTime: [
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
};

// 获取所有公司列表
const { data: companiesData } = useQueryCompanies({
  all: true,
  current: 1,
  pageSize: 1000,
});

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

// 任务状���映射
const taskStatusOptions = [
  { label: '待处理', value: 0 },
  { label: '进行中', value: 1 },
  { label: '已完成', value: 2 },
  { label: '已取消', value: 3 },
];

// 优先级映射
const priorityOptions = [
  { label: '低', value: 0 },
  { label: '中', value: 1 },
  { label: '高', value: 2 },
  { label: '紧急', value: 3 },
];

const open = (data: ContactTaskListItem) => {
  form.value = {
    id: data.id,
    taskTitle: data.taskTitle,
    taskContent: data.taskContent,
    companyId: data.companyId,
    contactId: data.contactId,
    opportunityId: data.opportunityId,
    taskType: data.taskType,
    startTime: data.startTime,
    endTime: data.endTime,
    priority: toOptionValue(data.priority, priorityLabelToValue),
    status: toOptionValue(data.status, taskStatusLabelToValue),
    assigneeId: data.assigneeId,
  };
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
};

defineExpose({ open, close });

const submit = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }

  updateContactTask(form.value, {
    onSuccess: () => {
      message.success('更新任务成功');
      close();
    },
  });
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="编辑联络任务"
    :confirm-loading="isUpdating"
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
      <a-form-item label="任务内容" name="taskContent">
        <a-textarea
          v-model:value="form.taskContent"
          placeholder="请输入任务内容"
          :rows="3"
        />
      </a-form-item>
      <a-form-item label="关联公司" name="companyId">
        <a-select
          v-model:value="form.companyId"
          placeholder="请选择公司"
          show-search
          :filter-option="(input, option) =>
            option?.label?.toLowerCase().includes(input.toLowerCase())
          "
          :options="
            companiesData?.records?.map((company) => ({
              value: company.id,
              label: company.companyName,
            })) ?? []
          "
        />
      </a-form-item>
      <a-form-item label="关联联系人" name="contactId">
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
      <a-form-item label="关联销售机会" name="opportunityId">
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
      <a-form-item label="任务类型" name="taskType">
        <a-input
          v-model:value="form.taskType"
          placeholder="请输入任务类型"
        />
      </a-form-item>
      <a-form-item label="开始时间" name="startTime">
        <a-date-picker
          v-model:value="form.startTime"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="请选择开始时间"
          style="width: 100%"
        />
      </a-form-item>
      <a-form-item label="结束时间" name="endTime">
        <a-date-picker
          v-model:value="form.endTime"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="请选择结束时间"
          style="width: 100%"
        />
      </a-form-item>
      <a-form-item label="优先级" name="priority">
        <a-select
          v-model:value="form.priority"
          placeholder="请选择优先级"
          :options="priorityOptions"
        />
      </a-form-item>
      <a-form-item label="任务状态" name="status">
        <a-select
          v-model:value="form.status"
          placeholder="请选择任务状态"
          :options="taskStatusOptions"
        />
      </a-form-item>
      <a-form-item label="任务执行人" name="assigneeId">
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
    </a-form>
  </a-modal>
</template>
