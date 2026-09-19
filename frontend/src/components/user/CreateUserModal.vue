<script setup lang="ts">
import type { PostUserAddData } from '@/api/axios';
import { ref } from 'vue';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Popover as APopover,
  Modal as AModal,
  message,
  Select as ASelect,
  SelectOption as ASelectOption,
} from 'ant-design-vue';
import { useCreateUser, useQueryRoles } from '@/hooks/useUser';
import { useQueryDepts } from '@/hooks/useDept';
import { statusMap } from '@/constants/user/constants';
import type { Rule } from 'ant-design-vue/es/form';
import { validateForm } from '@/utils/formValidate';

type CreateUserForm = NonNullable<PostUserAddData['body']>;

const { data: roles } = useQueryRoles({ all: true });
const { data: depts } = useQueryDepts();
const form = ref<CreateUserForm>({
  status: 1, // 默认状态正常
});
const { mutate: createUser, isPending: isCreating } = useCreateUser();
const innerOpen = ref(false);
const formRef = ref();

const rules: Record<string, Rule[]> = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入电话', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  roleId: [{ required: true, message: '请选择角色', trigger: 'change' }],
  deptId: [{ required: true, message: '请选择部门', trigger: 'change' }],
};

const open = () => {
  form.value = {
    status: 1,
    roleId: undefined,
    deptId: undefined,
    realName: '',
    phone: '',
    email: '',
  };
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
};

defineExpose({ open });

const submit = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }

  createUser(form.value, {
    onSuccess: () => {
      message.success('创建用户成功');
      close();
    },
  });
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="新增用户"
    :confirm-loading="isCreating"
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
      <a-form-item label="姓名" name="realName" required>
        <a-input v-model:value="form.realName" placeholder="请输入姓名" />
      </a-form-item>
      <a-form-item label="电话" name="phone" required>
        <a-input v-model:value="form.phone" placeholder="请输入电话" />
      </a-form-item>
      <a-form-item label="邮箱" name="email" required>
        <a-input v-model:value="form.email" placeholder="请输入邮箱" />
      </a-form-item>
      <a-form-item label="部门" name="deptId" required>
        <a-select v-model:value="form.deptId" placeholder="请选择部门">
          <a-select-option v-for="dept in depts ?? []" :key="dept.id" :value="dept.id!">
            {{ dept.deptName }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="角色" name="roleId" required>
        <a-select v-model:value="form.roleId" placeholder="请选择角色">
          <a-select-option
            v-for="item in roles?.records ?? []"
            :key="item.id"
            :value="item.id"
          >
            <a-popover placement="left" style="width: 100%; z-index: 1000">
              <template #content>
                <div>
                  {{ item.roleDesc }}
                </div>
              </template>
              {{ item.roleName }}
            </a-popover>
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
    </a-form>
  </a-modal>
</template>
