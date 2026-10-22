<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Modal as AModal,
  Checkbox,
  CheckboxGroup,
  InputSearch as AInputSearch,
  message,
  Spin,
  Collapse,
  CollapsePanel,
} from 'ant-design-vue';
import { useQueryAllPermissions, useQueryPermissionsByRole } from '@/hooks/usePremission';
import {
  useAddPermissionsToRole,
  useDeletePermissionsFromRole,
} from '@/hooks/usePremission';
import type { GetRoleListResponse } from '@/api/axios';
import { extractPermissionIds } from '@/types/permission';
import {
  buildPermissionCategories,
  filterCategories,
  idsOfCategory,
  moduleSelectionCounts,
  subPermissionsOf,
} from '@/utils/permissionCatalog';

type RoleRecord = NonNullable<NonNullable<GetRoleListResponse['data']>['records']>[number];

const innerOpen = ref(false);
const currentRole = ref<RoleRecord>();
const selectedPermissionIds = ref<number[]>([]);
const originalPermissionIds = ref<number[]>([]);

// 权限目录来源：GET /permission/list（后端 PermissionModule 号段单一事实源），不再用冻结快照
const {
  data: permissionCatalog,
  isLoading: isLoadingCatalog,
  isError: isCatalogError,
  refetch: refetchCatalog,
} = useQueryAllPermissions();

const categories = computed(() => buildPermissionCategories(permissionCatalog.value));
const categoryNames = computed(() => categories.value.map((category) => category.module));

// ---- 目录定位（搜索 / 只看已选 / 模块级全选）----
// 全部为纯函数计算，提交链路（toAdd / toRemove）不参与、不受影响。
const permissionKeyword = ref('');
const onlySelectedMode = ref(false);

const filteredCategories = computed(() =>
  filterCategories(categories.value, {
    keyword: permissionKeyword.value,
    onlySelected: onlySelectedMode.value,
    selectedIds: selectedPermissionIds.value,
  }),
);

const selectionCounts = computed(() =>
  moduleSelectionCounts(categories.value, selectedPermissionIds.value),
);
const countsByModule = computed(
  () => new Map(selectionCounts.value.map((count) => [count.module, count])),
);
const countOf = (module: string) =>
  countsByModule.value.get(module) ?? { module, selected: 0, total: 0 };

const selectedTotal = computed(() => selectedPermissionIds.value.length);
const permissionTotal = computed(() =>
  categories.value.reduce((sum, category) => sum + idsOfCategory(category).length, 0),
);

// 模块级全选 / 清空：用未过滤的整模块 id 集合，搜索态下也不会只选中当前可见项
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

const currentId = ref<number>();
// 获取当前角色权限
const {
  data: rolePermissionsData,
  isLoading: isLoadingRolePermissions,
  refetch: refetchRolePermissions,
} = useQueryPermissionsByRole(currentId);

const isLoading = computed(
  () => isLoadingRolePermissions.value || isLoadingCatalog.value,
);

// 折叠面板的激活 key
const activeKeys = ref<string[]>([]);

// 监听弹窗打开，初始化选中状态和展开所有分类
watch(innerOpen, (isOpen) => {
  if (isOpen && currentRole.value) {
    selectedPermissionIds.value = [...rolePermissionIds.value];
    originalPermissionIds.value = [...rolePermissionIds.value];
  }
});

// 目录异步到达或弹窗打开时同步展开项（模块集合只在目录变化时变，不影响手动折叠）
watch([innerOpen, categoryNames], ([isOpen, names]) => {
  if (isOpen) {
    activeKeys.value = [...names];
  }
});

// 搜索/只看已选变化时只展开命中的模块；条件清空后恢复全展开
watch([permissionKeyword, onlySelectedMode], () => {
  if (permissionKeyword.value.trim() || onlySelectedMode.value) {
    activeKeys.value = filteredCategories.value.map((category) => category.module);
  } else {
    activeKeys.value = [...categoryNames.value];
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
  permissionKeyword.value = '';
  onlySelectedMode.value = false;
};

const retryCatalog = () => {
  void refetchCatalog();
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
    <div v-if="isLoading" style="text-align: center; padding: 40px 0">
      <Spin tip="加载权限目录..." />
    </div>
    <div
      v-else-if="isCatalogError"
      style="text-align: center; padding: 40px 0; color: #999"
    >
      权限目录加载失败，
      <a @click="retryCatalog">重试</a>
    </div>
    <div v-else>
      <div style="margin-bottom: 16px; color: #666">
        角色：<strong>{{ currentRole?.roleName }}</strong>
      </div>

      <div
        style="display: flex; align-items: center; gap: 12px; margin-bottom: 12px"
      >
        <AInputSearch
          v-model:value="permissionKeyword"
          placeholder="搜索权限：中文描述 / 权限名 / ID，空格分词"
          allow-clear
          style="flex: 1"
        />
        <Checkbox v-model:checked="onlySelectedMode">只看已选</Checkbox>
        <span style="color: #8c8c8c; font-size: 12px; white-space: nowrap">
          已选 {{ selectedTotal }} / {{ permissionTotal }}
        </span>
      </div>

      <div
        v-if="filteredCategories.length === 0"
        style="text-align: center; padding: 40px 0; color: #999"
      >
        {{
          permissionKeyword.trim()
            ? '无匹配的权限'
            : onlySelectedMode
              ? '该角色暂无已选权限'
              : '暂无权限数据'
        }}
      </div>

      <div
        v-else
        style="max-height: 450px; overflow-y: auto; padding-right: 8px"
      >
        <CheckboxGroup v-model:value="selectedPermissionIds">
          <Collapse v-model:active-key="activeKeys" ghost>
            <CollapsePanel
              v-for="category in filteredCategories"
              :key="category.module"
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
                  <span>{{ category.module }}</span>
                  <span style="color: #8c8c8c; font-size: 12px">
                    {{ countOf(category.module).selected }}/{{
                      countOf(category.module).total
                    }}
                  </span>
                  <span style="flex: 1" />
                  <a style="font-size: 12px" @click.stop="selectCategoryAll(category.module)">
                    全选
                  </a>
                  <a style="font-size: 12px" @click.stop="clearCategory(category.module)">
                    清空
                  </a>
                </div>
              </template>
              <template
                v-for="permission in category.main"
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
                  v-if="subPermissionsOf(category, permission.id).length > 0"
                  style="
                    margin-left: 32px;
                    margin-top: 4px;
                    display: grid;
                    grid-template-columns: repeat(2, 1fr);
                    gap: 8px;
                  "
                >
                  <Checkbox
                    v-for="sub in subPermissionsOf(category, permission.id)"
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
                v-if="category.orphanSub.length > 0"
                style="
                  margin-left: 32px;
                  margin-top: 4px;
                  display: grid;
                  grid-template-columns: repeat(2, 1fr);
                  gap: 8px;
                "
              >
                <Checkbox
                  v-for="sub in category.orphanSub"
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
    </div>
  </AModal>
</template>
