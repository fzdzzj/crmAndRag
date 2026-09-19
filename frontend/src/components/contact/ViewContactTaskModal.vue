<script setup lang="ts">
import type {
  GetContactTaskByIdResponse,
  PostContactTaskQueryResponse,
} from '@/api/axios';
import { computed, ref } from 'vue';
import {
  Modal as AModal,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Tag as ATag,
  Spin,
  Button as AButton,
  List as AList,
  ListItem,
  message,
} from 'ant-design-vue';
import { FileOutlined, DownloadOutlined } from '@ant-design/icons-vue';
import { useQueryContactTaskAttachments, useQueryContactTaskDetail } from '@/hooks/useContactTask';
import { downloadAttachmentByUrl } from '@/utils/attachment';
import {
  taskPriorityColorMap,
  taskPriorityLabelMap,
} from '@/constants/contact/constants';
import AssistUserTags from '@/components/assist/AssistUserTags.vue';
import AssistRelatedDetailModal from '@/components/assist/AssistRelatedDetailModal.vue';
import AssistApplyEditor from '@/components/assist/AssistApplyEditor.vue';
import { useApplyAssist, type AssistApplyItem, type AssistAttachment } from '@/hooks/useAssist';

type ContactTaskListItem = NonNullable<
  NonNullable<PostContactTaskQueryResponse['data']>['records']
>[number];
// 旧契约的 /contactTask/{id} 响应暂缺 assistUsers 字段（契约对齐专项补齐），
// 先用局部类型扩展，保持详情弹窗可展示协助人
type ContactTaskDetail = NonNullable<GetContactTaskByIdResponse['data']> & {
  assistUsers?: Array<{
    id?: number;
    assistUserName?: string;
    assistUserDeptName?: string;
    assistStatus?: number;
  }>;
};

const taskId = ref<number | null>(null);
const innerOpen = ref(false);
const assistDetailOpen = ref(false);
type TaskAssistUser = NonNullable<ContactTaskDetail['assistUsers']>[number];
const selectedAssist = ref<TaskAssistUser | null>(null);
const applyOpen = ref(false);
const applyList = ref<AssistApplyItem[]>([]);
const { mutateAsync: applyAssist, isPending: applyingAssist } = useApplyAssist();

const { data: task, isLoading } = useQueryContactTaskDetail(taskId);
const { data: attachments } = useQueryContactTaskAttachments(taskId);

const taskTypeMap: Record<string, { text: string; color: string }> = {
  call: { text: '电话', color: 'blue' },
  email: { text: '邮件', color: 'green' },
  meeting: { text: '会议', color: 'orange' },
  visit: { text: '拜访', color: 'purple' },
  other: { text: '其他', color: 'default' },
};

const taskStatusMap: Record<string, { text: string; color: string }> = {
  待处理: { text: '待处理', color: 'default' },
  未开始: { text: '未开始', color: 'default' },
  进行中: { text: '进行中', color: 'processing' },
  已完成: { text: '已完成', color: 'success' },
  已取消: { text: '已取消', color: 'error' },
};

const getTaskTypeMeta = (taskType: ContactTaskDetail['taskType']) =>
  taskType ? taskTypeMap[taskType] ?? { text: taskType, color: 'default' } : { text: '-', color: 'default' };

const getTaskStatusMeta = (status: ContactTaskDetail['status']) =>
  status ? taskStatusMap[status] ?? { text: status, color: 'default' } : { text: '-', color: 'default' };

const getPriorityMeta = (priority: ContactTaskDetail['priority']) => {
  if (!priority) return { text: '-', color: 'default' };
  const key = String(priority);
  return {
    text: taskPriorityLabelMap[key] ?? priority,
    color: taskPriorityColorMap[key] ?? 'default',
  };
};

const assistUsers = computed(
  () => (task.value as ContactTaskDetail | undefined)?.assistUsers,
);

const open = (data: ContactTaskListItem) => {
  taskId.value = data.id ?? null;
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  taskId.value = null;
};

const openAssistDetail = (user: TaskAssistUser) => {
  selectedAssist.value = user;
  assistDetailOpen.value = true;
};

