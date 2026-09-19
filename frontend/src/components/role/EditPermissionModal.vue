<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Modal as AModal,
  Checkbox,
  CheckboxGroup,
  message,
  Spin,
  Collapse,
  CollapsePanel,
} from 'ant-design-vue';
import { useQueryPermissionsByRole } from '@/hooks/usePremission';
import {
  useAddPermissionsToRole,
  useDeletePermissionsFromRole,
} from '@/hooks/usePremission';
import type { GetRoleListResponse } from '@/api/axios';
import { extractPermissionIds } from '@/types/permission';
import PERMISSIONS from '@/constants/premission';

type RoleRecord = NonNullable<NonNullable<GetRoleListResponse['data']>['records']>[number];

const innerOpen = ref(false);
const currentRole = ref<RoleRecord>();
const selectedPermissionIds = ref<number[]>([]);
const originalPermissionIds = ref<number[]>([]);

// 使用本地权限数据
const permissionsByCategory = PERMISSIONS;

// 获取所有分类名称（静态常量，不需要 computed）
const categories = Object.keys(
  permissionsByCategory,
) as (keyof typeof PERMISSIONS)[];

// 获取某个分类下，某个主权限对应的子权限
const getSubPermissions = (
  category: keyof typeof PERMISSIONS,
  parentPermissionId: number,
) => {
  return permissionsByCategory[category].subPermissions.filter(
    (sp) => sp.parentPermissionId === parentPermissionId,
  );
};
const currentId = ref<number>();
// 获取当前角色权限
const {
  data: rolePermissionsData,
  isLoading: isLoadingRolePermissions,
  refetch: refetchRolePermissions,
} = useQueryPermissionsByRole(currentId);

// 折叠面板的激活 key
const activeKeys = ref<string[]>([]);

// 监听弹窗打开，初始化选中状态和展开所有分类
watch(innerOpen, (isOpen) => {
  if (isOpen && currentRole.value) {
    selectedPermissionIds.value = [...rolePermissionIds.value];
    originalPermissionIds.value = [...rolePermissionIds.value];
    // 默认展开所有分类
    activeKeys.value = [...categories];
  }
});

// 获取角色已有权限 ID
const rolePermissionIds = computed(
  () => extractPermissionIds(rolePermissionsData.value),
);

// 监听角色权限变化，同步更新选中状态（只在弹窗打开时）
watch(rolePermissionIds, (newIds) => {
  if (innerOpen.value && newIds.length > 0) {
    selectedPermissionIds.value = [...newIds];
    originalPermissionIds.value = [...newIds];
  }
});

const open = (role: RoleRecord) => {
  currentRole.value = role;
  currentId.value = role.id;
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  currentRole.value = undefined;
  selectedPermissionIds.value = [];
  originalPermissionIds.value = [];
  activeKeys.value = [];
};

defineExpose({ open, close });

const { mutateAsync: addPermissions, isPending: isAdding } =
  useAddPermissionsToRole();
const { mutateAsync: deletePermissions, isPending: isDeleting } =
  useDeletePermissionsFromRole();

const isSaving = ref(false);

watch([isAdding, isDeleting], ([adding, deleting]) => {
  isSaving.value = adding || deleting;
});

const submit = async () => {
  if (!currentRole.value?.id) {
    message.error('角色信息无效');
    return;
  }

  const roleId = currentRole.value.id;

  // 计算需要添加的权限（选中但原来没有的）
  const toAdd = selectedPermissionIds.value.filter(
    (id) => !originalPermissionIds.value.includes(id),
  );

  // 计算需要删除的权限（原来有但现在没选的）
  const toRemove = originalPermissionIds.value.filter(
    (id) => !selectedPermissionIds.value.includes(id),
  );

  // 如果没有变化，直接关闭
  if (toAdd.length === 0 && toRemove.length === 0) {
    message.info('权限未发生变化');
    close();
    return;
  }

  try {
    // 并行执行删除和添加操作
    const results = await Promise.allSettled([
      toRemove.length > 0
        ? deletePermissions({ roleId, permissionIds: toRemove })
        : Promise.resolve(),
      toAdd.length > 0
        ? addPermissions({ roleId, permissionIds: toAdd })
        : Promise.resolve(),
    ]);

    // 检查是否有操作失败
    const hasError = results.some((result) => result.status === 'rejected');

    if (hasError) {
      message.warning('权限更新部分成功，请检查');
    } else {
      message.success('权限更新成功');
    }
    void refetchRolePermissions();
    close();
  } catch {
    message.error('权限更新失败');
  }
};
</script>

<template>
  <AModal
    v-model:open="innerOpen"
    title="编辑角色权限"
    :confirm-loading="isSaving"
    width="600px"
    @ok="submit"
    @cancel="close"
  >
    <div
      v-if="isLoadingRolePermissions"
      style="text-align: center; padding: 40px 0"
    >
      <Spin tip="加载角色权限..." />
    </div>
    <div v-else>
      <div style="margin-bottom: 16px; color: #666">
        角色：<strong>{{ currentRole?.roleName }}</strong>
      </div>

      <div
        v-if="categories.length === 0"
        style="text-align: center; padding: 40px 0; color: #999"
      >
        暂无权限数据
      </div>

      <div
        v-else
        style="max-height: 450px; overflow-y: auto; padding-right: 8px"
      >
        <CheckboxGroup v-model:value="selectedPermissionIds">
          <Collapse v-model:active-key="activeKeys" ghost>
            <CollapsePanel
              v-for="category in categories"
              :key="category"
              :header="category"
            >
              <template
                v-for="permission in permissionsByCategory[category].mainPermissions"
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
                  v-if="getSubPermissions(category, permission.id).length > 0"
                  style="
                    margin-left: 32px;
                    margin-top: 4px;
                    display: grid;
                    grid-template-columns: repeat(2, 1fr);
                    gap: 8px;
                  "
                >
                  <Checkbox
                    v-for="sub in getSubPermissions(category, permission.id)"
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
    </div>
  </AModal>
</template>
