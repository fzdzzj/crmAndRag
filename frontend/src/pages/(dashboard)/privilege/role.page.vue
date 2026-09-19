<template>
  <div class="w-full">
    <Spin :spinning="isLoadingRole" tip="Loading...">
      <Table
        data-tour="role-table"
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="roleColumns"
        :data-source="roleData?.records ?? []"
        :row-key="(record: RoleRecord) => record.id!"
        :row-selection="bulkManageMode ? rowSelectionEnabled : undefined"
        :pagination="{
          current: current,
          pageSize: pageSize,
          total: roleData?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex items-center justify-between">
            <h2>角色列表</h2>
            <div class="flex flex-wrap gap-2">
              <Button @click="reset">重置筛选</Button>
              <Button v-if="bulkManageMode" danger @click="handleBulkDelete">
                批量删除
              </Button>
              <Button @click="toggleBulkManage">
                {{ bulkManageMode ? '取消批量' : '批量管理' }}
              </Button>
              <Permission :allow="['system:SYSTEM_MANAGE_ROLE']">
                <Button type="primary" data-tour="role-create" @click="openCreateModal">新增角色</Button>
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <Permission :allow="['system:SYSTEM_VIEW_ROLE']">
              <Popover content="查看" placement="top">
                <EyeOutlined
                  class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                  @click="openViewModal(record as RoleRecord)"
                />
              </Popover>
            </Permission>
            <Permission :allow="['system:SYSTEM_ASSIGN_PERMISSION']">
              <Popover content="编辑权限" placement="top">
                <EditOutlined
                  class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                  @click="openEditPermissionModal(record as RoleRecord)"
                />
              </Popover>
            </Permission>
            <Permission :allow="['system:SYSTEM_MANAGE_ROLE']">
              <Popover content="删除" placement="top">
                <DeleteOutlined
                  class="cursor-pointer text-red-500 hover:text-red-700"
                  @click="handleDelete(record as RoleRecord)"
                />
              </Popover>
            </Permission>
          </template>
        </template>
      </Table>
    </Spin>
    <CreateRoleModal ref="createRoleModalRef" />
    <EditPermissionModal ref="editPermissionModalRef" />
    <ViewRoleModal ref="viewRoleModalRef" />
  </div>
</template>

<script setup lang="ts">
import Permission from '@/components/common/Permission.vue';
import CreateRoleModal from '@/components/role/CreateRoleModal.vue';
import EditPermissionModal from '@/components/role/EditPermissionModal.vue';
import ViewRoleModal from '@/components/role/ViewRoleModal.vue';
import { Button, Spin, Popover, message, Table, Modal } from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
import {
  useQueryRolesWithURLSearchParamsAsync,
  useDeleteRole,
} from '@/hooks/useRole';
import { createRoleColumns } from '@/constants/role/constants';
import { computed, ref } from 'vue';
import type { GetRoleListResponse } from '@/api/axios';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
} from '@ant-design/icons-vue';

definePage({
  name: 'roleList',
});

type RoleRecord = NonNullable<NonNullable<GetRoleListResponse['data']>['records']>[number];
type RoleTablePagination = Parameters<
  NonNullable<TableProps<RoleRecord>['onChange']>
>[0];

const {
  data: roleData,
  isLoading: isLoadingRole,
  current,
  pageSize,
  filters,
  setFilters,
  reset,
} = useQueryRolesWithURLSearchParamsAsync();

const { mutate: deleteRole } = useDeleteRole();

const roleColumns = computed(() => {
  return createRoleColumns({
    filters,
    setFilters,
  });
});

const bulkManageMode = ref(false);
const selectedRowKeys = ref<number[]>([]);

function toggleBulkManage() {
  bulkManageMode.value = !bulkManageMode.value;
  selectedRowKeys.value = [];
}

const rowSelectionEnabled = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys as number[];
  },
}));

function handleBulkDelete() {
  Modal.confirm({
    title: '确认批量删除',
    content: `确定要删除选中的 ${selectedRowKeys.value.length} 个角色吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      selectedRowKeys.value.forEach((roleId) => {
        deleteRole(
          { roleId },
          {
            onSuccess: () => {
              if (
                roleId ===
                selectedRowKeys.value[selectedRowKeys.value.length - 1]
              ) {
                message.success('批量删除成功');
                selectedRowKeys.value = [];
              }
            },
            onError: () => {
              message.error('删除失败');
            },
          },
        );
      });
    },
  });
}

const createRoleModalRef = ref<InstanceType<typeof CreateRoleModal>>();
const editPermissionModalRef = ref<InstanceType<typeof EditPermissionModal>>();
const viewRoleModalRef = ref<InstanceType<typeof ViewRoleModal>>();

const openCreateModal = () => {
  createRoleModalRef.value?.open();
};

const openEditPermissionModal = (role: RoleRecord) => {
  editPermissionModalRef.value?.open(role);
};

const openViewModal = (role: RoleRecord) => {
  viewRoleModalRef.value?.open(role);
};

const handleDelete = (record: RoleRecord) => {
  if (record.id === undefined) {
    return;
  }
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除角色"${record.roleName || record.id}"吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      deleteRole(
        { roleId: record.id! },
        {
          onSuccess: () => {
            message.success('删除成功');
          },
          onError: () => {
            message.error('删除失败');
          },
        },
      );
    },
  });
};

const handleTableChange = (pagination: RoleTablePagination) => {
  // 分页和筛选由 URL 参数自动处理
  current.value = pagination.current ?? current.value;
  pageSize.value = pagination.pageSize ?? pageSize.value;
};
</script>
