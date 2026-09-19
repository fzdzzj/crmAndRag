<template>
  <div class="w-full">
    <div class="mb-4">
      <Tabs v-model:active-key="activeTab" data-tour="approval-tabs" @change="handleTabChange">
        <Tabs.TabPane key="0" tab="待审批" />
        <Tabs.TabPane key="1" tab="所有审批记录" />
      </Tabs>
    </div>

    <Spin :spinning="loading" tip="加载中...">
      <Table
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="columns"
        :data-source="dataSource"
        :row-key="(record: SalesStageApprovalVO) => record.id!"
        :row-class-name="rowClassName"
        :pagination="{
          current: pagination.current,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex items-center justify-between">
            <h2>阶段审批</h2>
            <Button type="primary" @click="fetchData">刷新</Button>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'approvalStatus'">
            <Tag :color="getStatusColor(record.approvalStatus)">
              {{ getStatusText(record.approvalStatus) }}
            </Tag>
          </template>
          <template v-if="column.key === 'viewDetail'">
            <Space>
              <a class="text-blue-500 hover:text-blue-700" @click="handleViewDetail(record)">详情</a>
              <a
                class="text-blue-500 hover:text-blue-700"
                @click="
                  record.id &&
                  downloadSaleStageAttachment({ approvalIds: [record.id] })
                "
                >下载附件</a
              >
            </Space>
          </template>
          <template v-if="column.key === 'actions' && activeTab === '0'">
            <Space>
              <Button data-tour="approval-approve" @click="handleApprove(record, 1)">同意</Button>
              <Button danger @click="handleApprove(record, 2)">拒绝</Button>
            </Space>
          </template>
          <template v-if="column.key === 'approvalOpinionColumn'">
            {{ record.approvalOpinion }}
          </template>
          <template v-if="column.key === 'attachments'">
            <Space v-if="attachmentMap[record.id]?.length">
              <Tooltip
                v-for="(att, idx) in attachmentMap[record.id]"
                :key="idx"
                :title="att.fileName"
              >
                <a
                  class="text-blue-500 hover:text-blue-700 cursor-pointer text-xs"
                  @click="
                    downloadSaleStageAttachment({
                      approvalIds: [record.id],
                    })
                  "
                >
                  <PaperClipOutlined class="mr-1" />
                  {{ att.fileName }}
                </a>
              </Tooltip>
            </Space>
            <span v-else class="text-gray-400">-</span>
          </template>
        </template>
      </Table>
    </Spin>

    <Modal
      v-model:open="approvalModalVisible"
      title="审批处理"
      @ok="submitApproval"
    >
      <Form layout="vertical">
        <Form.Item label="审批意见">
          <Textarea v-model:value="approvalOpinion" :rows="4" />
        </Form.Item>
      </Form>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive, computed, watch, nextTick, h } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useListStageApprovals, useApproveStage, useGetStageAttachments } from '@/hooks/useSale';
import type {
  GetSalesStageAttachmentResponse,
  GetSalesStageData,
  GetSalesStageResponse,
  PutSalesStageData,
} from '@/api/axios';
import {
  Button,
  Space,
  Modal,
  Form,
  Tabs,
  Tag,
  Textarea,
  Spin,
  Table,
  Tooltip,
} from 'ant-design-vue';
import { useDownloadSaleStageAttachment } from '@/hooks/useSaleStage';
import { PaperClipOutlined } from '@ant-design/icons-vue';
import AssistUserTags from '@/components/assist/AssistUserTags.vue';

definePage({
  name: 'stageApproval',
});

const router = useRouter();
const route = useRoute();

type SalesStageApprovalVO = NonNullable<
  NonNullable<GetSalesStageResponse['data']>['records']
>[number];
type ApprovalAttachmentVO = NonNullable<GetSalesStageAttachmentResponse['data']>[number];
type StageApprovalQuery = NonNullable<GetSalesStageData['query']>;
type ApproveStageBody = NonNullable<PutSalesStageData['body']>;

const activeTab = ref('0');
const loading = ref(false);
const dataSource = ref<SalesStageApprovalVO[]>([]);
const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
});

const approvalModalVisible = ref(false);
const currentRecord = ref<SalesStageApprovalVO>();
const currentAction = ref<number>(1);
const approvalOpinion = ref('');
const submitting = ref(false);

// 从订单/合同详情跳转过来时，需要定位的目标审批
const targetApprovalId = computed(() => {
  const id = Number(route.query.approvalId);
  return Number.isFinite(id) && id > 0 ? id : undefined;
});
const locatedApproval = ref(false);
let initialized = false;

const { mutateAsync: listApprovals } = useListStageApprovals();
const { mutateAsync: approveStage } = useApproveStage();
const { mutateAsync: downloadSaleStageAttachment } =
  useDownloadSaleStageAttachment();
const { mutateAsync: getStageAttachments } = useGetStageAttachments();

// 附件映射：approvalId -> AttachmentVO[]
const attachmentMap = ref<Record<number, ApprovalAttachmentVO[]>>({});

const getStatusColor = (status?: number) => {
  switch (status) {
    case 0:
      return 'orange';
    case 1:
      return 'green';
    case 2:
      return 'red';
    case 3:
      return 'purple';
    default:
      return 'default';
  }
};

