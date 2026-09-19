<script setup lang="ts">
import {
  Alert as AAlert,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Empty as AEmpty,
  Modal as AModal,
  Spin as ASpin,
  Tag as ATag,
  Button as AButton,
  Upload as AUpload,
  List as AList,
  ListItem as AListItem,
  Tabs as ATabs,
  TabPane as ATabPane,
} from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import type { UploadFile, UploadProps } from 'ant-design-vue';
import {
  fetchAssistRecordDetail,
  fetchAssistAttachments,
  fetchAssistRelatedDetail,
  fetchAssistSourceAttachments,
  useUploadAssistSourceAttachment,
  type AssistAttachment,
  type AssistRelatedType,
} from '@/hooks/useAssist';
import { downloadAttachmentByUrl } from '@/utils/attachment';
import AssistOpportunitySnapshot from './AssistOpportunitySnapshot.vue';
import AssistMessagePanel from './AssistMessagePanel.vue';
import { parseAssistSnapshot } from './assistSnapshot';

const props = defineProps<{
  open: boolean;
  assistId?: number;
  type?: AssistRelatedType;
  fallback?: unknown;
  snapshot?: string;
  frozen?: boolean;
  /** 普通业务详情已有附件数据时，避免协助人弹窗再次请求协助来源附件。 */
  loadSourceAttachments?: boolean;
  sourceAttachments?: AssistAttachment[];
  viewMode?: 'business' | 'assist';
}>();

const emit = defineEmits<{ 'update:open': [value: boolean] }>();

const loading = ref(false);
const errorMessage = ref('');
const fetched = ref<Record<string, unknown> | null>(null);
const taskOpen = ref(false);
const taskLoading = ref(false);
const taskError = ref('');
const linkedTask = ref<Record<string, unknown> | null>(null);
const fetchedSourceAttachments = ref<AssistAttachment[]>([]);
const sourceFiles = ref<UploadFile[]>([]);
const { mutateAsync: uploadSourceAttachments, isPending: uploadingSourceAttachments } = useUploadAssistSourceAttachment();

const fallbackRecord = computed<Record<string, unknown>>(() => {
  if (!props.fallback || typeof props.fallback !== 'object') return {};
  return props.fallback as Record<string, unknown>;
});

const sourceType = computed(() => props.type === 'activity' || props.type === 'task' ? props.type : null);
const shouldLoadSourceAttachments = computed(() => props.loadSourceAttachments !== false);
const assistView = computed(() => props.viewMode === 'assist');
const visibleSourceAttachments = computed(() => props.sourceAttachments ?? fetchedSourceAttachments.value);
const snapshotAttachments = computed<AssistAttachment[]>(() => {
  const attachments = snapshotRecord.value.attachments;
  return Array.isArray(attachments) ? attachments as AssistAttachment[] : [];
});
const visibleBusinessAttachments = computed(() =>
  props.frozen
    ? snapshotAttachments.value
    : visibleSourceAttachments.value.length ? visibleSourceAttachments.value : snapshotAttachments.value,
);
const deliveryAttachments = ref<AssistAttachment[]>([]);
const resolvedSnapshot = computed(() => props.snapshot ?? (
  typeof fetched.value?.recordSnapshot === 'string' ? fetched.value.recordSnapshot : undefined
));
const snapshotRecord = computed<Record<string, unknown>>(() => {
  const record = parseAssistSnapshot(resolvedSnapshot.value)?.record;
  return record ? (record as Record<string, unknown>) : {};
});
const detail = computed(() => fetched.value ?? (props.frozen ? snapshotRecord.value : fallbackRecord.value));
const assistStatus = computed(() => {
  const value = detail.value.assistStatus;
  return typeof value === 'number' ? value : props.frozen ? 1 : 0;
});
const typeLabel = computed(() => ({
  company: '公司详情',
  contact: '联系人详情',
  approval: '审批记录详情',
  activity: '业务活动详情',
  task: '联络任务详情',
}[props.type ?? 'activity']));
const assistStatusLabel = (value: unknown) => ({
  0: '待协助',
  1: '已协助',
  2: '已驳回',
  3: '已拒绝',
  4: '已取消',
}[Number(value)] ?? '-');

const text = (key: string, alternative?: string) => {
  const value = detail.value[key] ?? (alternative ? detail.value[alternative] : undefined);
  if (value == null || value === '') return '-';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return `${value}`;
  }
  return JSON.stringify(value);
};

