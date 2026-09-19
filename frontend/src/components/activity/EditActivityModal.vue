<script setup lang="ts">
import type {
  PostBusinessActivityQueryResponse,
  PutBusinessActivityData,
} from '@/api/axios';
import type { UploadFile } from 'ant-design-vue';
import { ref, computed } from 'vue';
import {
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  InputNumber as AInputNumber,
  Modal as AModal,
  message,
  Select as ASelect,
  DatePicker as ADatePicker,
  Textarea as ATextarea,
  Upload as AUpload,
  List as AList,
  ListItem,
  ListItemMeta,
  Popconfirm as APopconfirm,
  Button as AButton
} from 'ant-design-vue';
import { useUpdateActivity, useQueryActivityAttachments, useUploadActivityAttachment, useDeleteActivityAttachment } from '@/hooks/useActivity';
import dayjs, { type Dayjs } from 'dayjs';
import { useQuerySales } from '@/hooks/useSale';
import { FileOutlined, PlusOutlined, DeleteOutlined, DownloadOutlined } from '@ant-design/icons-vue';
import { downloadAttachmentByUrl } from '@/utils/attachment';

type ActivityListItem = NonNullable<
  NonNullable<PostBusinessActivityQueryResponse['data']>['records']
>[number];
type ActivityUpdateItem = NonNullable<PutBusinessActivityData['body']>[number];

const form = ref<ActivityUpdateItem>({});
const { mutate: updateActivity, isPending: isUpdating } = useUpdateActivity();
const { mutate: uploadAttachments, isPending: isUploading } = useUploadActivityAttachment();
const { mutate: deleteAttachment } = useDeleteActivityAttachment();
const innerOpen = ref(false);
const currentActivityId = ref<number>();

// 附件列表查询 - 使用 computed 传递动态 ID
const { data: attachments, refetch: refetchAttachments } = useQueryActivityAttachments(
  computed(() => currentActivityId.value)
);

// 新附件列表
const newFileList = ref<UploadFile[]>([]);

// 获取所有销售机会列表
const { data: salesData } = useQuerySales({
  all: true,
});

const open = (data: ActivityListItem) => {
  form.value = {
    id: data.id,
    isAddUser: false,
    isAddContact: false,
    activityTitle: data.activityTitle,
    activityContent: data.activityContent,
    activityTime: data.activityTime || undefined,
    activityType: data.activityType,
    activityDuration: data.activityDuration,
    opportunityId: data.opportunityId,
    createTime: data.createTime,
    remark: data.remark,
    creatorId: data.creatorId,
    taskId: data.taskId,
  };
  currentActivityId.value = data.id;
  newFileList.value = [];
  innerOpen.value = true;
};

const activityTimeValue = computed<Dayjs | undefined>({
  get: () =>
    form.value.activityTime ? dayjs(form.value.activityTime) : undefined,
  set: (val) => {
    form.value.activityTime = val
      ? val.format('YYYY-MM-DD HH:mm:ss')
      : undefined;
  },
});

const close = () => {
  innerOpen.value = false;
};

defineExpose({ open, close });

const submit = () => {
  updateActivity([form.value], {
    onSuccess: () => {
      // 如果有新附件需要上传
      if (newFileList.value.length > 0 && currentActivityId.value) {
        message.loading('正在上传附件...', 0);
        uploadAttachments(
          {
            activityId: currentActivityId.value,
            files: newFileList.value.map((file) => file.originFileObj as File),
          },
          {
            onSuccess: () => {
              message.destroy();
              message.success('更新活动并上传附件成功');
              void refetchAttachments();
              newFileList.value = [];
              close();
            },
            onError: () => {
              message.destroy();
            },
          }
        );
      } else {
        message.success('更新活动成功');
        close();
      }
    },
  });
};
</script>

<template>
  <a-modal
    v-model:open="innerOpen"
    title="编辑业务活动"
    :confirm-loading="isUpdating || isUploading"
    width="600px"
    @ok="submit"
    @cancel="close"
  >
    <a-form
      :model="form"
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 16 }"
      layout="horizontal"
    >
      <a-form-item label="活动标题" name="activityTitle">
        <a-input
          v-model:value="form.activityTitle"
          placeholder="请输入活动标题"
        />
      </a-form-item>
      <a-form-item label="活动内容" name="activityContent">
        <a-textarea
          v-model:value="form.activityContent"
          placeholder="请输入活动内容"
          :rows="3"
        />
      </a-form-item>
      <a-form-item label="活动时间" name="activityTime">
        <a-date-picker
          v-model:value="activityTimeValue"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          placeholder="请选择活动时间"
          style="width: 100%"
        />
      </a-form-item>
      <a-form-item label="活动类型" name="activityType">
        <a-input
          v-model:value="form.activityType"
          placeholder="请输入活动类型"
        />
      </a-form-item>
      <a-form-item label="活动时长" name="activityDuration">
          <AInputNumber
            v-model:value="form.activityDuration"
            placeholder="请输入活动时长（分钟）"
            :min="0"
            style="width: 100%"
          />
      </a-form-item>
      <a-form-item label="销售机会" name="opportunityId">
        <a-select
          v-model:value="form.opportunityId"
          placeholder="请选择销售机会"
          show-search
          :filter-option="(input, option) =>
            option?.label?.toLowerCase().includes(input.toLowerCase())
          "
          :options="
            salesData?.records?.map((sale) => ({
              value: sale.id,
              label: sale.opportunityName,
            })) ?? []
          "
        />
      </a-form-item>
      <a-form-item label="备注" name="remark">
        <a-textarea
          v-model:value="form.remark"
          placeholder="请输入备注"
          :rows="2"
        />
      </a-form-item>
      <a-form-item label="已有附件">
        <a-list
          v-if="attachments && attachments.length > 0"
          :data-source="attachments"
          size="small"
          class="mb-2"
        >
          <template #renderItem="{ item }">
            <ListItem>
              <template #actions>
                <a-button
                  v-if="item.downloadUrl"
                  type="link"
                  size="small"
                  @click="downloadAttachmentByUrl(item.downloadUrl, item.fileName)"
                >
                  <template #icon><download-outlined /></template>
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
                    <template #icon><delete-outlined /></template>
                    删除
                  </a-button>
                </a-popconfirm>
              </template>
              <ListItemMeta>
                <template #avatar>
                  <file-outlined class="text-blue-500 text-lg" />
                </template>
                <template #title>
                  <span class="text-sm">{{ item.fileName }}</span>
                </template>
              </ListItemMeta>
            </ListItem>
          </template>
        </a-list>
        <div v-else class="text-gray-400 text-xs">暂无附件</div>
      </a-form-item>
      <a-form-item label="上传附件" name="newAttachments">
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
        <div class="text-gray-400 text-xs mt-1">支持上传多个附件，更新后自动上传</div>
      </a-form-item>
    </a-form>
  </a-modal>
</template>
