<template>
  <div class="w-full">
    <Spin :spinning="isLoadingActivity" tip="Loading...">
      <Table
        data-tour="activity-table"
        :scroll="{ x: 'max-content' }"
        size="small"
:columns="activityColumns" :data-source="activityData?.records ?? []"
        :row-key="(record: ActivityListItem) => record.id!"
        :row-selection="bulkManageMode ? rowSelectionEnabled : undefined" :pagination="{
          current: current,
          pageSize: pageSize,
          total: activityData?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }" @change="handleTableChange">
        <template #title>
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <h2>业务活动列表</h2>
              <Tooltip title="业务活动主要记录关键拜访及技术交流等，作为进入下一阶段的关键动作">
                <QuestionCircleOutlined class="cursor-help text-gray-400" />
              </Tooltip>
            </div>
            <div class="flex flex-wrap gap-2">
              <Button @click="reset">重置筛选</Button>
              <Button
v-if="bulkManageMode" danger :disabled="selectedRowKeys.length === 0"
                @click="handleBulkDelete">
                批量删除
              </Button>
              <Button @click="toggleBulkManage">
                {{ bulkManageMode ? '取消批量' : '批量管理' }}
              </Button>
              <Permission :allow="['sales:SALES_CREATE_BUSINESS_ACTIVITY']">
                <Button type="primary" data-tour="activity-create" @click="openCreateModal">新增活动</Button>
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <Permission :allow="['sales:SALES_VIEW_BUSINESS_ACTIVITY']">
              <Popover content="查看" placement="top">
                <EyeOutlined
class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                  @click="openViewModal(record)" />
              </Popover>
            </Permission>
            <Permission :allow="['sales:SALES_UPDATE_BUSINESS_ACTIVITY']">
              <Popover content="编辑" placement="top">
                <EditOutlined
class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                  @click="openEditModal(record)" />
              </Popover>
            </Permission>
            <Permission :allow="['sales:SALES_UPDATE_BUSINESS_ACTIVITY']">
              <Popover content="上传附件" placement="top">
                <UploadOutlined
class="mr-2 cursor-pointer text-orange-500 hover:text-orange-700"
                  @click="openUploadAttachmentModal(record)" />
              </Popover>
            </Permission>
            <Permission :allow="['sales:SALES_DELETE_BUSINESS_ACTIVITY']">
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

    <ViewActivityModal ref="viewActivityModalRef" />
    <EditActivityModal ref="editActivityModalRef" />
    <CreateActivityModal ref="createActivityModalRef" />
    <UploadActivityAttachmentModal ref="uploadAttachmentModalRef" />
  </div>
</template>

<script lang="ts" setup>
import type { PostBusinessActivityQueryResponse } from '@/api/axios';
import Permission from '@/components/common/Permission.vue';
import { Button, Spin, Popover, Tooltip, message, Table, Modal } from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
import {
  useDeleteActivities,
  useQueryActivityDetail,
  useQueryActivitiesWithURLSearchParamsAsync,
  type ActivitySearchFilters,
} from '@/hooks/useActivity';
import { createActivityColumns } from '@/constants/activity/constants';
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  QuestionCircleOutlined,
  UploadOutlined,
} from '@ant-design/icons-vue';
import EditActivityModal from '@/components/activity/EditActivityModal.vue';
import CreateActivityModal from '@/components/activity/CreateActivityModal.vue';
import ViewActivityModal from '@/components/activity/ViewActivityModal.vue';
import UploadActivityAttachmentModal from '@/components/activity/UploadActivityAttachmentModal.vue';

definePage({
  name: 'businessActivity',
});

type ActivityListItem = NonNullable<
  NonNullable<PostBusinessActivityQueryResponse['data']>['records']
>[number];
type ActivityTablePagination = Parameters<
  NonNullable<TableProps<ActivityListItem>['onChange']>
>[0];
type ActivityRowSelection = NonNullable<TableProps<ActivityListItem>['rowSelection']>;

const {
  data: activityData,
  isLoading: isLoadingActivity,
  current,
  pageSize,
  filters,
  setFilters,
  reset,
} = useQueryActivitiesWithURLSearchParamsAsync();
const { mutate: deleteActivities } = useDeleteActivities();
const { mutateAsync: getActivityDetail } = useQueryActivityDetail();
const route = useRoute();

const activityColumns = computed(() => {
  return createActivityColumns({
    filters,
    setFilters,
  });
});

const editActivityModalRef = ref<InstanceType<typeof EditActivityModal>>();
const createActivityModalRef = ref<InstanceType<typeof CreateActivityModal>>();
const viewActivityModalRef = ref<InstanceType<typeof ViewActivityModal>>();
const uploadAttachmentModalRef = ref<InstanceType<typeof UploadActivityAttachmentModal>>();

let openedAssistActivityId: number | undefined;
watch(
  () => route.hash,
  async (hash) => {
    const matched = /^#assist-activity-(\d+)$/.exec(hash);
    const activityId = matched ? Number(matched[1]) : undefined;
    if (!activityId || activityId === openedAssistActivityId) {
      return;
    }
    openedAssistActivityId = activityId;
    try {
      const detail = await getActivityDetail(activityId);
      if (detail) {
        viewActivityModalRef.value?.open(detail);
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '无法打开业务活动详情');
    }
  },
  { immediate: true },
);

const openViewModal = (record: ActivityListItem) => {
  viewActivityModalRef.value?.open(record);
};

const openEditModal = (record: ActivityListItem) => {
  editActivityModalRef.value?.open(record);
};

const openCreateModal = () => {
  createActivityModalRef.value?.open();
};

const openUploadAttachmentModal = (record: ActivityListItem) => {
  uploadAttachmentModalRef.value?.open(record);
};

const bulkManageMode = ref(false);
const selectedRowKeys = ref<number[]>([]);

function toggleBulkManage() {
  bulkManageMode.value = !bulkManageMode.value;
  selectedRowKeys.value = [];
}

const rowSelectionEnabled = computed<ActivityRowSelection>(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys as number[];
  },
}));

function handleBulkDelete() {
  if (selectedRowKeys.value.length === 0) {
    message.info('请选择活动');
    return;
  }
  Modal.confirm({
    title: '确认批量删除',
    content: `确定要删除选中的 ${selectedRowKeys.value.length} 个活动吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      deleteActivities(selectedRowKeys.value, {
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

function handleDelete(record: ActivityListItem) {
  if (record.id === undefined) {
    return;
  }
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除活动"${record.activityTitle || record.id}"吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      deleteActivities([record.id!], {
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
  pagination: ActivityTablePagination,
  tableFilters: Record<string, unknown>,
) => {
  current.value = pagination.current ?? current.value;
  pageSize.value = pagination.pageSize ?? pageSize.value;

  const nextFilters: Partial<ActivitySearchFilters> = {};
  if (tableFilters.activityType) {
    const activityTypeArray = tableFilters.activityType as string[];
    nextFilters.activityType = activityTypeArray[0];
  } else {
    nextFilters.activityType = undefined;
  }
  setFilters(nextFilters);
};
</script>
