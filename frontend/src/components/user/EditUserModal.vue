<script setup lang="ts">
import type { PostUserFindResponse, PostUserUpdateData } from '@/api/axios';
import { ref } from 'vue';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Modal as AModal,
  message,
  Select as ASelect,
  SelectOption as ASelectOption,
  Button as AButton,
} from 'ant-design-vue';
import { useUpdateUser, useQueryRoles } from '@/hooks/useUser';
import { useQueryDepts } from '@/hooks/useDept';
import { statusMap } from '@/constants/user/constants';
import ResetPasswordModal from './ResetPasswordModal.vue';

type UserRecord = NonNullable<NonNullable<PostUserFindResponse['data']>['records']>[number];
type UpdateUserForm = NonNullable<PostUserUpdateData['body']>;

const resetPasswordModalRef = ref<InstanceType<typeof ResetPasswordModal>>();
const openResetPasswordModal = () => {
  resetPasswordModalRef.value?.openResetPasswordModal(form.value.id);
};
const { data: roles } = useQueryRoles({ all: true });
const { data: depts } = useQueryDepts();
const form = ref<UpdateUserForm>({});
const { mutate: updateUser, isPending: isUpdating } = useUpdateUser();
const innerOpen = ref(false);

const open = (data: UserRecord) => {
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
      message.success('更新用户成功');
      close();
    },
    onError: () => {
      message.error('更新用户失败');
    },
  });
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="编辑用户"
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
      <a-form-item label="部门" name="deptId">
        <a-select v-model:value="form.deptId" placeholder="请选择部门" allow-clear>
          <a-select-option v-for="dept in depts ?? []" :key="dept.id" :value="dept.id!">
            {{ dept.deptName }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="角色" name="roleId">
        <a-select v-model:value="form.roleId" placeholder="请选择角色">
          <a-select-option
            v-for="item in roles?.records ?? []"
            :key="item.id"
            :value="item.id"
          >
            {{ item.roleName }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="状态" name="status">
        <a-select v-model:value="form.status" placeholder="请选择状态">
          <a-select-option
            v-for="(label, value) in statusMap"
            :key="value"
            :value="Number(value)"
          >
            {{ label }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="密码">
        <a-button type="primary" danger @click="openResetPasswordModal"
          >重置密码</a-button
        >
      </a-form-item>
    </a-form>
  </a-modal>
  <ResetPasswordModal ref="resetPasswordModalRef" />
</template>