const firstText = (...keys: string[]) => {
  for (const key of keys) {
    const value = detail.value[key];
    if (value != null && value !== '') {
      if (typeof value === 'string') return value;
      if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
        return String(value);
      }
      return JSON.stringify(value);
    }
  }
  return '-';
};

const firstTextFrom = (source: Record<string, unknown>, ...keys: string[]) => {
  for (const key of keys) {
    const value = source[key];
    if (value != null && value !== '') {
      if (typeof value === 'string') return value;
      if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
        return String(value);
      }
      return JSON.stringify(value);
    }
  }
  return '-';
};

const close = () => emit('update:open', false);

const openLinkedTask = async () => {
  if (!props.assistId || props.frozen || !detail.value.taskId) return;
  taskOpen.value = true;
  taskLoading.value = true;
  taskError.value = '';
  try {
    const value = await fetchAssistRelatedDetail(props.assistId, 'task');
    linkedTask.value = value && typeof value === 'object'
      ? value as Record<string, unknown>
      : null;
  } catch (error) {
    taskError.value = error instanceof Error ? error.message : '联络任务加载失败';
  } finally {
    taskLoading.value = false;
  }
};

const load = async () => {
  fetched.value = null;
  linkedTask.value = null;
  taskOpen.value = false;
  errorMessage.value = '';
  fetchedSourceAttachments.value = [];
  deliveryAttachments.value = [];
  sourceFiles.value = [];
  if (!props.open || !props.assistId || !props.type) return;

  loading.value = true;
  try {
    const detailRequest = assistView.value
      ? fetchAssistRecordDetail(props.assistId)
      : !props.frozen
        ? fetchAssistRelatedDetail(props.assistId, props.type)
        : Promise.resolve(null);
    const attachmentRequest = !props.frozen && shouldLoadSourceAttachments.value && sourceType.value
      ? fetchAssistSourceAttachments(props.assistId, sourceType.value)
      : Promise.resolve<AssistAttachment[]>([]);

    // 详情和业务附件相互独立，详情接口失败时也不能阻断附件请求。
    const [detailResult, attachmentResult] = await Promise.allSettled([detailRequest, attachmentRequest]);
    if (detailResult.status === 'rejected') throw detailResult.reason;
    if (detailResult.value && typeof detailResult.value === 'object') {
      fetched.value = detailResult.value as Record<string, unknown>;
    }
    if (attachmentResult.status === 'fulfilled') {
      fetchedSourceAttachments.value = attachmentResult.value;
    }
    if (assistView.value) {
      deliveryAttachments.value = await fetchAssistAttachments(props.assistId);
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '关联详情加载失败';
  } finally {
    loading.value = false;
  }
};

const selectSourceFile: UploadProps['customRequest'] = (options) => {
  if (!(options.file instanceof File)) return;
  sourceFiles.value.push({
    uid: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    name: options.file.name,
    type: options.file.type,
    originFileObj: options.file as UploadFile['originFileObj'],
  });
  options.onSuccess?.({});
};

const removeSourceFile = (file: UploadFile) => {
  sourceFiles.value = sourceFiles.value.filter((item) => item.uid !== file.uid);
  return Promise.resolve();
};

const uploadSourceFiles = async () => {
  if (!props.assistId || !sourceType.value || !shouldLoadSourceAttachments.value) return;
  const files = sourceFiles.value
    .map((item) => item.originFileObj)
    .filter((file): file is NonNullable<UploadFile['originFileObj']> => Boolean(file))
    .map((file) => file as File);
  if (!files.length) return;
  await uploadSourceAttachments({ id: props.assistId, files });
  fetchedSourceAttachments.value = await fetchAssistSourceAttachments(props.assistId, sourceType.value);
  sourceFiles.value = [];
};

watch(
  () => [props.open, props.assistId, props.type, props.frozen] as const,
  () => void load(),
  { immediate: true },
);
</script>

<template>
  <AModal
    :open="open"
    :title="assistView ? '协助详情' : typeLabel"
    :footer="null"
    width="640px"
    destroy-on-close
    @cancel="close"
  >
    <ASpin :spinning="loading">
      <AAlert
        v-if="errorMessage"
        type="error"
        show-icon
        :message="errorMessage"
        description="详情接口拒绝或记录已不可见，当前协助上下文保持不变。"
      />

      <template v-else-if="assistView">
        <ATabs default-active-key="overview">
          <ATabPane key="overview" tab="概览">
            <ADescriptions bordered :column="1" size="small">
              <ADescriptionsItem label="来源">{{ firstText('modelName') }}</ADescriptionsItem>
              <ADescriptionsItem label="业务标题">{{ firstText('recordTitle', 'title', 'activityTitle', 'taskTitle') }}</ADescriptionsItem>
              <ADescriptionsItem label="业务内容">{{ firstText('recordContent', 'content', 'activityContent', 'taskContent') }}</ADescriptionsItem>
              <ADescriptionsItem label="协作目的">{{ text('applyPurpose') }}</ADescriptionsItem>
              <ADescriptionsItem label="协作要求">{{ text('applyRequirement') }}</ADescriptionsItem>
              <ADescriptionsItem label="协助人">{{ text('assistUserName') }}</ADescriptionsItem>
              <ADescriptionsItem label="状态">{{ assistStatusLabel(detail.assistStatus) }}</ADescriptionsItem>
              <ADescriptionsItem label="协助内容">{{ text('assistContent') }}</ADescriptionsItem>
              <ADescriptionsItem label="驳回/拒绝理由">{{ text('rejectReason') }}</ADescriptionsItem>
              <ADescriptionsItem label="处理时间">{{ text('assistTime') }}</ADescriptionsItem>
              <ADescriptionsItem label="申请时间">{{ text('createTime') }}</ADescriptionsItem>
            </ADescriptions>
          </ATabPane>
          <ATabPane key="attachments" tab="附件">
            <div>
              <div class="mb-2 text-sm font-medium">协助交付物</div>
              <AList v-if="deliveryAttachments.length" :data-source="deliveryAttachments" size="small" bordered>
                <template #renderItem="{ item }">
                  <AListItem class="flex items-center justify-between">
                    <span class="truncate">{{ item.fileName || '附件' }}</span>
                    <AButton v-if="item.downloadUrl" type="link" size="small" @click="downloadAttachmentByUrl(item.downloadUrl, item.fileName || '附件')">下载</AButton>
                  </AListItem>
                </template>
              </AList>
              <AEmpty v-else :image="undefined" description="暂无协助交付物" />
            </div>
          </ATabPane>
        </ATabs>
      </template>
      <template v-else-if="type === 'company'">
        <ADescriptions bordered :column="1" size="small">
          <ADescriptionsItem label="公司名称">{{ text('companyName') }}</ADescriptionsItem>
          <ADescriptionsItem label="集团">{{ text('belongGroup') }}</ADescriptionsItem>
          <ADescriptionsItem label="部门">{{ text('dept') }}</ADescriptionsItem>
          <ADescriptionsItem label="行业">{{ text('industry') }}</ADescriptionsItem>
          <ADescriptionsItem label="客户等级">{{ text('grade') }}</ADescriptionsItem>
          <ADescriptionsItem label="负责人">{{ text('ownerName') }}</ADescriptionsItem>
          <ADescriptionsItem label="联系人数量">
            {{ Array.isArray(detail.contactList) ? detail.contactList.length : '-' }}
          </ADescriptionsItem>
          <ADescriptionsItem label="说明">{{ text('description') }}</ADescriptionsItem>
        </ADescriptions>
      </template>

      <template v-else-if="type === 'contact'">
        <ADescriptions bordered :column="1" size="small">
          <ADescriptionsItem label="姓名">{{ text('name') }}</ADescriptionsItem>
          <ADescriptionsItem label="公司">{{ text('companyName') }}</ADescriptionsItem>
          <ADescriptionsItem label="部门">{{ text('dept') }}</ADescriptionsItem>
          <ADescriptionsItem label="职位">{{ text('position') }}</ADescriptionsItem>
          <ADescriptionsItem label="手机号">{{ text('mobile') }}</ADescriptionsItem>
          <ADescriptionsItem label="邮箱">{{ text('email') }}</ADescriptionsItem>
          <ADescriptionsItem label="客户关系等级">{{ text('relationLevel') }}</ADescriptionsItem>
        </ADescriptions>
      </template>

      <template v-else-if="type === 'approval' || type === 'activity' || type === 'task'">
        <ADescriptions v-if="Object.keys(detail).length" bordered :column="1" size="small">
          <ADescriptionsItem label="业务标题">
            {{ firstText('recordTitle', 'title', 'activityTitle', 'taskTitle', 'opportunityName') }}
          </ADescriptionsItem>
          <ADescriptionsItem label="业务内容">
            {{ firstText('recordContent', 'content', 'activityContent', 'taskContent', 'message') }}
          </ADescriptionsItem>
          <ADescriptionsItem label="业务时间">
            {{ firstText('recordTime', 'createTime', 'activityTime', 'time', 'startTime', 'applyTime') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'approval'" label="审批状态">
            <ATag>{{ text('approvalStatusDesc', 'approvalStatus') }}</ATag>
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'approval'" label="目标阶段">
            {{ text('targetStage') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'approval'" label="审批意见">
            {{ text('approvalOpinion') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'activity'" label="活动类型">
            {{ text('activityType') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'activity'" label="活动时长">
            {{ text('activityDuration') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'activity'" label="备注">
            {{ text('remark') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'activity'" label="关联联络任务">
            <AButton
              v-if="detail.taskId && !frozen"
              type="link"
              size="small"
              class="!px-0"
              @click="openLinkedTask"
            >
              查看任务 #{{ detail.taskId }}
            </AButton>
            <span v-else>{{ detail.taskId ? `#${detail.taskId}` : '未关联' }}</span>
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'task'" label="任务状态">
            {{ text('status') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'task'" label="任务类型">
            {{ text('taskType') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'task'" label="优先级">
            {{ text('priority') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'task'" label="开始时间">
            {{ text('startTime') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'task'" label="结束时间">
            {{ text('endTime') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type === 'task'" label="执行人">
            {{ firstText('assigneeName', 'assignee') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type !== 'approval'" label="协作目的">
            {{ text('applyPurpose') }}
          </ADescriptionsItem>
          <ADescriptionsItem v-if="type !== 'approval'" label="协作要求">
            {{ text('applyRequirement') }}
          </ADescriptionsItem>
        </ADescriptions>
        <AEmpty v-else description="暂无详情" />
        <div v-if="sourceType && (!frozen || snapshotAttachments.length) && (shouldLoadSourceAttachments || props.sourceAttachments || snapshotAttachments.length)" class="mt-4 border-t pt-3">
          <div class="mb-2 text-sm font-medium">业务附件</div>
          <AList v-if="visibleBusinessAttachments.length" :data-source="visibleBusinessAttachments" size="small" bordered>
            <template #renderItem="{ item }">
              <AListItem class="flex items-center justify-between">
                <span class="truncate">{{ item.fileName || '附件' }}</span>
                <AButton
                  v-if="item.downloadUrl"
                  type="link"
                  size="small"
                  @click="downloadAttachmentByUrl(item.downloadUrl, item.fileName || '附件')"
                >下载</AButton>
              </AListItem>
            </template>
          </AList>
          <AEmpty v-else :image="undefined" description="暂无业务附件" />
          <div v-if="!frozen && shouldLoadSourceAttachments" class="mt-2 flex flex-wrap items-center gap-2">
            <AUpload :file-list="sourceFiles" :custom-request="selectSourceFile" :multiple="true" @remove="removeSourceFile">
              <AButton :disabled="uploadingSourceAttachments">选择附件</AButton>
            </AUpload>
            <AButton
              type="primary"
              :loading="uploadingSourceAttachments"
              :disabled="!sourceFiles.length"
              @click="uploadSourceFiles"
            >上传业务附件</AButton>
          </div>
        </div>
        <AssistOpportunitySnapshot
          v-if="resolvedSnapshot"
          :model-name="type === 'approval' ? 'sales_stage_approval' : type === 'activity' ? 'business_activity' : 'contact_task'"
           :snapshot="resolvedSnapshot"
          :frozen="true"
        />
      </template>

      <AssistMessagePanel
        v-if="assistId"
        :assist-id="assistId"
        :assist-status="assistStatus"
      />

      <AEmpty v-if="!type" description="暂无可查看的关联记录" />
    </ASpin>
    <AModal
      v-model:open="taskOpen"
      title="关联联络任务"
      :footer="null"
      width="560px"
      destroy-on-close
    >
      <ASpin :spinning="taskLoading">
        <AAlert v-if="taskError" type="error" show-icon :message="taskError" />
        <ADescriptions v-else-if="linkedTask" bordered :column="1" size="small">
          <ADescriptionsItem label="任务标题">{{ firstTextFrom(linkedTask, 'taskTitle') }}</ADescriptionsItem>
          <ADescriptionsItem label="任务内容">{{ firstTextFrom(linkedTask, 'taskContent') }}</ADescriptionsItem>
          <ADescriptionsItem label="公司">{{ firstTextFrom(linkedTask, 'companyName') }}</ADescriptionsItem>
          <ADescriptionsItem label="联系人">{{ firstTextFrom(linkedTask, 'contactName') }}</ADescriptionsItem>
          <ADescriptionsItem label="状态">{{ firstTextFrom(linkedTask, 'status') }}</ADescriptionsItem>
        </ADescriptions>
        <AEmpty v-else description="暂无联络任务详情" />
      </ASpin>
    </AModal>
  </AModal>
</template>
