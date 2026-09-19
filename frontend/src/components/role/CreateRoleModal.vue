<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputSearch as AInputSearch,
  Textarea as ATextArea,
  Modal as AModal,
  Select as ASelect,
  Checkbox,
  CheckboxGroup,
  Collapse,
  CollapsePanel,
  message,
} from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { validateForm } from '@/utils/formValidate';
import { useCreateRole, useDeleteRole, useQueryRoles } from '@/hooks/useRole';
import { useAddPermissionsToRole } from '@/hooks/usePremission';
import { getPermissionGetByRole, getRoleList } from '@/api/axios';
import apiClient from '@/api/apiClient';
import PERMISSIONS from '@/constants/premission';
import { extractPermissionIds } from '@/types/permission';

interface RoleForm {
  roleName: string;
  roleDesc: string;
}

const form = ref<RoleForm>({
  roleName: '',
  roleDesc: '',
});
const selectedPermissionIds = ref<number[]>([]);
const permissionKeyword = ref('');
const activeKeys = ref<string[]>([]);
const isSubmitting = ref(false);
const templateRoleId = ref<number>();
const { mutateAsync: createRole } = useCreateRole();
const { mutateAsync: addPermissions } = useAddPermissionsToRole();
const { mutateAsync: deleteRole } = useDeleteRole();
const { data: roleData } = useQueryRoles({ all: true });
const innerOpen = ref(false);
const formRef = ref();

const rules: Record<string, Rule[]> = {
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  roleDesc: [{ required: true, message: '请输入角色描述', trigger: 'blur' }],
};

// 使用本地权限数据（与编辑角色权限弹窗一致）
const permissionsByCategory = PERMISSIONS;
const categories = Object.keys(
  permissionsByCategory,
) as (keyof typeof PERMISSIONS)[];

// 可作为权限模板的已有角色
const roleOptions = computed(() =>
  (roleData.value?.records ?? []).map((role) => ({
    label: role.roleName ?? `角色${role.id}`,
    value: role.id,
  })),
);

const getSubPermissions = (
  category: keyof typeof PERMISSIONS,
  parentPermissionId: number,
) => {
  return permissionsByCategory[category].subPermissions.filter(
    (sp) => sp.parentPermissionId === parentPermissionId,
  );
};

interface FilteredMainPermission {
  permission: (typeof PERMISSIONS)[keyof typeof PERMISSIONS]['mainPermissions'][number];
  subs: (typeof PERMISSIONS)[keyof typeof PERMISSIONS]['subPermissions'][number][];
}

// 按关键字过滤权限分类/主权限/子权限，无匹配的主权限连同其子权限一起隐藏
const filteredPermissionGroups = computed(() => {
  const kw = permissionKeyword.value.trim().toLowerCase();
  if (!kw) {
    return categories.map((category) => ({
      category,
      mainPermissions: permissionsByCategory[category].mainPermissions.map(
        (permission) => ({
          permission,
          subs: getSubPermissions(category, permission.id),
        }),
      ),
    }));
  }
  const match = (text: string) => text.toLowerCase().includes(kw);
  return categories
    .map((category) => {
      const mainPermissions = permissionsByCategory[category].mainPermissions
        .map((permission) => {
          const subs = getSubPermissions(category, permission.id).filter((sub) =>
            match(sub.permissionsDesc),
          );
          const mainMatched = match(permission.permissionsDesc);
          if (!mainMatched && subs.length === 0) {
            return null;
          }
          const result: FilteredMainPermission = { permission, subs };
          return result;
        })
        .filter(
          (item): item is FilteredMainPermission => item !== null,
        );
      return { category, mainPermissions };
    })
    .filter((group) => group.mainPermissions.length > 0);
});

// 搜索时自动展开匹配到的分类，清空后恢复全部展开
watch(permissionKeyword, () => {
  if (permissionKeyword.value.trim()) {
    activeKeys.value = filteredPermissionGroups.value.map((group) => group.category);
  } else {
    activeKeys.value = [...categories];
  }
});

const open = () => {
  form.value = {
    roleName: '',
    roleDesc: '',
  };
  templateRoleId.value = undefined;
  selectedPermissionIds.value = [];
  permissionKeyword.value = '';
  activeKeys.value = [...categories];
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  selectedPermissionIds.value = [];
  activeKeys.value = [];
};

defineExpose({ open, close });

// 基于某个已有角色的权限初始化勾选，之后可自由增删
const applyTemplatePermissions = async (value: unknown) => {
  const roleId = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(roleId) || roleId <= 0) {
    selectedPermissionIds.value = [];
    return;
  }
  try {
    const res = await getPermissionGetByRole({
      client: apiClient,
      query: { roleId },
    });
    selectedPermissionIds.value = extractPermissionIds(res.data?.data);
  } catch {
    message.error('复制角色权限失败');
    selectedPermissionIds.value = [];
  }
};

