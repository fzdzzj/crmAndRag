<script setup lang="ts">
import type { PostBusinessActivityQueryResponse } from '@/api/axios';
import { ref, computed } from 'vue';
import type { UploadFile } from 'ant-design-vue';
import {
  Modal as AModal,
  message,
  Upload as AUpload,
  List as AList,
  ListItem,
  Button as AButton,
  Popconfirm as APopconfirm,
} from 'ant-design-vue';
import { FileOutlined, PlusOutlined, DownloadOutlined, DeleteOutlined } from '@ant-design/icons-vue';
import { useUploadActivityAttachment, useQueryActivityAttachments, useDeleteActivityAttachment } from '@/hooks/useActivity';
import { downloadAttachmentByUrl } from '@/utils/attachment';

type ActivityListItem = NonNullable<
  NonNullable<PostBusinessActivityQueryResponse['data']>['records']
>[number];

const innerOpen = ref(false);
const currentActivity = ref<ActivityListItem>();
const newFileList = ref<UploadFile[]>([]);

const { mutate: uploadAttachments, isPending: isUploading } = useUploadActivityAttachment();
const { mutate: deleteAttachment } = useDeleteActivityAttachment();

// 计算当前活动 ID，用于查询附件
const activityId = computed(() => currentActivity.value?.id);

// 查询附件列表
const { data: attachments, refetch: refetchAttachments } = useQueryActivityAttachments(activityId);

const open = (activity: ActivityListItem) => {
  currentActivity.value = activity;
  newFileList.value = [];
  innerOpen.value = true;
};

const close = () => {
  innerOpen.value = false;
  currentActivity.value = undefined;
};

defineExpose({ open, close });

const submit = () => {
  if (newFileList.value.length === 0) {
    message.warning('请选择要上传的附件');
    return;
  }

  if (!currentActivity.value?.id) {
    message.error('活动 ID 不存在');
    return;
  }

  uploadAttachments(
    {
      activityId: currentActivity.value.id,
      files: newFileList.value.map((file) => file.originFileObj as File),
    },
    {
      onSuccess: () => {
        message.success('附件上传成功');
        newFileList.value = [];
        void refetchAttachments();
      },
    }
  );
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="上传活动附件"
    :confirm-loading="isUploading"
    width="600px"
    @ok="submit"
    @cancel="close"
  >
    <div class="mb-4">
      <div class="text-sm font-medium text-gray-700 mb-2">活动名称</div>
      <div class="text-gray-600">{{ currentActivity?.activityTitle || '-' }}</div>
    </div>

    <!-- 已有附件列表 -->
    <div v-if="attachments && attachments.length > 0" class="mb-4">
      <div class="mb-2 flex items-center text-sm font-medium text-gray-700">
        <file-outlined class="mr-1" />
        已有附件 ({{ attachments.length }})
      </div>
      <a-list :data-source="attachments" size="small" bordered>
        <template #renderItem="{ item }">
          <ListItem class="flex items-center justify-between">
            <div class="flex items-center flex-1">
              <file-outlined class="mr-2 text-blue-500" />
              <span class="text-sm">{{ item.fileName }}</span>
            </div>
            <div class="flex items-center gap-2">
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
              <a-popconfirm
                title="确定删除此附件？"
                ok-text="确定"
                cancel-text="取消"
                @confirm="deleteAttachment(item.id!, {
                  onSuccess: () => {
                    message.success('删除成功');
                    refetchAttachments();
                  },
                  onError: () => {
                    message.error('删除失败');
                  }
                })"
              >
                <a-button type="link" danger size="small">
                  <template #icon>
                    <delete-outlined />
                  </template>
                  删除
                </a-button>
              </a-popconfirm>
            </div>
          </ListItem>
        </template>
      </a-list>
    </div>

    <!-- 上传新附件 -->
    <div>
      <div class="mb-2 text-sm font-medium text-gray-700">上传新附件</div>
      <a-upload
        v-model:file-list="newFileList"
        :before-upload="() => false"
        :max-count="10"
        list-type="picture-card"
      >
        <div v-if="newFileList.length < 10">
          <plus-outlined />
          <div style="margin-top: 8px">上传附件</div>
        </div>
      </a-upload>
      <div class="text-gray-400 text-xs mt-1">支持上传多个附件</div>
    </div>
  </a-modal>
</template>
