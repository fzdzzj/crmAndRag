<template>
  <div class="w-full">
    <div>
      <Spin :spinning="isLoadingUser || isLoadingRole" tip="Loading...">
        <Table
          :scroll="{ x: 'max-content' }"
          size="small"
          :columns="userColumns"
          :data-source="userData?.records ?? []"
          :row-key="(record: UserRecord) => record.id!"
          :row-selection="bulkManageMode ? rowSelectionEnabled : undefined"
          :pagination="{
            current: current,
            pageSize: pageSize,
            total: userData?.total ?? 0,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (t: number) => `共 ${t} 条`,
          }"
          @change="handleTableChange"
        >
          <template #title>
            <div class="flex items-center justify-between">
              <h2>用户列表</h2>
              <div class="flex flex-wrap gap-2">
                <Button @click="reset">重置筛选</Button>
                <Button v-if="bulkManageMode" danger @click="handleBulkDelete">
                  批量删除
                </Button>
                <Button @click="toggleBulkManage">
                  {{ bulkManageMode ? '取消批量' : '批量管理' }}
                </Button>
                <Permission :allow="['system:SYSTEM_CREATE_USER']">
                  <Button type="primary" data-tour="user-create" @click="openCreateModal"
                    >新增用户</Button
                  >
                </Permission>
                <Button data-tour="user-handover" @click="openHandoverRecordsModal">
                  <HistoryOutlined class="mr-1" />
                  交接记录
                </Button>
              </div>
            </div>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <Permission :allow="['system:SYSTEM_VIEW_USER']">
                <Popover content="查看" placement="top">
                  <EyeOutlined
                    class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                    @click="openViewModal(record as UserRecord)"
                  />
                </Popover>
              </Permission>
              <Permission :allow="['system:SYSTEM_UPDATE_USER']">
                <Popover content="编辑" placement="top">
                  <EditOutlined
                    class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                    @click="openEditModal(record as UserRecord)"
                  />
                </Popover>
              </Permission>
              <Permission :allow="['system:SYSTEM_UPDATE_USER']">
                <Popover content="交接" placement="top">
                  <SwapOutlined
                    class="mr-2 cursor-pointer text-amber-500 hover:text-amber-700"
                    @click="openHandoverModal(record as UserRecord)"
                  />
                </Popover>
              </Permission>
              <Popover content="删除" placement="top">
                <DeleteOutlined
                  class="cursor-pointer text-red-500 hover:text-red-700"
                  @click="handleDelete(record as UserRecord)"
                />
              </Popover>
            </template>
          </template>
        </Table>
      </Spin>
    </div>
    <ViewUserModal ref="viewUserModalRef" :role-map="roleMap" />
    <EditUserModal ref="editUserModalRef" />
    <CreateUserModal ref="createUserModalRef" />
    <UserHandoverModal ref="handoverModalRef" />
    <HandoverRecordsModal ref="handoverRecordsModalRef" />
  </div>
</template>

<script setup lang="ts">
import Permission from '@/components/common/Permission.vue';
import EditUserModal from '@/components/user/EditUserModal.vue';
import CreateUserModal from '@/components/user/CreateUserModal.vue';
import UserHandoverModal from '@/components/user/UserHandoverModal.vue';
import HandoverRecordsModal from '@/components/user/HandoverRecordsModal.vue';
import ViewUserModal from '@/components/user/ViewUserModal.vue';
import { Button, Spin, Popover, message, Table, Modal } from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
import {
  useDeleteUsers,
  useQueryRoles,
  useQueryUsersWithURLSearchParamsAsync,
  type UserSearchFilters,
} from '@/hooks/useUser';
import { createUserColumns, statusMap } from '@/constants/user/constants';
import { computed, ref } from 'vue';
import type { PostUserFindResponse } from '@/api/axios';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  HistoryOutlined,
  SwapOutlined,
} from '@ant-design/icons-vue';

definePage({
  name: 'userList',
});

type UserRecord = NonNullable<
  NonNullable<PostUserFindResponse['data']>['records']
>[number];
type UserTablePagination = Parameters<
  NonNullable<TableProps<UserRecord>['onChange']>
>[0];
type UserTableFilters = Parameters<
  NonNullable<TableProps<UserRecord>['onChange']>
>[1];

const {
  data: userData,
  isLoading: isLoadingUser,
  current,
  pageSize,
  filters,
  setFilters,
  reset,
} = useQueryUsersWithURLSearchParamsAsync();
const { mutate: deleteUsers } = useDeleteUsers();
const { data: roleData, isLoading: isLoadingRole } = useQueryRoles({
  all: true,
});
const roleMap = computed(() =>
  roleData.value?.records?.reduce(
    (acc, role) => {
      acc[role.id ?? 0] = role.roleName ?? '';
      return acc;
    },
    {} as Record<number, string>,
  ),
);

const userColumns = computed(() => {
  return createUserColumns({
    roleMap: roleMap.value ?? {},
    statusMap,
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
    content: `确定要删除选中的 ${selectedRowKeys.value.length} 个用户吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      deleteUsers(selectedRowKeys.value, {
        onSuccess: () => {
          message.success('批量删除成功');
          selectedRowKeys.value = [];
        },
        onError: () => {
          message.error('删除失败');
        },
      });
    },
  });
}

const viewUserModalRef = ref<InstanceType<typeof ViewUserModal>>();
const editUserModalRef = ref<InstanceType<typeof EditUserModal>>();
const createUserModalRef = ref<InstanceType<typeof CreateUserModal>>();
const handoverModalRef = ref<InstanceType<typeof UserHandoverModal>>();
const handoverRecordsModalRef =
  ref<InstanceType<typeof HandoverRecordsModal>>();

const openViewModal = (record: UserRecord) => {
  viewUserModalRef.value?.open(record);
};

const openEditModal = (record: UserRecord) => {
  editUserModalRef.value?.open(record);
};

const openCreateModal = () => {
  createUserModalRef.value?.open();
};

const openHandoverModal = (record: UserRecord) => {
  handoverModalRef.value?.open(record);
};

const openHandoverRecordsModal = () => {
  handoverRecordsModalRef.value?.open();
};

const handleDelete = (record: UserRecord) => {
  if (record.id === undefined) {
    return;
  }
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除用户"${record.realName || record.email || record.id}"吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      deleteUsers([record.id!], {
        onSuccess: () => {
          message.success('删除成功');
        },
        onError: () => {
          message.error('删除失败');
        },
      });
    },
  });
};

const handleTableChange = (
  pagination: UserTablePagination,
  tableFilters: UserTableFilters,
) => {
  current.value = pagination.current ?? current.value;
  pageSize.value = pagination.pageSize ?? pageSize.value;

  const nextFilters: Partial<UserSearchFilters> = {};
  if (tableFilters.roleId) {
    nextFilters.roleId = tableFilters.roleId as number[];
  } else {
    nextFilters.roleId = undefined;
  }

  if (tableFilters.status) {
    nextFilters.status = tableFilters.status as number[];
  } else {
    nextFilters.status = undefined;
  }

  setFilters(nextFilters);
};
</script>