const submit = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }

  const roleName = form.value.roleName.trim();
  if (!roleName) {
    return;
  }

  isSubmitting.value = true;
  try {
    await createRole({
      roleName,
      roleDesc: form.value.roleDesc.trim(),
    });
  } catch {
    message.error('创建角色失败');
    isSubmitting.value = false;
    return;
  }

  // 后端创建接口只返回布尔值，通过角色列表按名称定位新角色
  let roleId: number | undefined;
  try {
    const res = await getRoleList({
      client: apiClient,
      query: { pageNum: 1, pageSize: 1000 },
    });
    roleId = (res.data?.data?.records ?? []).reduce<number | undefined>(
      (maxId, role) =>
        role.roleName === roleName && role.id !== undefined &&
        (maxId === undefined || role.id > maxId)
          ? role.id
          : maxId,
      undefined,
    );
  } catch {
    // 查询失败时走下方“未能定位角色”提示
  }

  if (!roleId) {
    message.error('角色已创建，但未能定位角色，请到“编辑权限”中配置');
    isSubmitting.value = false;
    close();
    return;
  }

  if (selectedPermissionIds.value.length > 0) {
    try {
      await addPermissions({
        roleId,
        permissionIds: selectedPermissionIds.value,
      });
    } catch {
      // 绑定权限失败：回滚删除刚创建的角色
      try {
        await deleteRole({ roleId });
      } catch {
        // 回滚失败时仅提示，避免覆盖原始错误
      }
      message.error('权限绑定失败，已回滚删除该角色');
      isSubmitting.value = false;
      return;
    }
  }

  message.success('创建角色成功');
  isSubmitting.value = false;
  close();
};
</script>

<template>
  <AModal
    v-model:open="innerOpen"
    title="新增角色"
    :confirm-loading="isSubmitting"
    width="700px"
    @ok="submit"
    @cancel="close"
  >
    <AForm
      ref="formRef"
      :model="form"
      :rules="rules"
      :label-col="{ span: 5 }"
      :wrapper-col="{ span: 18 }"
      layout="horizontal"
    >
      <AFormItem label="角色名称" name="roleName" required>
        <AInput v-model:value="form.roleName" placeholder="请输入角色名称" />
      </AFormItem>
      <AFormItem label="角色描述" name="roleDesc" required>
        <ATextArea
          v-model:value="form.roleDesc"
          placeholder="请输入角色描述"
          :rows="3"
        />
      </AFormItem>
      <AFormItem label="复制角色权限">
        <ASelect
          v-model:value="templateRoleId"
          :options="roleOptions"
          allow-clear
          placeholder="可选：基于某个角色的权限初始化，再增删"
          @change="applyTemplatePermissions"
        />
      </AFormItem>
      <AFormItem label="角色权限">
        <AInputSearch
          v-model:value="permissionKeyword"
          placeholder="搜索权限名称"
          allow-clear
          style="margin-bottom: 8px"
        />
        <div
          v-if="filteredPermissionGroups.length === 0"
          style="text-align: center; padding: 24px 0; color: #999"
        >
          {{ permissionKeyword.trim() ? '无匹配的权限' : '暂无权限数据' }}
        </div>
        <div
          v-else
          style="
            max-height: 360px;
            overflow-y: auto;
            padding-right: 8px;
            border: 1px solid #f0f0f0;
            border-radius: 6px;
            padding: 8px;
          "
        >
          <CheckboxGroup v-model:value="selectedPermissionIds">
            <Collapse v-model:active-key="activeKeys" ghost>
              <CollapsePanel
                v-for="group in filteredPermissionGroups"
                :key="group.category"
                :header="group.category"
              >
                <template
                  v-for="{ permission, subs } in group.mainPermissions"
                  :key="permission.id"
                >
                  <div
                    style="
                      display: grid;
                      grid-template-columns: repeat(2, 1fr);
                      gap: 8px;
                    "
                  >
                    <Checkbox
                      :value="permission.id"
                      style="
                        padding: 10px 12px;
                        border: 1px solid #f0f0f0;
                        border-radius: 6px;
                        background: #fafafa;
                        margin-right: 0;
                        display: flex;
                        align-items: flex-start;
                        gap: 8px;
                      "
                    >
                      <div style="flex: 1; font-size: 14px; color: #262626">
                        {{ permission.permissionsDesc }}
                      </div>
                    </Checkbox>
                  </div>
                  <div
                    v-if="subs.length > 0"
                    style="
                      margin-left: 32px;
                      margin-top: 4px;
                      display: grid;
                      grid-template-columns: repeat(2, 1fr);
                      gap: 8px;
                    "
                  >
                    <Checkbox
                      v-for="sub in subs"
                      :key="sub.id"
                      :value="sub.id"
                      style="
                        padding: 8px 12px;
                        border: 1px solid #e6f4ff;
                        border-radius: 6px;
                        background: #f0f7ff;
                        margin-right: 0;
                        display: flex;
                        align-items: flex-start;
                        gap: 8px;
                      "
                    >
                      <div style="flex: 1; font-size: 13px; color: #595959">
                        {{ sub.permissionsDesc }}
                      </div>
                    </Checkbox>
                  </div>
                </template>
              </CollapsePanel>
            </Collapse>
          </CheckboxGroup>
        </div>
      </AFormItem>
    </AForm>
  </AModal>
</template>
