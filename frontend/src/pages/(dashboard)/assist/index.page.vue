<template>
  <div class="w-full">
    <Spin :spinning="isLoading" tip="Loading...">
      <Table
        data-tour="assist-table"
        :columns="columns"
        :data-source="data?.records ?? []"
        :row-key="(record: AssistRecord) => record.id!"
        :scroll="{ x: 900 }"
        :pagination="{
          current: pageNum,
          pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
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
          <template v-else-if="column.key === 'assistStatus'">
            <ATag :color="statusColorMap[record.assistStatus ?? 0]">
              {{ statusMap[record.assistStatus ?? 0] ?? '未知' }}
            </ATag>
          </template>
          <template v-else-if="column.key === 'action'">
            <AButton
              v-if="record.assistStatus === 0"
              type="link"
              size="small"
              @click="openHandle(record)"
            >
              处理
            </AButton>
            <AButton v-else type="link" size="small" @click="openRecordDetail(record)">
              详情
            </AButton>
          </template>
        </template>
      </Table>
    </Spin>

    <AModal
      v-model:open="handleOpen"
      title="处理协助申请"
      :confirm-loading="handling || uploadingDelivery"
      ok-text="已协助"
      cancel-text="取消"
      width="640px"
      :body-style="{ maxHeight: '70vh', overflowY: 'auto' }"
      @ok="submitHandle(1)"
      @cancel="closeHandle"
    >
      <Spin :spinning="detailLoading">
        <ATabs v-if="detailRecord" default-active-key="overview">
          <ATabPane key="overview" tab="概览">
            <ADescriptions bordered :column="1" size="small">
              <ADescriptionsItem label="来源">{{ modelNameMap[detailRecord.modelName ?? ''] ?? detailRecord.modelName }}</ADescriptionsItem>
              <ADescriptionsItem label="业务标题">{{ detailRecord.recordTitle || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="业务内容">{{ detailRecord.recordContent || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="协作目的">{{ detailRecord.applyPurpose || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="协作要求">{{ detailRecord.applyRequirement || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="业务时间">{{ detailRecord.recordTime || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="申请人">{{ detailRecord.applicantName || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="申请时间">{{ detailRecord.createTime || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="协助状态">{{ statusMap[detailRecord.assistStatus ?? 0] ?? '未知' }}</ADescriptionsItem>
              <ADescriptionsItem label="协助内容">{{ detailRecord.assistContent || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="驳回/拒绝理由">{{ detailRecord.rejectReason || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="处理时间">{{ detailRecord.assistTime || '-' }}</ADescriptionsItem>
            </ADescriptions>
          </ATabPane>
          <ATabPane key="related" tab="关联业务">
            <AssistRecordLinks
              :assist-id="detailRecord.id"
              :model-name="detailRecord.modelName"
              :record-id="detailRecord.recordId"
              :opportunity-id="detailRecord.opportunityId"
              :opportunity-name="detailRecord.opportunityName"
              :company-id="detailRecord.companyId"
              :company-name="detailRecord.companyName"
              :contact-id="detailRecord.contactId"
              :contact-name="detailRecord.contactName"
              :fallback="detailRecord"
              :snapshot="detailSnapshot"
              :frozen="detailRecord.assistStatus != null && Number(detailRecord.assistStatus) !== 0"
            />
            <AssistOpportunitySnapshot
              :model-name="detailRecord.modelName"
              :snapshot="detailSnapshot"
              :frozen="detailRecord.assistStatus != null && Number(detailRecord.assistStatus) !== 0"
            />
          </ATabPane>
          <ATabPane key="messages" tab="过程对话">
            <AssistMessagePanel :assist-id="detailRecord.id" :assist-status="detailRecord.assistStatus" />
          </ATabPane>
          <ATabPane key="attachments" tab="附件">
            <div>
              <h3 class="mb-2 text-sm font-medium">已有协助附件</h3>
              <p v-if="!deliveryAttachments?.length" class="text-sm text-gray-400">暂无附件</p>
              <div v-else class="flex flex-wrap gap-x-3 gap-y-1 text-sm">
                <button v-for="attachment in deliveryAttachments" :key="attachment.id" type="button" class="cursor-pointer border-0 bg-transparent p-0 text-blue-600 hover:text-blue-800" @click="downloadAttachmentByUrl(attachment.downloadUrl, attachment.fileName || '附件')">
                  {{ attachment.fileName || '附件' }}
                </button>
              </div>
            </div>
          </ATabPane>
          <ATabPane key="handle" tab="处理">
            <div>
              <label class="mb-1 block text-sm text-gray-600">
                {{ handleMode === 1 ? '协助结果（必填）' : '驳回/拒绝理由（必填）' }}
              </label>
              <ATextarea
                v-model:value="handleText"
                :rows="4"
                :placeholder="handleMode === 1 ? '请输入协助内容/成果' : '请输入驳回/拒绝理由'"
                :maxlength="500"
              />
              <div class="mt-2 flex justify-end gap-2">
                <AButton danger :loading="handling || uploadingDelivery" @click="submitHandle(2)">驳回</AButton>
                <AButton danger :loading="handling || uploadingDelivery" @click="submitHandle(3)">拒绝</AButton>
              </div>
              <div class="mt-4 border-t pt-3">
                <label class="mb-1 block text-sm text-gray-600">协助交付物附件（可选，可选择多个）</label>
                <AUpload
                  :file-list="deliveryFiles"
                  :custom-request="selectDeliveryFile"
                  :multiple="true"
                  @remove="removeDeliveryFile"
                >
                  <AButton :disabled="handling || uploadingDelivery">选择文件</AButton>
                </AUpload>
                <AButton
                  class="mt-2"
                  type="primary"
                  :loading="uploadingDelivery"
                  :disabled="deliveryFiles.length === 0 || handling"
                  @click="uploadDeliveryAttachments"
                >
                  单独上传附件
                </AButton>
              </div>
            </div>
          </ATabPane>
        </ATabs>
      </Spin>
    </AModal>

    <AModal v-model:open="detailOpen" title="协助详情" :footer="null" width="640px" :body-style="{ maxHeight: '70vh', overflowY: 'auto' }">
      <Spin :spinning="detailLoading">
        <ATabs v-if="detailRecord" default-active-key="overview">
          <ATabPane key="overview" tab="概览">
            <ADescriptions bordered :column="1" size="small">
              <ADescriptionsItem label="来源">{{ modelNameMap[detailRecord.modelName ?? ''] ?? detailRecord.modelName }}</ADescriptionsItem>
              <ADescriptionsItem label="业务标题">{{ detailRecord.recordTitle || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="业务内容">{{ detailRecord.recordContent || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="协作目的">{{ detailRecord.applyPurpose || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="协作要求">{{ detailRecord.applyRequirement || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="业务时间">{{ detailRecord.recordTime || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="申请人">{{ detailRecord.applicantName || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="申请时间">{{ detailRecord.createTime || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="协助状态">{{ statusMap[detailRecord.assistStatus ?? 0] ?? '未知' }}</ADescriptionsItem>
              <ADescriptionsItem label="协助内容">{{ detailRecord.assistContent || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="驳回/拒绝理由">{{ detailRecord.rejectReason || '-' }}</ADescriptionsItem>
              <ADescriptionsItem label="处理时间">{{ detailRecord.assistTime || '-' }}</ADescriptionsItem>
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
            <AssistOpportunitySnapshot :model-name="detailRecord?.modelName" :snapshot="detailSnapshot" :frozen="detailRecord?.assistStatus != null && Number(detailRecord.assistStatus) !== 0" />
          </ATabPane>
          <ATabPane key="messages" tab="过程对话">
            <AssistMessagePanel :assist-id="detailRecord?.id" :assist-status="detailRecord?.assistStatus" />
          </ATabPane>
          <ATabPane key="attachments" tab="附件">
            <div class="border-t pt-3">
              <h3 class="mb-2 text-sm font-medium">协助交付物</h3>
              <p v-if="!deliveryAttachments?.length" class="text-sm text-gray-400">暂无附件</p>
              <div v-else class="flex flex-wrap gap-x-3 gap-y-1 text-sm">
                <button v-for="attachment in deliveryAttachments" :key="attachment.id" type="button" class="cursor-pointer border-0 bg-transparent p-0 text-blue-600 hover:text-blue-800" @click="downloadAttachmentByUrl(attachment.downloadUrl, attachment.fileName || '附件')">
                  {{ attachment.fileName || '附件' }}
                </button>
              </div>
            </div>
          </ATabPane>
        </ATabs>
      </Spin>
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
  Textarea as ATextarea,
  Upload as AUpload,
  Tabs as ATabs,
  TabPane as ATabPane,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  message,
} from 'ant-design-vue';
import type { TableColumnType, UploadFile, UploadProps } from 'ant-design-vue';
import {
  useHandleAssist,
  useQueryAssistAttachments,
  useQueryAssistDetail,
  useQueryMyAssists,
  useUploadAssistAttachment,
  type AssistRecord,
} from '@/hooks/useAssist';
import AssistOpportunitySnapshot from '@/components/assist/AssistOpportunitySnapshot.vue';
import AssistRecordLinks from '@/components/assist/AssistRecordLinks.vue';
import { downloadAttachmentByUrl } from '@/utils/attachment';
import AssistMessagePanel from '@/components/assist/AssistMessagePanel.vue';

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

const { data, isLoading, pageNum, pageSize, assistStatus } = useQueryMyAssists();
const { mutateAsync: handleAssist, isPending: handling } = useHandleAssist();
const { mutateAsync: uploadAssistAttachment, isPending: uploadingDelivery } = useUploadAssistAttachment();

// 切换状态筛选时回到第一页
watch(assistStatus, () => {
  pageNum.value = 1;
});

const handleOpen = ref(false);
const detailOpen = ref(false);
const current = ref<AssistRecord | null>(null);
const handleText = ref('');
const handleMode = ref<1 | 2 | 3>(1);
const deliveryFiles = ref<UploadFile[]>([]);
const detailId = computed(() =>
  detailOpen.value || handleOpen.value ? current.value?.id : undefined,
);
const { data: detailData, isFetching: detailLoading } = useQueryAssistDetail(detailId);
const { data: deliveryAttachments } = useQueryAssistAttachments(detailId);
const detailRecord = computed(() => detailData.value ?? current.value);
const detailSnapshot = computed(() => {
  const record = detailRecord.value as (AssistRecord & { recordSnapshot?: string }) | null | undefined;
  return record?.recordSnapshot ?? record?.snapshot;
});

const openHandle = (record: AssistRecord) => {
  current.value = record;
  handleText.value = '';
  handleMode.value = 1;
  deliveryFiles.value = [];
  handleOpen.value = true;
};

const closeHandle = () => {
  handleOpen.value = false;
  current.value = null;
  handleText.value = '';
  deliveryFiles.value = [];
};

const selectDeliveryFile: UploadProps['customRequest'] = (options) => {
  if (!(options.file instanceof File)) {
    message.error('无效文件');
    return;
  }
  deliveryFiles.value.push({
    uid: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    name: options.file.name,
    type: options.file.type,
    originFileObj: options.file as UploadFile['originFileObj'],
  });
  options.onSuccess?.({});
};

const removeDeliveryFile = (file: UploadFile) => {
  deliveryFiles.value = deliveryFiles.value.filter((item) => item.uid !== file.uid);
  return Promise.resolve();
};

const uploadDeliveryAttachments = async () => {
  const id = current.value?.id;
  if (!id) {
    return;
  }
  const files = deliveryFiles.value
    .map((item) => item.originFileObj)
    .filter((file): file is NonNullable<UploadFile['originFileObj']> => Boolean(file))
    .map((file) => file as File);
  if (files.length === 0) {
    return;
  }
  try {
    await uploadAssistAttachment({ id, files });
    message.success('协助附件上传成功');
    deliveryFiles.value = [];
  } catch (error) {
    message.error((error as { message?: string }).message ?? '协助附件上传失败');
  }
};

const submitHandle = async (status: number) => {
  if (!handleText.value.trim()) {
    message.warning(status === 1 ? '请填写协助内容' : '请填写驳回/拒绝理由');
    return;
  }
  if (!current.value?.id) {
    return;
  }
  handleMode.value = status as 1 | 2 | 3;
  try {
    const files = deliveryFiles.value
      .map((item) => item.originFileObj)
      .filter((file): file is NonNullable<UploadFile['originFileObj']> => Boolean(file))
      .map((file) => file as File);
    // 附件是可选交付物：没有文件时直接完成；有文件时先全部上传，避免状态已结束后附件写入被拒绝。
    if (files.length > 0) {
      await uploadAssistAttachment({ id: current.value.id, files });
    }
    await handleAssist({
      id: current.value.id,
      assistStatus: status,
      assistContent: status === 1 ? handleText.value.trim() : undefined,
      rejectReason: status !== 1 ? handleText.value.trim() : undefined,
    });
    const file = deliveryFiles.value[0]?.originFileObj;
    if (file instanceof File) {
      await uploadAssistAttachment({ id: current.value.id, files: [file] });
    }
    message.success(
      status === 1
        ? '已标记为协助完成'
        : status === 2
          ? '已驳回该协助申请'
          : '已拒绝该协助申请',
    );
    closeHandle();
  } catch (error) {
    message.error((error as { message?: string }).message ?? '处理失败');
  }
};

const openRecordDetail = (record: AssistRecord) => {
  current.value = record;
  detailOpen.value = true;
};

const onTableChange = (page: number, size: number) => {
  pageNum.value = page;
  pageSize.value = size;
};

const columns: TableColumnType<AssistRecord>[] = [
  { title: '来源', dataIndex: 'modelName', key: 'modelName', width: 120 },
  { title: '申请人', dataIndex: 'applicantName', key: 'applicantName', width: 140 },
  { title: '申请时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  {
    title: '状态',
    dataIndex: 'assistStatus',
    key: 'assistStatus',
    width: 100,
  },
  { title: '内容/理由', dataIndex: 'assistContent', key: 'assistContent' },
  { title: '操作', key: 'action', width: 100 },
];
</script>
