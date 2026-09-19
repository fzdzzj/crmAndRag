<script setup lang="ts">
import { ref, computed } from 'vue';
import {
  Modal as AModal,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Grid,
  Tag as ATag,
  List as AList,
  ListItem,
  Button as AButton,
  message,
} from 'ant-design-vue';
import { useQueryActivityAttachments } from '@/hooks/useActivity';
import { UserOutlined, ContactsOutlined, FileOutlined, DownloadOutlined, TeamOutlined } from '@ant-design/icons-vue';
import { downloadAttachmentByUrl } from '@/utils/attachment';
import AssistUserTags from '@/components/assist/AssistUserTags.vue';
import AssistRelatedDetailModal from '@/components/assist/AssistRelatedDetailModal.vue';
import AssistApplyEditor from '@/components/assist/AssistApplyEditor.vue';
import { useApplyAssist, type AssistApplyItem, type AssistAttachment } from '@/hooks/useAssist';

const screens = Grid.useBreakpoint();
const isMobile = computed(() => !screens.value.md);

/** 弹窗展示所需的业务活动字段（列表记录与订单/合同详情里的活动记录均兼容） */
type ActivityViewData = {
  id?: number;
  activityTitle?: string;
  activityContent?: string;
  activityType?: string;
  activityDuration?: number;
  activityTime?: string;
  opportunityName?: string;
  creatorName?: string;
  createTime?: string;
  remark?: string;
  taskId?: number;
  users?: Array<{ userId?: number; userName?: string; userRole?: string }>;
  contacts?: Array<{ contactId?: number; contactName?: string; contactRole?: string }>;
  assistUsers?: Array<{
    id?: number;
    assistUserName?: string;
    assistUserDeptName?: string;
    assistStatus?: number;
  }>;
};

const activity = ref<ActivityViewData | null>(null);
const innerOpen = ref(false);
const assistDetailOpen = ref(false);
type ActivityAssistUser = NonNullable<ActivityViewData['assistUsers']>[number];
const selectedAssist = ref<ActivityAssistUser | null>(null);
const applyOpen = ref(false);
const applyList = ref<AssistApplyItem[]>([]);
const { mutateAsync: applyAssist, isPending: applyingAssist } = useApplyAssist();

// 计算当前活动 ID，用于查询附件
const activityId = computed(() => activity.value?.id);

// 查询附件列表
const { data: attachments } = useQueryActivityAttachments(activityId);


const open = (data: ActivityViewData) => {
  activity.value = data;
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  activity.value = null;
};

const openAssistDetail = (user: NonNullable<ActivityViewData['assistUsers']>[number]) => {
  selectedAssist.value = user;
  assistDetailOpen.value = true;
};

const submitApply = async () => {
  const valid = applyList.value.filter((item) => item.assistUserId != null && item.applyPurpose?.trim() && item.applyRequirement?.trim());
  if (!valid.length || valid.length !== applyList.value.length || !activity.value?.id) {
    message.warning('每位协助人都需要填写协作目的与协作要求');
    return;
  }
  try {
    await applyAssist({ modelName: 'business_activity', recordId: activity.value.id, applyList: valid });
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
  <AModal v-model:open="innerOpen" title="查看业务活动详情" :footer="null" width="800px">
    <ADescriptions v-if="activity" bordered :column="isMobile ? 1 : 2">
      <ADescriptionsItem label="活动标题" :span="2">
        {{ activity.activityTitle || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="活动内容" :span="2">
        {{ activity.activityContent || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="活动类型">
        <ATag>
          {{ activity.activityType }}
        </ATag>
      </ADescriptionsItem>
      <ADescriptionsItem label="活动时长">
        {{
          activity.activityDuration ? `${activity.activityDuration} 分钟` : '-'
        }}
      </ADescriptionsItem>
      <ADescriptionsItem label="活动时间" :span="2">
        {{ activity.activityTime || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="销售机会" :span="2">
        {{ activity.opportunityName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="关联联络任务" :span="2">
        {{ activity.taskId ? `#${activity.taskId}` : '未关联' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="创建人">
        {{ activity.creatorName || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="创建时间">
        {{ activity.createTime || '-' }}
      </ADescriptionsItem>
      <ADescriptionsItem label="备注" :span="2">
        {{ activity.remark || '-' }}
      </ADescriptionsItem>
    </ADescriptions>

    <!-- 关联用户 -->
    <div v-if="activity?.users && activity.users.length > 0" class="mt-4">
      <div class="mb-2 flex items-center text-sm font-medium text-gray-700">
        <user-outlined class="mr-1" />
        关联用户
      </div>
      <AList :data-source="activity.users" size="small" bordered>
        <template #renderItem="{ item }">
          <ListItem class="flex items-center justify-between">
            <span>{{ item.userName }}</span>
            <ATag v-if="item.userRole" color="blue" class="text-xs">
              {{ item.userRole }}
            </ATag>
          </ListItem>
        </template>
      </AList>
    </div>

    <!-- 关联联系人 -->
    <div v-if="activity?.contacts && activity.contacts.length > 0" class="mt-4">
      <div class="mb-2 flex items-center text-sm font-medium text-gray-700">
        <contacts-outlined class="mr-1" />
        关联联系人
      </div>
      <AList :data-source="activity.contacts" size="small" bordered>
        <template #renderItem="{ item }">
          <ListItem class="flex items-center justify-between">
            <span>{{ item.contactName }}</span>
            <ATag v-if="item.contactRole" color="green" class="text-xs">
              {{ item.contactRole }}
            </ATag>
          </ListItem>
        </template>
      </AList>
    </div>

    <!-- 协助人 -->
      <div v-if="activity?.assistUsers && activity.assistUsers.length > 0" class="mt-4">
      <div class="mb-2 flex items-center text-sm font-medium text-gray-700">
        <team-outlined class="mr-1" />
        协助人
      </div>
      <AssistUserTags
        :users="activity.assistUsers"
        model-name="business_activity"
        :record-id="activity.id"
        @open="openAssistDetail"
      />
    </div>
    <div v-if="activity?.id" class="mt-3 flex justify-end">
      <AButton type="link" @click="applyOpen = true">申请协助</AButton>
    </div>

    <!-- 附件列表 -->
    <div v-if="attachments && attachments.length > 0" class="mt-4">
      <div class="mb-2 flex items-center text-sm font-medium text-gray-700">
        <file-outlined class="mr-1" />
        活动附件
      </div>
      <AList :data-source="attachments" size="small" bordered>
        <template #renderItem="{ item }">
          <ListItem class="flex items-center justify-between">
            <div class="flex items-center flex-1">
              <file-outlined class="mr-2 text-blue-500" />
              <span class="text-sm">{{ item.fileName }}</span>
            </div>
            <a-button
              v-if="item.downloadUrl"
              type="link"
              size="small"
              class="text-blue-500"
              @click="downloadAttachmentByUrl(item.downloadUrl, item.fileName)"
            >
              <template #icon>
                <download-outlined />
              </template>
              下载
            </a-button>
          </ListItem>
        </template>
      </AList>
    </div>
  </AModal>
  <AssistRelatedDetailModal
    v-model:open="assistDetailOpen"
    :assist-id="selectedAssist?.id"
    type="activity"
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