const submitApply = async () => {
  const valid = applyList.value.filter((item) => item.assistUserId != null && item.applyPurpose?.trim() && item.applyRequirement?.trim());
  if (!valid.length || valid.length !== applyList.value.length || !task.value?.id) {
    message.warning('每位协助人都需要填写协作目的与协作要求');
    return;
  }
  try {
    await applyAssist({ modelName: 'contact_task', recordId: task.value.id, applyList: valid });
    message.success('协助申请已提交');
    applyOpen.value = false;
    applyList.value = [];
  } catch (error) {
    message.error(error instanceof Error ? error.message : '协助申请失败');
  }
};

defineExpose({ open, close });
</script>

<template>
  <AModal
    v-model:open="innerOpen"
    title="查看联络任务详情"
    :footer="null"
    width="700px"
  >
    <Spin :spinning="isLoading" tip="Loading...">
      <ADescriptions v-if="task" bordered :column="1">
      <ADescriptionsItem label="任务标题">
        {{ task.taskTitle || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="任务内容">
        {{ task.taskContent || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="公司名称">
        {{ task.companyName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="联系人">
        {{ task.contactName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="销售机会">
        {{ task.opportunityTitle || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="任务类型">
        <ATag :color="getTaskTypeMeta(task.taskType).color">
          {{ getTaskTypeMeta(task.taskType).text }}
        </ATag>
      </ADescriptionsItem>
      <ADescriptionsItem label="任务状态">
        <ATag :color="getTaskStatusMeta(task.status).color">
          {{ getTaskStatusMeta(task.status).text }}
        </ATag>
      </ADescriptionsItem>
      <ADescriptionsItem label="优先级">
        <ATag :color="getPriorityMeta(task.priority).color">
          {{ getPriorityMeta(task.priority).text }}
        </ATag>
      </ADescriptionsItem>
      <ADescriptionsItem label="开始时间">
        {{ task.startTime || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="结束时间">
        {{ task.endTime || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="任务执行人">
        {{ task.assigneeName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="协助人">
        <AssistUserTags
          :users="assistUsers"
          model-name="contact_task"
          :record-id="task.id"
          compact
          @open="openAssistDetail"
        />
      </ADescriptionsItem>
      <ADescriptionsItem label="创建人">
        {{ task.creatorName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="创建时间">
        {{ task.createTime || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="更新时间">
        {{ task.updateTime || '-' }}
      </ADescriptionsItem>
      </ADescriptions>
      <div v-if="task?.id" class="mt-3 flex justify-end">
        <AButton type="link" @click="applyOpen = true">申请协助</AButton>
      </div>
      <div v-if="attachments?.length" class="mt-4">
        <div class="mb-2 flex items-center text-sm font-medium text-gray-700">
          <file-outlined class="mr-1" />
          任务附件
        </div>
        <AList :data-source="attachments" size="small" bordered>
          <template #renderItem="{ item }">
            <ListItem class="flex items-center justify-between">
              <div class="flex min-w-0 flex-1 items-center">
                <file-outlined class="mr-2 text-blue-500" />
                <span class="truncate text-sm">{{ item.fileName || '附件' }}</span>
              </div>
              <AButton
                v-if="item.downloadUrl"
                type="link"
                size="small"
                class="text-blue-500"
                @click="downloadAttachmentByUrl(item.downloadUrl, item.fileName || '附件')"
              >
                <template #icon>
                  <download-outlined />
                </template>
                下载
              </AButton>
            </ListItem>
          </template>
        </AList>
      </div>
    </Spin>
  </AModal>
  <AssistRelatedDetailModal
    v-model:open="assistDetailOpen"
    :assist-id="selectedAssist?.id"
    type="task"
    :fallback="selectedAssist"
    :frozen="selectedAssist?.assistStatus != null && Number(selectedAssist.assistStatus) !== 0"
    :load-source-attachments="false"
    :source-attachments="(attachments ?? []) as AssistAttachment[]"
    view-mode="assist"
  />
  <AModal v-model:open="applyOpen" title="申请协助" :confirm-loading="applyingAssist" @ok="submitApply">
    <AssistApplyEditor v-model:value="applyList" />
  </AModal>
</template>
