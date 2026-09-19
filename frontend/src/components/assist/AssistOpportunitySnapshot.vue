<script setup lang="ts">
import {
  Alert as AAlert,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Empty as AEmpty,
  Tag as ATag,
} from 'ant-design-vue';
import { computed } from 'vue';
import { downloadAttachmentByUrl } from '@/utils/attachment';
import {
  getScopedSnapshotActivities,
  normalizeAssistModelName,
  parseAssistSnapshot,
  type SnapshotActivity,
  type SnapshotAttachment,
} from './assistSnapshot';

const props = defineProps<{
  modelName?: string;
  snapshot?: string;
  frozen?: boolean;
}>();

const data = computed(() => parseAssistSnapshot(props.snapshot));
const activities = computed(() =>
  data.value ? getScopedSnapshotActivities(data.value, props.modelName) : [],
);
const record = computed(() => data.value?.record);
const related = computed(() => data.value?.related ?? data.value?.opportunity);
const opportunity = computed(() => data.value?.opportunity);
const normalizedModelName = computed(() => normalizeAssistModelName(props.modelName ?? data.value?.modelName));
const isApproval = computed(() => normalizedModelName.value === 'sales_stage_approval');
const isBusinessActivity = computed(() => normalizedModelName.value === 'business_activity');
const isContactTask = computed(() => normalizedModelName.value === 'contact_task');
const recordAttachments = computed<SnapshotAttachment[]>(() => record.value?.attachments ?? []);

const displayActivityTitle = (activity: SnapshotActivity) =>
  activity.activityTitle ?? activity.title ?? '未命名活动';
const displayActivityTime = (activity: SnapshotActivity) =>
  activity.activityTime ?? activity.time ?? '-';
const displayActivityContent = (activity: SnapshotActivity) =>
  activity.activityContent ?? activity.content ?? '-';
const download = (attachment: SnapshotAttachment) => {
  void downloadAttachmentByUrl(attachment.downloadUrl, attachment.fileName || '附件');
};
</script>

<template>
  <section v-if="data || frozen" class="mt-4 border-t pt-4">
    <h3 class="mb-3 text-base font-medium">
      {{ frozen ? '历史快照（处理当时）' : '协助详情' }}
    </h3>

    <AAlert
      v-if="frozen && !data"
      type="warning"
      show-icon
      message="历史快照不可用"
      description="后端未返回该协助的历史记录，页面不会回查实时业务数据。"
    />

    <template v-else-if="data">
      <ADescriptions bordered :column="1" size="small">
        <ADescriptionsItem label="商机名称">
          {{ opportunity?.opportunityName || related?.opportunityName || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="客户公司">
          {{ opportunity?.companyName || related?.companyName || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem label="主要联系人">
          {{ opportunity?.contactName || related?.contactName || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem v-if="opportunity" label="阶段">
          {{ opportunity.stageName || opportunity.stage || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem v-if="opportunity" label="金额">
          {{ opportunity.amount ?? '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem v-if="opportunity" label="预计成交日期">
          {{ opportunity.expectedCloseDate || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem v-if="opportunity" label="来源">
          {{ opportunity.source || '-' }}
        </ADescriptionsItem>
        <ADescriptionsItem v-if="opportunity" label="说明">
          {{ opportunity.description || '-' }}
        </ADescriptionsItem>
      </ADescriptions>

      <div v-if="isBusinessActivity || isContactTask" class="mt-4">
        <h4 class="mb-2 text-sm font-medium">
          {{ isBusinessActivity ? '被协助业务活动' : '被协助联络任务' }}
        </h4>
        <div v-if="record" class="rounded border p-3">
          <div class="mb-1 flex flex-wrap items-center gap-2">
            <span class="font-medium">{{ record.title || record.activityTitle || '未命名记录' }}</span>
            <ATag v-if="record.activityType || record.taskType">
              {{ record.activityType || record.taskType }}
            </ATag>
          </div>
          <p class="mb-2 whitespace-pre-wrap text-sm text-gray-600">
            {{ record.content || record.activityContent || record.taskContent || '-' }}
          </p>
          <div class="text-xs text-gray-500">
            {{ record.time || record.activityTime || record.startTime || '-' }}
            <span v-if="record.endTime"> 至 {{ record.endTime }}</span>
          </div>
          <div v-if="recordAttachments.length" class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-sm">
            <button
              v-for="attachment in recordAttachments"
              :key="attachment.attachmentId ?? attachment.id ?? attachment.fileName"
              type="button"
              class="cursor-pointer border-0 bg-transparent p-0 text-blue-600 hover:text-blue-800 disabled:cursor-not-allowed disabled:text-gray-400"
              :disabled="!attachment.downloadUrl"
              @click="download(attachment)"
            >
              {{ attachment.fileName || '附件' }}
            </button>
          </div>
        </div>
        <AEmpty v-else description="暂无历史记录" :image="AEmpty.PRESENTED_IMAGE_SIMPLE" />
      </div>

      <div v-if="isApproval" class="mt-4">
        <h4 class="mb-2 text-sm font-medium">审批推进时的业务活动</h4>
        <AEmpty v-if="!activities.length" description="暂无业务活动" :image="AEmpty.PRESENTED_IMAGE_SIMPLE" />
        <div v-for="activity in activities" :key="activity.activityId ?? activity.id ?? activity.activityTitle" class="mb-3 rounded border p-3">
          <div class="mb-1 flex flex-wrap items-center gap-2">
            <span class="font-medium">{{ displayActivityTitle(activity) }}</span>
            <ATag v-if="activity.activityType">{{ activity.activityType }}</ATag>
            <span class="text-xs text-gray-500">{{ displayActivityTime(activity) }}</span>
          </div>
          <p class="mb-2 whitespace-pre-wrap text-sm text-gray-600">{{ displayActivityContent(activity) }}</p>
          <div v-if="activity.attachments?.length" class="flex flex-wrap gap-x-3 gap-y-1 text-sm">
            <button
              v-for="attachment in activity.attachments"
              :key="attachment.attachmentId ?? attachment.id ?? attachment.fileName"
              type="button"
              class="cursor-pointer border-0 bg-transparent p-0 text-blue-600 hover:text-blue-800 disabled:cursor-not-allowed disabled:text-gray-400"
              :disabled="!attachment.downloadUrl"
              @click="download(attachment)"
            >
              {{ attachment.fileName || '附件' }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="isContactTask && activities.length" class="mt-4">
        <h4 class="mb-2 text-sm font-medium">明确关联的业务活动</h4>
        <div v-for="activity in activities" :key="activity.activityId ?? activity.id ?? activity.activityTitle" class="mb-3 rounded border p-3">
          <div class="font-medium">{{ displayActivityTitle(activity) }}</div>
          <p class="mt-1 whitespace-pre-wrap text-sm text-gray-600">{{ displayActivityContent(activity) }}</p>
          <div v-if="activity.attachments?.length" class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-sm">
            <button
              v-for="attachment in activity.attachments"
              :key="attachment.attachmentId ?? attachment.id ?? attachment.fileName"
              type="button"
              class="cursor-pointer border-0 bg-transparent p-0 text-blue-600 hover:text-blue-800 disabled:cursor-not-allowed disabled:text-gray-400"
              :disabled="!attachment.downloadUrl"
              @click="download(attachment)"
            >
              {{ attachment.fileName || '附件' }}
            </button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
