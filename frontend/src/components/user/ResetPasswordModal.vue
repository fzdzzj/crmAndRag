<script setup lang="ts">
import { ref } from 'vue';
import { useAdminResetPassword } from '@/hooks/useUser';
import { message } from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { validateForm } from '@/utils/formValidate';
import type { PostUserPasswordResetData } from '@/api/axios';
import {
  Form as AForm,
  FormItem as AFormItem,
  InputPassword as AInputPassword,
  Modal as AModal,
} from 'ant-design-vue';

type ResetPasswordPayload = NonNullable<PostUserPasswordResetData['body']>;

const resetPasswordOpen = ref(false);
const formRef = ref();
const form = ref<Pick<ResetPasswordPayload, 'userId'> & { newPassword: string }>({
  newPassword: '',
});
const rules: Record<string, Rule[]> = {
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }],
};
const { mutate: resetPassword, isPending: isResetting } = useAdminResetPassword();
const openResetPasswordModal = (userId: ResetPasswordPayload['userId']) => {
  form.value.userId = userId;
  form.value.newPassword = '';
  resetPasswordOpen.value = true;
};

const closeResetPasswordModal = () => {
  resetPasswordOpen.value = false;
  form.value.newPassword = '';
};

const submitResetPassword = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }
  if (!form.value.userId) {
    message.error('用户ID不存在');
    return;
  }
  resetPassword(
    { userId: form.value.userId, newPassword: form.value.newPassword },
    {
      onSuccess: () => {
        message.success('重置密码成功');
        closeResetPasswordModal();
      },
      onError: () => {
        message.error('重置密码失败');
      },
    },
  );
};

defineExpose({ openResetPasswordModal });
</script>
<template>
  <a-modal
    v-model:open="resetPasswordOpen"
    title="重置密码"
    :confirm-loading="isResetting"
    @ok="submitResetPassword"
    @cancel="closeResetPasswordModal"
  >
    <a-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 16 }"
    >
      <a-form-item label="新密码" name="newPassword" required>
        <a-input-password
          v-model:value="form.newPassword"
          placeholder="请输入新密码"
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
