<script setup lang="ts">
import { ref } from 'vue';
import { useResetPassword } from '@/hooks/useUser';
import { message } from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { validateForm } from '@/utils/formValidate';
import {
  Form as AForm,
  FormItem as AFormItem,
  InputPassword as AInputPassword,
  Modal as AModal,
} from 'ant-design-vue';

const open = ref(false);
const formRef = ref();
const passwordForm = ref({ newPassword: '' });
const rules: Record<string, Rule[]> = {
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }],
};
const { mutate: resetPassword, isPending } = useResetPassword();

const openModal = () => {
  passwordForm.value.newPassword = '';
  open.value = true;
};

const closeModal = () => {
  open.value = false;
  passwordForm.value.newPassword = '';
};

const submit = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }
  resetPassword(passwordForm.value.newPassword, {
    onSuccess: () => {
      message.success('密码修改成功');
      closeModal();
    },
  });
};

defineExpose({ openModal });
</script>

<template>
  <a-modal
    v-model:open="open"
    title="修改密码"
    :confirm-loading="isPending"
    @ok="submit"
    @cancel="closeModal"
  >
    <a-form
      ref="formRef"
      :model="passwordForm"
      :rules="rules"
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 16 }"
    >
      <a-form-item label="新密码" name="newPassword" required>
        <a-input-password
          v-model:value="passwordForm.newPassword"
          placeholder="请输入新密码"
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
