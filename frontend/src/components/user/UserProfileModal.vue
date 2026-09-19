<script setup lang="ts">
import type { PostUserUpdateData } from '@/api/axios';
import { ref } from 'vue';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Modal as AModal,
  message,
} from 'ant-design-vue';
import { useUpdateUser } from '@/hooks/useUser';
import { useQueryClient } from '@tanstack/vue-query';

type UserProfileForm = NonNullable<PostUserUpdateData['body']>;

const form = ref<UserProfileForm>({});
const { mutate: updateUser, isPending: isUpdating } = useUpdateUser();
const innerOpen = ref(false);
const queryClient = useQueryClient();

const open = (data: UserProfileForm) => {
  form.value = { ...data };
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
};

defineExpose({ open });

const submit = () => {
  updateUser(form.value, {
    onSuccess: () => {
      message.success('个人信息更新成功');
      close();
      // Invalidate user queries to refresh data
      void queryClient.invalidateQueries({ queryKey: ['currentUser'] });
    },
    onError: () => {
      message.error('个人信息更新失败');
    },
  });
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="修改个人信息"
    :confirm-loading="isUpdating"
    @ok="submit"
    @cancel="close"
  >
    <a-form
      :model="form"
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 16 }"
      layout="horizontal"
    >
      <a-form-item label="姓名" name="realName">
        <a-input v-model:value="form.realName" placeholder="请输入姓名" />
      </a-form-item>
      <a-form-item label="电话" name="phone">
        <a-input v-model:value="form.phone" placeholder="请输入电话" />
      </a-form-item>
      <a-form-item label="邮箱" name="email">
        <a-input v-model:value="form.email" placeholder="请输入邮箱" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