const getStatusText = (status: number) => {
  const map: Record<number, string> = { 0: '待审批', 1: '已同意', 2: '已拒绝', 3: '退回修改' };
  return map[status] || '未知';
};

const handleViewDetail = (record: SalesStageApprovalVO) => {
  if (record.opportunityId) {
    void router.push(`/sale/detail/${record.opportunityId}`);
  }
};

const rowClassName = (record: SalesStageApprovalVO) =>
  targetApprovalId.value !== undefined && record.id === targetApprovalId.value
    ? 'approval-target-row'
    : '';

const locateApproval = async () => {
  const id = targetApprovalId.value;
  if (id === undefined || locatedApproval.value) {
    return;
  }
  const found = dataSource.value.some((record) => record.id === id);
  if (!found && pagination.total > pagination.pageSize) {
    // 目标不在当前页，拉全量后再定位
    pagination.pageSize = pagination.total;
    return;
  }
  locatedApproval.value = true;
  if (found) {
    await nextTick();
    document
      .querySelector('.approval-target-row')
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
};

const handleApprove = (record: SalesStageApprovalVO, action: number) => {
  currentRecord.value = record;
  currentAction.value = action;
  approvalOpinion.value = action === 1 ? '同意' : '';
  approvalModalVisible.value = true;
};

const baseColumns = computed(() => [
  { title: '销售机会', dataIndex: 'opportunityName', key: 'opportunityName' },
  {
    title: '当前阶段',
    dataIndex: 'currentStage',
    key: 'currentStage',
    customRender: ({ text }: { text?: string }) =>
      h(Tag, { color: 'blue' }, () => text || '-'),
  },
  {
    title: '目标阶段',
    dataIndex: 'targetStage',
    key: 'targetStage',
    customRender: ({ text }: { text?: string }) =>
      h(Tag, { color: 'purple' }, () => text || '-'),
  },
  { title: '审批备注', dataIndex: 'message', key: 'message' },
  { title: '申请时间', dataIndex: 'applyTime', key: 'applyTime' },
  { title: '审批人', dataIndex: 'approverName', key: 'approverName' },
  { title: '状态', dataIndex: 'approvalStatus', key: 'approvalStatus' },
  {
    title: '协助人',
    dataIndex: 'assistUsers',
    key: 'assistUsers',
    customRender: ({
      text,
    }: {
      text?: {
        assistUserName?: string;
        assistUserDeptName?: string;
        assistStatus?: number;
      }[];
    }) => {
      return h(AssistUserTags, { users: text });
    },
  },
  { title: '附件', key: 'attachments' },
  { title: '操作', key: 'viewDetail' },
]);

const columns = computed(() => {
  if (activeTab.value === '0') {
    return [
      ...baseColumns.value,
      { title: '审批操作', key: 'actions' },
    ];
  }
  return [
    ...baseColumns.value,
    { title: '审批意见', key: 'approvalOpinionColumn' },
  ];
});

const fetchData = async () => {
  loading.value = true;
  try {
    const params: StageApprovalQuery = {
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      'dto.approvalStatus': activeTab.value === '0' ? [0] : undefined,
    };

    const pageData = await listApprovals(params);
    if (pageData) {
      dataSource.value = pageData.records || [];
      pagination.total = pageData.total || 0;
      void loadAttachments();
      void locateApproval();
    } else {
      dataSource.value = [];
      pagination.total = 0;
    }
  } catch (e) {
    console.error(e);
  } finally {
    loading.value = false;
  }
};

const loadAttachments = async () => {
  const approvalIds = dataSource.value
    .map((r) => r.id)
    .filter((id): id is number => id !== undefined);
  if (approvalIds.length === 0) return;
  try {
    const attachments = (await getStageAttachments(approvalIds)) || [];
    const map: Record<number, ApprovalAttachmentVO[]> = {};
    attachments.forEach((att) => {
      const key = att.andId ?? 0;
      if (!map[key]) map[key] = [];
      map[key].push(att);
    });
    attachmentMap.value = map;
  } catch {
    attachmentMap.value = {};
  }
};

watch([() => pagination.current, () => pagination.pageSize], () => {
  void fetchData();
});

// 从详情页跳转定位审批时，切到“所有审批记录”再定位
watch(
  targetApprovalId,
  (id) => {
    if (id !== undefined) {
      activeTab.value = '1';
      if (initialized) {
        pagination.current = 1;
        void fetchData();
      }
    }
  },
  { immediate: true },
);

const handleTabChange = () => {
  pagination.current = 1;
  void fetchData();
};

const handleTableChange = (newPagination: { current?: number; pageSize?: number }) => {
  pagination.current = newPagination.current ?? pagination.current;
  pagination.pageSize = newPagination.pageSize ?? pagination.pageSize;
};

const submitApproval = async () => {
  if (!currentRecord.value) return;

  submitting.value = true;
  try {
    const data: ApproveStageBody = {
      id: currentRecord.value.id,
      approvalStatus: currentAction.value,
      approvalOpinion: approvalOpinion.value,
    };

    await approveStage(data);
    approvalModalVisible.value = false;
    await fetchData();
  } catch (e) {
    console.error(e);
  } finally {
    submitting.value = false;
  }
};

onMounted(() => {
  initialized = true;
  void fetchData();
});
</script>

<style scoped>
:deep(.approval-target-row) {
  background-color: #e6f4ff !important;
}
</style>
