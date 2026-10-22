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
  Spin,
  message,
} from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { validateForm } from '@/utils/formValidate';
import { useCreateRole, useDeleteRole, useQueryRoles } from '@/hooks/useRole';
import { useAddPermissionsToRole, useQueryAllPermissions } from '@/hooks/usePremission';
import { getPermissionGetByRole, getRoleList } from '@/api/axios';
import type { PermissionVo, SubPermissionVo } from '@/api/axios';
import apiClient from '@/api/apiClient';
import { extractPermissionIds } from '@/types/permission';
import {
  buildPermissionCategories,
  filterCategories,
  idsOfCategory,
  moduleSelectionCounts,
  subPermissionsOf,
} from '@/utils/permissionCatalog';

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
const onlySelectedMode = ref(false);
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

// 权限目录来源：GET /permission/list（与编辑角色权限弹窗同源，后端 PermissionModule 号段单一事实源）
const {
  data: permissionCatalog,
  isLoading: isLoadingCatalog,
  isError: isCatalogError,
  refetch: refetchCatalog,
} = useQueryAllPermissions();

const categories = computed(() => buildPermissionCategories(permissionCatalog.value));
const categoryNames = computed(() => categories.value.map((category) => category.module));

// 可作为权限模板的已有角色
const roleOptions = computed(() =>
  (roleData.value?.records ?? []).map((role) => ({
    label: role.roleName ?? `角色${role.id}`,
    value: role.id,
  })),
);

interface FilteredMainPermission {
  permission: PermissionVo;
  subs: SubPermissionVo[];
}

interface FilteredPermissionGroup {
  category: string;
  mainPermissions: FilteredMainPermission[];
  orphanSub: SubPermissionVo[];
}

// 按关键字 / 只看已选过滤权限分类，无匹配的主权限连同其子权限一起隐藏。
// 复用与编辑弹窗同一套纯函数（filterCategories / moduleSelectionCounts / idsOfCategory），模板结构不变。
const filteredPermissionGroups = computed<FilteredPermissionGroup[]>(() =>
  filterCategories(categories.value, {
    keyword: permissionKeyword.value,
    onlySelected: onlySelectedMode.value,
    selectedIds: selectedPermissionIds.value,
  }).map((category) => ({
    category: category.module,
    mainPermissions: category.main.map((permission) => ({
      permission,
      subs: subPermissionsOf(category, permission.id),
    })),
    orphanSub: category.orphanSub,
  })),
);

const selectionCounts = computed(() =>
  moduleSelectionCounts(categories.value, selectedPermissionIds.value),
);
const countsByModule = computed(
  () => new Map(selectionCounts.value.map((count) => [count.module, count])),
);
const countOf = (module: string) =>
  countsByModule.value.get(module) ?? { module, selected: 0, total: 0 };

// 模块级全选 / 清空：用未过滤的整模块 id 集合
const selectCategoryAll = (module: string) => {
  const category = categories.value.find((item) => item.module === module);
  const merged = new Set([...selectedPermissionIds.value, ...idsOfCategory(category)]);
  selectedPermissionIds.value = [...merged];
};

const clearCategory = (module: string) => {
  const category = categories.value.find((item) => item.module === module);
  const ids = new Set(idsOfCategory(category));
  selectedPermissionIds.value = selectedPermissionIds.value.filter((id) => !ids.has(id));
};

// 搜索/只看已选时自动展开匹配到的分类，条件清空后恢复全部展开
watch([permissionKeyword, onlySelectedMode], () => {
  if (permissionKeyword.value.trim() || onlySelectedMode.value) {
    activeKeys.value = filteredPermissionGroups.value.map((group) => group.category);
  } else {
    activeKeys.value = [...categoryNames.value];
  }
});

const retryCatalog = () => {
  void refetchCatalog();
};

const open = () => {
  form.value = {
    roleName: '',
    roleDesc: '',
  };
  templateRoleId.value = undefined;
  selectedPermissionIds.value = [];
  permissionKeyword.value = '';
  onlySelectedMode.value = false;
  activeKeys.value = [...categoryNames.value];
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
        <div
          v-if="isLoadingCatalog"
          style="text-align: center; padding: 24px 0"
        >
          <Spin tip="加载权限目录..." />
        </div>
        <div
          v-else-if="isCatalogError"
          style="text-align: center; padding: 24px 0; color: #999"
        >
          权限目录加载失败，
          <a @click="retryCatalog">重试</a>
        </div>
        <template v-else>
          <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
            <AInputSearch
              v-model:value="permissionKeyword"
              placeholder="搜索权限：中文描述 / 权限名 / ID，空格分词"
              allow-clear
              style="flex: 1"
            />
            <Checkbox v-model:checked="onlySelectedMode">只看已选</Checkbox>
            <span style="color: #8c8c8c; font-size: 12px; white-space: nowrap">
              已选 {{ selectedPermissionIds.length }}
            </span>
          </div>
          <div
            v-if="filteredPermissionGroups.length === 0"
            style="text-align: center; padding: 24px 0; color: #999"
          >
            {{
              permissionKeyword.trim()
                ? '无匹配的权限'
                : onlySelectedMode
                  ? '尚未勾选任何权限'
                  : '暂无权限数据'
            }}
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
                >
                  <template #header>
                    <div
                      style="
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        width: 100%;
                      "
                      @click.stop
                    >
                      <span>{{ group.category }}</span>
                      <span style="color: #8c8c8c; font-size: 12px">
                        {{ countOf(group.category).selected }}/{{
                          countOf(group.category).total
                        }}
                      </span>
                      <span style="flex: 1" />
                      <a
                        style="font-size: 12px"
                        @click.stop="selectCategoryAll(group.category)"
                      >
                        全选
                      </a>
                      <a
                        style="font-size: 12px"
                        @click.stop="clearCategory(group.category)"
                      >
                        清空
                      </a>
                    </div>
                  </template>
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
                        :value="permission.id as number"
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
                        :value="sub.id as number"
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
                  <div
                    v-if="group.orphanSub.length > 0"
                    style="
                      margin-left: 32px;
                      margin-top: 4px;
                      display: grid;
                      grid-template-columns: repeat(2, 1fr);
                      gap: 8px;
                    "
                  >
                    <Checkbox
                      v-for="sub in group.orphanSub"
                      :key="sub.id"
                      :value="sub.id as number"
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
                </CollapsePanel>
              </Collapse>
            </CheckboxGroup>
          </div>
        </template>
      </AFormItem>
    </AForm>
  </AModal>
</template>
