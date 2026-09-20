<script setup lang="ts">
import type { PostBusinessActivityData } from '@/api/axios';
import { ref } from 'vue';
import type { Rule } from 'ant-design-vue/es/form';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputNumber as AInputNumber,
  Modal as AModal,
  message,
  Select as ASelect,
  DatePicker as ADatePicker,
  Textarea as ATextarea,
} from 'ant-design-vue';
import { useCreateActivity } from '@/hooks/useActivity';
import { useQuerySales } from '@/hooks/useSale';
import { useQueryContactTasks } from '@/hooks/useContactTask';
import { validateForm } from '@/utils/formValidate';
import AssistApplyEditor from '@/components/assist/AssistApplyEditor.vue';
import type { AssistApplyItem } from '@/hooks/useAssist';

type ActivityForm = NonNullable<PostBusinessActivityData['body']>;

const createDefaultForm = (): ActivityForm => ({
  isAddUser: false,
  isAddContact: false,
  activityTitle: '',
  activityContent: '',
  activityTime: '',
  activityType: undefined,
  activityDuration: undefined,
  opportunityId: undefined,
  remark: '',
  userIdList: [],
  contactIdList: [],
  taskId: undefined,
  nextTaskStatus: undefined,
  assistApplyList: [],
});

const form = ref<ActivityForm>(createDefaultForm());
const { mutate: createActivity, isPending: isCreating } = useCreateActivity();
const innerOpen = ref(false);
const formRef = ref();

// 表单验证规则
const rules: Record<string, Rule[]> = {
  activityTitle: [{ required: true, message: '请输入活动标题', trigger: 'blur' }],
  activityContent: [{ required: true, message: '请输入活动内容', trigger: 'blur' }],
  activityTime: [{ required: true, message: '请选择活动时间', trigger: 'change' }],
  activityType: [{ required: true, message: '请输入活动类型', trigger: 'blur' }],
  opportunityId: [{ required: true, message: '请选择销售订单', trigger: 'change' }],
};

// 获取所有销售机会列表
const { data: salesData } = useQuerySales({
  all: true,
});

// 获取所有联络任务列表
const { data: contactTasksData } = useQueryContactTasks({
  all: true,
});

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

  // 创建活动
  createActivity(form.value, {
    onSuccess: () => {
      message.success('创建活动成功');
      close();
    },
  });
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="新增业务活动"
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
      <a-form-item label="活动标题" name="activityTitle" required>
        <a-input
          v-model:value="form.activityTitle"
          placeholder="请输入活动标题"
        />
      </a-form-item>
      <a-form-item label="活动内容" name="activityContent" required>
        <a-textarea
          v-model:value="form.activityContent"
          placeholder="请输入活动内容"
          :rows="3"
        />
      </a-form-item>
      <a-form-item label="活动时间" name="activityTime" required>
        <a-date-picker
          v-model:value="form.activityTime"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="请选择活动时间"
          style="width: 100%"
        />
      </a-form-item>
      <a-form-item label="活动类型" name="activityType" required>
        <a-input
          v-model:value="form.activityType"
          placeholder="请输入活动类型"
        />
      </a-form-item>
      <a-form-item label="活动时长" name="activityDuration">
          <AInputNumber
            v-model:value="form.activityDuration"
            placeholder="请输入活动时长（分钟）"
            :min="0"
            style="width: 100%"
          />
      </a-form-item>
      <a-form-item label="销售订单" name="opportunityId" required>
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
      <a-form-item label="联络任务" name="taskId">
        <a-select
          v-model:value="form.taskId"
          placeholder="请选择联络任务（可选）"
          show-search
          :filter-option="(input, option) =>
            option?.label?.toLowerCase().includes(input.toLowerCase())
          "
          :options="
            contactTasksData?.records?.map((task) => ({
              value: task.id,
              label: task.taskTitle,
            })) ?? []
          "
        />
      </a-form-item>
      <a-form-item v-if="form.taskId" label="任务状态" name="nextTaskStatus">
        <a-select
          v-model:value="form.nextTaskStatus"
          placeholder="请更新任务状态"
          :options="[
            { value: 0, label: '未开始' },
            { value: 1, label: '进行中' },
            { value: 2, label: '已完成' },
            { value: 3, label: '已取消' },
          ]"
        />
      </a-form-item>
      <a-form-item label="备注" name="remark">
        <a-textarea
          v-model:value="form.remark"
          placeholder="请输入备注"
          :rows="2"
        />
      </a-form-item>
      <a-form-item label="协助申请" name="assistApplyList">
        <AssistApplyEditor v-model:value="form.assistApplyList" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
