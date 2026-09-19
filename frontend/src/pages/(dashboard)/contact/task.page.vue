<template>
  <div class="w-full">
    <Spin :spinning="isLoadingTask" tip="Loading...">
      <Table
        data-tour="task-table"
        :scroll="{ x: 'max-content' }"
        size="small"
:columns="taskColumns" :data-source="taskData?.records ?? []"
        :row-key="(record: ContactTaskListItem) => record.id!"
        :row-selection="bulkManageMode ? rowSelectionEnabled : undefined" :pagination="{
          current: current,
          pageSize: pageSize,
          total: taskData?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }" @change="handleTableChange">
        <template #title>
          <div class="flex items-center justify-between">
            <h2>联络任务列表</h2>
            <div class="flex flex-wrap gap-2">
              <Button @click="reset">重置筛选</Button>
              <Button
v-if="bulkManageMode" danger :disabled="selectedRowKeys.length === 0"
                @click="handleBulkDelete">
                批量删除
              </Button>
              <Button data-tour="task-batch" @click="toggleBulkManage">
                {{ bulkManageMode ? '取消批量' : '批量管理' }}
              </Button>
              <Permission :allow="['task:TASK_CREATE_TASK']">
                <Button type="primary" data-tour="task-create" @click="createContactTaskModalRef?.open()">
                  新增任务
                </Button>
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <Permission :allow="['task:TASK_VIEW_TASK']">
              <Popover content="查看" placement="top">
                <EyeOutlined
class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                  @click="viewContactTaskModalRef?.open(record)" />
              </Popover>
            </Permission>
            <Permission :allow="['task:TASK_UPDATE_TASK']">
              <Popover content="编辑" placement="top">
                <EditOutlined
class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                  @click="editContactTaskModalRef?.open(record)" />
              </Popover>
            </Permission>
            <Permission :allow="['task:TASK_DELETE_TASK']">
              <Popover content="删除" placement="top">
                <DeleteOutlined
class="cursor-pointer text-red-500 hover:text-red-700"
                  @click="handleDelete(record)" />
              </Popover>
            </Permission>
          </template>
        </template>
      </Table>
    </Spin>

    <ViewContactTaskModal ref="viewContactTaskModalRef" />
    <CreateContactTaskModal ref="createContactTaskModalRef" />
    <EditContactTaskModal ref="editContactTaskModalRef" />
  </div>
</template>

<script lang="ts" setup>
import type { PostContactTaskQueryResponse } from '@/api/axios';
import Permission from '@/components/common/Permission.vue';
import { Button, Spin, Popover, message, Table, Modal } from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
import {
  useDeleteContactTask,
  useBatchDeleteContactTasks,
  useQueryContactTasksWithURLSearchParamsAsync,
  type ContactTaskSearchFilters,
} from '@/hooks/useContactTask';
import { createContactTaskColumns } from '@/constants/contact/constants';
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
} from '@ant-design/icons-vue';
import ViewContactTaskModal from '@/components/contact/ViewContactTaskModal.vue';
import CreateContactTaskModal from '@/components/contact/CreateContactTaskModal.vue';
import EditContactTaskModal from '@/components/contact/EditContactTaskModal.vue';

definePage({
  name: 'contactTask',
});

type ContactTaskListItem = NonNullable<
  NonNullable<PostContactTaskQueryResponse['data']>['records']
>[number];
type ContactTaskTablePagination = Parameters<
  NonNullable<TableProps<ContactTaskListItem>['onChange']>
>[0];
type ContactTaskRowSelection = NonNullable<TableProps<ContactTaskListItem>['rowSelection']>;

const {
  data: taskData,
  isLoading: isLoadingTask,
  current,
  pageSize,
  filters,
  setFilters,
  reset,
} = useQueryContactTasksWithURLSearchParamsAsync();

const { mutate: deleteContactTask } = useDeleteContactTask();
const { mutate: batchDeleteContactTasks } = useBatchDeleteContactTasks();

const viewContactTaskModalRef = ref<InstanceType<typeof ViewContactTaskModal>>();
const createContactTaskModalRef = ref<InstanceType<typeof CreateContactTaskModal>>();
const editContactTaskModalRef = ref<InstanceType<typeof EditContactTaskModal>>();
const route = useRoute();

let openedAssistTaskId: number | undefined;
watch(
  () => route.hash,
  (hash) => {
    const matched = /^#assist-task-(\d+)$/.exec(hash);
    const taskId = matched ? Number(matched[1]) : undefined;
    if (!taskId || taskId === openedAssistTaskId) {
      return;
    }
    openedAssistTaskId = taskId;
    void nextTick(() => {
      viewContactTaskModalRef.value?.open({ id: taskId } as ContactTaskListItem);
    });
  },
  { immediate: true },
);

const taskColumns = computed(() => {
  return createContactTaskColumns({
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

const rowSelectionEnabled = computed<ContactTaskRowSelection>(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys as number[];
  },
}));

function handleBulkDelete() {
  if (selectedRowKeys.value.length === 0) {
    message.info('请选择任务');
    return;
  }
  Modal.confirm({
    title: '确认批量删除',
    content: `确定要删除选中的 ${selectedRowKeys.value.length} 个任务吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      batchDeleteContactTasks(selectedRowKeys.value, {
        onSuccess: () => {
          message.success('删除成功');
          selectedRowKeys.value = [];
        },
        onError: () => {
          message.error('删除失败');
        },
      });
    },
  });
}

function handleDelete(record: ContactTaskListItem) {
  if (record.id === undefined) {
    return;
  }
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除任务"${record.taskTitle || record.id}"吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      deleteContactTask(record.id!, {
        onSuccess: () => {
          message.success('删除成功');
        },
        onError: () => {
          message.error('删除失败');
        },
      });
    },
  });
}

const handleTableChange = (
  pagination: ContactTaskTablePagination,
  tableFilters: Record<string, unknown>,
) => {
  current.value = pagination.current ?? current.value;
  pageSize.value = pagination.pageSize ?? pageSize.value;

  const nextFilters: Partial<ContactTaskSearchFilters> = {};
  if (tableFilters.taskType) {
    const values = tableFilters.taskType as string[];
    nextFilters.taskType = values[0];
  } else {
    nextFilters.taskType = undefined;
  }
  if (tableFilters.taskStatus) {
    const values = tableFilters.taskStatus as number[];
    nextFilters.taskStatus = Number(values[0]);
  } else {
    nextFilters.taskStatus = undefined;
  }
  if (tableFilters.priority) {
    const values = tableFilters.priority as number[];
    nextFilters.priority = Number(values[0]);
  } else {
    nextFilters.priority = undefined;
  }
  setFilters(nextFilters);
};
</script>
