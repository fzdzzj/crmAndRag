<template>
  <div class="w-full">
    <Spin :spinning="isLoading" tip="Loading...">
      <Table
        data-tour="assist-app-table"
        :columns="columns"
        :data-source="groupedRecords"
        :row-key="(record: ApplicationRow) => record.isGroup ? record.key : record.id!"
        :scroll="{ x: 1000 }"
        :pagination="{
          current: pageNum,
          pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 位协助人`,
          onChange: (page: number, size: number) => {
            onTableChange(page, size);
          },
        }"
      >
        <template #title>
          <div class="flex flex-wrap items-center justify-end gap-2">
            <ASelect
              v-model:value="assistStatus"
              placeholder="状态筛选"
              allow-clear
              style="width: 150px"
            >
              <ASelectOption :value="0">待协助</ASelectOption>
              <ASelectOption :value="1">已协助</ASelectOption>
              <ASelectOption :value="2">已驳回</ASelectOption>
              <ASelectOption :value="3">已拒绝</ASelectOption>
              <ASelectOption :value="4">已取消</ASelectOption>
            </ASelect>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'modelName'">
            <ATag>{{ modelNameMap[record.modelName ?? ''] ?? record.modelName }}</ATag>
          </template>
          <template v-else-if="column.key === 'recordTitle'">
            <template v-if="record.isGroup">
              <div>{{ record.recordTitle || '-' }}</div>
              <div class="mt-1 text-xs text-gray-400">展开查看每位协助人的目的、要求与处理结果</div>
            </template>
            <template v-else>{{ record.recordTitle || '-' }}</template>
          </template>
          <template v-else-if="column.key === 'applyPurpose'">
            <template v-if="record.isGroup">共 {{ record.children.length }} 位协助人</template>
            <template v-else>{{ record.applyPurpose || '-' }}</template>
          </template>
          <template v-else-if="column.key === 'assistUserName'">
            <template v-if="record.isGroup">展开后查看</template>
            <template v-else>
              {{ record.assistUserName || '-' }}
              <span v-if="record.assistUserDeptName" class="text-gray-400">
                （{{ record.assistUserDeptName }}）
              </span>
            </template>
          </template>
          <template v-else-if="column.key === 'assistStatus'">
            <template v-if="record.isGroup">{{ getStatusSummary(record.children) }}</template>
            <ATag v-else :color="statusColorMap[record.assistStatus ?? 0]">
              {{ statusMap[record.assistStatus ?? 0] ?? '未知' }}
            </ATag>
          </template>
          <template v-else-if="column.key === 'action'">
            <template v-if="record.isGroup">
              <AButton
                v-if="hasAppendableChild(record)"
                type="link"
                size="small"
                @click="openAppend(firstAppendableChild(record))"
              >
                追加协助
              </AButton>
            </template>
            <template v-if="!record.isGroup">
              <AButton type="link" size="small" @click="openDetail(record)">
                详情
              </AButton>
              <AButton
                v-if="record.assistStatus === 2"
                type="link"
                size="small"
                @click="openReapply(record)"
              >
                重新申请
              </AButton>
              <AButton
                v-if="record.assistStatus !== 4"
                type="link"
                size="small"
                @click="openAppend(record)"
              >
                追加协助
              </AButton>
            </template>
          </template>
        </template>
        <template #expandedRowRender="{ record }">
          <div v-if="record.isGroup" class="space-y-2 rounded bg-gray-50 p-3">
            <div
              v-for="child in record.children"
              :key="child.id"
              class="flex flex-wrap items-center justify-between gap-2 rounded border bg-white p-2"
            >
              <div class="min-w-0 text-sm">
                <div>
                  <span class="font-medium">{{ child.assistUserName || '未指定协助人' }}</span>
                  <span v-if="child.assistUserDeptName" class="ml-1 text-gray-400">
                    （{{ child.assistUserDeptName }}）
                  </span>
                  <ATag class="ml-2" :color="statusColorMap[child.assistStatus ?? 0]">
                    {{ statusMap[child.assistStatus ?? 0] ?? '未知' }}
                  </ATag>
                </div>
                <div class="mt-1 text-gray-600">
                  目的：{{ child.applyPurpose || '-' }}；要求：{{ child.applyRequirement || '-' }}
                </div>
              </div>
              <div class="flex shrink-0 gap-1">
                <AButton type="link" size="small" @click="openDetail(child)">详情</AButton>
                <AButton
                  v-if="child.assistStatus === 2"
                  type="link"
                  size="small"
                  @click="openReapply(child)"
                >
                  重新申请
                </AButton>
                <AButton
                  v-if="child.assistStatus !== 4"
                  type="link"
                  size="small"
                  @click="openAppend(child)"
                >
                  追加协助
                </AButton>
              </div>
            </div>
          </div>
        </template>
      </Table>
    </Spin>

    <AModal
      v-model:open="detailOpen"
      title="申请详情"
      :footer="null"
      width="640px"
      :body-style="{ maxHeight: '70vh', overflowY: 'auto' }"
    >
      <Spin :spinning="detailLoading">
      <ATabs v-if="detailRecord" default-active-key="overview">
        <ATabPane key="overview" tab="概览">
        <ADescriptions bordered :column="1" size="small">
        <ADescriptionsItem label="来源">
          {{ modelNameMap[detailRecord.modelName ?? ''] ?? detailRecord.modelName }}
        </ADescriptionsItem>
        <ADescriptionsItem label="业务标题">
          {{ detailRecord.recordTitle || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="业务内容">
          {{ detailRecord.recordContent || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="协作目的">
          {{ detailRecord.applyPurpose || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="协作要求">
          {{ detailRecord.applyRequirement || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="协助人">
          {{ detailRecord.assistUserName || '-' }}
          <span v-if="detailRecord.assistUserDeptName" class="text-gray-400">
            （{{ detailRecord.assistUserDeptName }}）
          </span>
        </ADescriptionsItem>
        <ADescriptionsItem label="申请时间">
          {{ detailRecord.createTime || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="状态">
          {{ statusMap[detailRecord.assistStatus ?? 0] ?? '未知' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="协助内容">
          {{ detailRecord.assistContent || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="驳回/拒绝理由">
          {{ detailRecord.rejectReason || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="处理时间">
          {{ detailRecord.assistTime || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem v-if="detailRecord.parentId" label="来源申请ID">
          {{ detailRecord.parentId }}
        </ADescriptionsItem>
        </ADescriptions>
        </ATabPane>
        <ATabPane key="related" tab="关联业务">
        <AssistRecordLinks
        :assist-id="detailRecord?.id"
        :model-name="detailRecord?.modelName"
        :record-id="detailRecord?.recordId"
        :opportunity-id="detailRecord?.opportunityId"
        :opportunity-name="detailRecord?.opportunityName"
        :company-id="detailRecord?.companyId"
        :company-name="detailRecord?.companyName"
        :contact-id="detailRecord?.contactId"
        :contact-name="detailRecord?.contactName"
        :fallback="detailRecord"
        :snapshot="detailSnapshot"
        :frozen="detailRecord?.assistStatus != null && Number(detailRecord.assistStatus) !== 0"
        />
        <AssistOpportunitySnapshot
        :model-name="detailRecord?.modelName"
        :snapshot="detailSnapshot"
        :frozen="detailRecord?.assistStatus != null && Number(detailRecord.assistStatus) !== 0"
        />
        </ATabPane>
        <ATabPane key="messages" tab="过程对话">
          <AssistMessagePanel :assist-id="detailRecord?.id" :assist-status="detailRecord?.assistStatus" />
        </ATabPane>
        <ATabPane key="attachments" tab="附件">
        <div class="mt-1 border-t pt-3">
        <h3 class="mb-2 text-sm font-medium">协助交付物</h3>
        <p v-if="!deliveryAttachments?.length" class="text-sm text-gray-400">暂无附件</p>
        <div v-else class="flex flex-wrap gap-x-3 gap-y-1 text-sm">
          <button
            v-for="attachment in deliveryAttachments"
            :key="attachment.id"
            type="button"
            class="cursor-pointer border-0 bg-transparent p-0 text-blue-600 hover:text-blue-800"
            @click="downloadAttachmentByUrl(attachment.downloadUrl, attachment.fileName || '附件')"
          >
            {{ attachment.fileName || '附件' }}
          </button>
        </div>
        <div v-if="detailRecord?.assistStatus === 0" class="mt-3">
          <p class="mb-2 text-xs text-gray-500">待协助期间，申请人也可以补充多个协助附件。</p>
          <div class="flex flex-wrap items-center gap-2">
            <AUpload
              :file-list="applicationFiles"
              :custom-request="selectApplicationFile"
              :multiple="true"
              @remove="removeApplicationFile"
            >
              <AButton :disabled="uploadingApplication">选择附件</AButton>
            </AUpload>
            <AButton
              type="primary"
              :loading="uploadingApplication"
              :disabled="applicationFiles.length === 0"
              @click="uploadApplicationAttachments"
            >上传附件</AButton>
          </div>
        </div>
        </div>
        </ATabPane>
      </ATabs>
      </Spin>
    </AModal>

    <AModal
      v-model:open="reapplyOpen"
      title="重新申请（驳回后再次发起）"
      :confirm-loading="reapplying"
      ok-text="提交申请"
      cancel-text="取消"
      @ok="submitReapply"
    >
      <ADescriptions v-if="current" :column="1" size="small">
        <ADescriptionsItem label="来源">
          {{ modelNameMap[current.modelName ?? ''] ?? current.modelName }}
        </ADescriptionsItem>
        <ADescriptionsItem label="业务标题">
          {{ current.recordTitle || '-' }}
        </ADescriptionsItem>
      </ADescriptions>
      <div class="mt-3">
        <div class="mb-3 text-sm text-gray-600">
          协助人：{{ current?.assistUserName || '-' }}
          <span v-if="current?.assistUserDeptName">（{{ current.assistUserDeptName }}）</span>
        </div>
        <AInput
          v-model:value="reapplyForm.applyPurpose"
          class="mb-3"
          placeholder="协作目的：如补充材料、填写信息、跟进客户"
        />
        <ATextarea
          v-model:value="reapplyForm.applyRequirement"
          :rows="3"
          placeholder="协作要求：需要对方具体做什么"
        />
      </div>
    </AModal>

    <AModal
      v-model:open="appendOpen"
      title="追加协助"
      :confirm-loading="appending"
      ok-text="提交追加"
      cancel-text="取消"
      width="640px"
      :body-style="{ maxHeight: '70vh', overflowY: 'auto' }"
      @ok="submitAppend"
    >
      <p class="mb-3 text-sm text-gray-500">
        原申请记录和处理历史会保留；这里只新增其他协助人，每位协助人可填写独立的目的与要求。
      </p>
      <AssistApplyEditor v-model:value="appendRows" />
    </AModal>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  Button as AButton,
  Modal as AModal,
  Select as ASelect,
  SelectOption as ASelectOption,
  Spin,
  Table,
  Tag as ATag,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Input as AInput,
  Textarea as ATextarea,
  Upload as AUpload,
  Tabs as ATabs,
  TabPane as ATabPane,
  message,
} from 'ant-design-vue';
import type { TableColumnType, UploadFile, UploadProps } from 'ant-design-vue';
import AssistOpportunitySnapshot from '@/components/assist/AssistOpportunitySnapshot.vue';
import AssistRecordLinks from '@/components/assist/AssistRecordLinks.vue';
import {
  useQueryMyApplications,
  useQueryAssistAttachments,
  useQueryAssistDetail,
  useReapplyAssist,
  useAppendAssist,
  useUploadAssistAttachment,
  type AssistApplyItem,
  type AssistApplicationRecord,
} from '@/hooks/useAssist';
import AssistApplyEditor from '@/components/assist/AssistApplyEditor.vue';
import { downloadAttachmentByUrl } from '@/utils/attachment';
import AssistMessagePanel from '@/components/assist/AssistMessagePanel.vue';

definePage({
  name: 'assistApplications',
});

const modelNameMap: Record<string, string> = {
  sales_stage_approval: '订单推进',
  business_activity: '业务活动',
  contact_task: '联络任务',
};

const statusMap: Record<number, string> = {
  0: '待协助',
  1: '已协助',
  2: '已驳回',
  3: '已拒绝',
  4: '已取消',
};

const statusColorMap: Record<number, string> = {
  0: 'warning',
  1: 'success',
  2: 'error',
  3: 'default',
  4: 'default',
};

type ApplicationGroup = {
  key: string;
  isGroup: true;
  modelName?: string;
  recordId?: number;
  recordTitle?: string;
  recordTime?: string;
  createTime?: string;
  children: ApplicationMember[];
};

type ApplicationMember = AssistApplicationRecord & { isGroup: false };
type ApplicationRow = ApplicationMember | ApplicationGroup;

const { data, isLoading, pageNum, pageSize, assistStatus } = useQueryMyApplications();
const { mutate: reapply, isPending: reapplying } = useReapplyAssist();
const { mutateAsync: appendAssist, isPending: appending } = useAppendAssist();
const { mutateAsync: uploadAssistAttachment, isPending: uploadingApplication } =
  useUploadAssistAttachment();

watch(assistStatus, () => {
  pageNum.value = 1;
});

const groupedRecords = computed<ApplicationGroup[]>(() => {
  const groups = new Map<string, ApplicationGroup>();
  for (const record of data.value?.records ?? []) {
    const member: ApplicationMember = { ...record, isGroup: false };
    const key = `${member.modelName ?? ''}-${member.recordId ?? ''}`;
    const group = groups.get(key) ?? {
      key,
      isGroup: true,
      modelName: member.modelName,
      recordId: member.recordId,
      recordTitle: member.recordTitle,
      recordTime: member.recordTime,
      createTime: member.createTime,
      children: [],
    };
    group.children.push(member);
    groups.set(key, group);
  }
  return [...groups.values()];
});

const getStatusSummary = (records: AssistApplicationRecord[]) => {
  const counts = new Map<number, number>();
  records.forEach((record) => {
    const status = record.assistStatus ?? 0;
    counts.set(status, (counts.get(status) ?? 0) + 1);
  });
  return [...counts.entries()]
    .map(([status, count]) => `${count} ${statusMap[status] ?? '未知'}`)
    .join(' / ');
};

const groupChildren = (record: unknown): ApplicationMember[] => {
  if (!record || typeof record !== 'object') return [];
  const children = (record as { children?: unknown }).children;
  return Array.isArray(children) ? children as ApplicationMember[] : [];
};

const hasAppendableChild = (record: unknown) =>
  groupChildren(record).some((child) => child.assistStatus !== 4);

const firstAppendableChild = (record: unknown) => {
  const children = groupChildren(record);
  return children.find((child) => child.assistStatus !== 4) ?? children[0];
};

const detailOpen = ref(false);
const reapplyOpen = ref(false);
const current = ref<AssistApplicationRecord | null>(null);
const detailId = computed(() => (detailOpen.value ? current.value?.id : undefined));
const { data: detailData, isFetching: detailLoading } = useQueryAssistDetail(detailId);
const { data: deliveryAttachments } = useQueryAssistAttachments(detailId);
const detailRecord = computed(() => detailData.value ?? current.value);
const detailSnapshot = computed(() => {
  const record = detailRecord.value as (AssistApplicationRecord & { recordSnapshot?: string }) | null | undefined;
  return record?.recordSnapshot ?? record?.snapshot;
});
const applicationFiles = ref<UploadFile[]>([]);
const reapplyForm = ref<{
  originalAssistId?: number;
  assistUserId?: number;
  applyPurpose: string;
  applyRequirement: string;
}>({ applyPurpose: '', applyRequirement: '' });
const appendOpen = ref(false);
const appendTarget = ref<AssistApplicationRecord | null>(null);
const appendRows = ref<AssistApplyItem[]>([]);

const openDetail = (record: AssistApplicationRecord) => {
  current.value = record;
  applicationFiles.value = [];
  detailOpen.value = true;
};

const selectApplicationFile: UploadProps['customRequest'] = (options) => {
  if (!(options.file instanceof File)) {
    message.error('无效文件');
    return;
  }
  applicationFiles.value.push({
    uid: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    name: options.file.name,
    type: options.file.type,
    originFileObj: options.file as UploadFile['originFileObj'],
  });
  options.onSuccess?.({});
};

const removeApplicationFile = (file: UploadFile) => {
  applicationFiles.value = applicationFiles.value.filter((item) => item.uid !== file.uid);
  return Promise.resolve();
};

const uploadApplicationAttachments = async () => {
  const id = detailRecord.value?.id;
  if (!id) {
    return;
  }
  const files = applicationFiles.value
    .map((item) => item.originFileObj)
    .filter((file): file is NonNullable<UploadFile['originFileObj']> => Boolean(file))
    .map((file) => file as File);
  if (files.length === 0) {
    return;
  }
  try {
    await uploadAssistAttachment({ id, files });
    message.success('协助附件上传成功');
    applicationFiles.value = [];
  } catch (error) {
    message.error((error as { message?: string }).message ?? '协助附件上传失败');
  }
};

const openReapply = (record: AssistApplicationRecord) => {
  current.value = record;
  reapplyForm.value = {
    originalAssistId: record.id,
    assistUserId: record.assistUserId,
    applyPurpose: record.applyPurpose ?? '',
    applyRequirement: record.applyRequirement ?? '',
  };
  reapplyOpen.value = true;
};

const openAppend = (record: AssistApplicationRecord) => {
  appendTarget.value = record;
  appendRows.value = [{ assistUserId: undefined, applyPurpose: '', applyRequirement: '' }];
  appendOpen.value = true;
};

const submitAppend = async () => {
  const target = appendTarget.value;
  const rows = appendRows.value;
  if (!target?.id) return;
  if (!rows.length || rows.some((row) => !row.assistUserId
    || !row.applyPurpose?.trim() || !row.applyRequirement?.trim())) {
    message.warning('请为每位新增协助人选择用户，并填写协作目的与协作要求');
    return;
  }
  try {
    await appendAssist({
      originalAssistId: target.id,
      assistApplyList: rows.map((row) => ({
        ...row,
        applyPurpose: row.applyPurpose!.trim(),
        applyRequirement: row.applyRequirement!.trim(),
      })),
    });
    message.success('追加协助成功');
    appendOpen.value = false;
    appendTarget.value = null;
    appendRows.value = [];
  } catch (error) {
    message.error((error as { message?: string }).message ?? '追加协助失败');
  }
};

const submitReapply = () => {
  const { originalAssistId, assistUserId, applyPurpose, applyRequirement } = reapplyForm.value;
  if (originalAssistId == null || assistUserId == null) {
    message.error('原协助记录不完整，请刷新后重试');
    return;
  }
  if (!applyPurpose.trim() || !applyRequirement.trim()) {
    message.warning('请填写协作目的与协作要求');
    return;
  }
  reapply(
    {
      originalAssistId,
      assistApplyList: [{
        assistUserId,
        applyPurpose,
        applyRequirement,
      }],
    },
    {
      onSuccess: () => {
        message.success('重新申请成功');
        reapplyOpen.value = false;
      },
      onError: (e: unknown) => {
        message.error((e as { message?: string }).message ?? '重新申请失败');
      },
    },
  );
};

const onTableChange = (page: number, size: number) => {
  pageNum.value = page;
  pageSize.value = size;
};

const columns: TableColumnType<ApplicationRow>[] = [
  { title: '来源', dataIndex: 'modelName', key: 'modelName', width: 120 },
  { title: '业务标题', dataIndex: 'recordTitle', key: 'recordTitle' },
  {
    title: '协作目的',
    dataIndex: 'applyPurpose',
    key: 'applyPurpose',
    width: 180,
  },
  { title: '协助人', dataIndex: 'assistUserName', key: 'assistUserName', width: 140 },
  { title: '申请时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  {
    title: '状态',
    dataIndex: 'assistStatus',
    key: 'assistStatus',
    width: 100,
  },
  { title: '操作', key: 'action', width: 160 },
];
</script>
