<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  Button as AButton,
  Form as AForm,
  FormItem as AFormItem,
  Input as AInput,
  Select as ASelect,
  Textarea as ATextarea,
  Table as ATable,
  Upload as AUpload,
  Modal as AModal,
  Popconfirm,
  message,
  Tag as ATag,
  type UploadChangeParam,
  type UploadFile,
  type UploadProps,
} from 'ant-design-vue';
import { UploadOutlined, DeleteOutlined } from '@ant-design/icons-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { validateForm } from '@/utils/formValidate';
import type {
  GetProjectFileListOpportunityByOpportunityIdResponse,
} from '@/api/axios';
import {
  useProjectFilesByOpportunity,
  useProjectFilesByContract,
  useUploadProjectFile,
  useDeleteProjectFile,
  projectFileCategoryMap,
  projectFileCategoryOptions,
  type UploadProjectFilePayload,
} from '@/hooks/useProjectFile';
import { downloadAttachmentByUrl } from '@/utils/attachment';

type ArrayItem<T> = T extends readonly (infer U)[] ? U : never;
type ProjectFileVO = ArrayItem<
  NonNullable<GetProjectFileListOpportunityByOpportunityIdResponse['data']>
>;

const props = defineProps<{
  opportunityId?: number;
  contractId?: number;
}>();

const opportunityIdRef = computed(() => props.opportunityId);
const contractIdRef = computed(() => props.contractId);

const { data: fileListByOpportunity, isLoading: isLoadingByOpportunity } =
  useProjectFilesByOpportunity(opportunityIdRef);
const { data: fileListByContract, isLoading: isLoadingByContract } =
  useProjectFilesByContract(contractIdRef);

const fileList = computed(() => {
  if (props.opportunityId) return fileListByOpportunity.value ?? [];
  if (props.contractId) return fileListByContract.value ?? [];
  return [];
});
const isLoading = computed(() => {
  if (props.opportunityId) return isLoadingByOpportunity.value;
  if (props.contractId) return isLoadingByContract.value;
  return false;
});
const { mutateAsync: uploadFile, isPending: isUploading } =
  useUploadProjectFile();
const { mutateAsync: deleteFile } = useDeleteProjectFile();

const uploadModalOpen = ref(false);
const uploadFormRef = ref();
const uploadRules: Record<string, Rule[]> = {
  category: [{ required: true, message: '请选择文件分类', trigger: 'change' }],
};
const uploadForm = ref({
  category: undefined as string | undefined,
  theme: '',
  description: '',
});
const uploadFileList = ref<UploadFile[]>([]);

const columns = [
  {
    title: '文件名',
    dataIndex: 'fileName',
    key: 'fileName',
    ellipsis: true,
  },
  {
    title: '分类',
    dataIndex: 'category',
    key: 'category',
    width: 120,
  },
  {
    title: '主题',
    dataIndex: 'theme',
    key: 'theme',
    ellipsis: true,
  },
  {
    title: '上传人',
    dataIndex: 'uploaderName',
    key: 'uploaderName',
    width: 100,
  },
  {
    title: '上传时间',
    dataIndex: 'uploadTime',
    key: 'uploadTime',
    width: 170,
  },
  {
    title: '操作',
    key: 'action',
    width: 120,
  },
];

function getCategoryLabel(category: string) {
  return projectFileCategoryMap[category] || category;
}

function getCategoryColor(category: string) {
  const colorMap: Record<string, string> = {
    VISIT_RECORD: 'blue',
    MEETING_MINUTES: 'green',
    PROPOSAL: 'orange',
    BID_DOCUMENT: 'purple',
    PROJECT_CONTRACT: 'cyan',
  };
  return colorMap[category] || 'default';
}

function openUploadModal() {
  uploadForm.value = { category: undefined, theme: '', description: '' };
  uploadFileList.value = [];
  uploadModalOpen.value = true;
}

function handleUploadFile(info: UploadChangeParam<UploadFile>) {
  uploadFileList.value = [info.file];
}

function handleRemoveFile() {
  uploadFileList.value = [];
}

async function handleUploadSubmit() {
  if (!(await validateForm(uploadFormRef.value))) {
    return;
  }
  const category = uploadForm.value.category;
  if (!category) {
    message.warning('请选择文件分类');
    return;
  }
  if (uploadFileList.value.length === 0) {
    message.warning('请选择要上传的文件');
    return;
  }

  const selectedFile = uploadFileList.value[0]?.originFileObj ?? uploadFileList.value[0];
  if (!(selectedFile instanceof Blob)) {
    message.warning('未读取到有效文件');
    return;
  }

  const payload: UploadProjectFilePayload = {
    category,
    file: selectedFile instanceof File ? selectedFile : new File([selectedFile], 'upload'),
    theme: uploadForm.value.theme || undefined,
    description: uploadForm.value.description || undefined,
    opportunityId: props.opportunityId,
    contractId: props.contractId,
  };

  try {
    await uploadFile(payload);
    uploadModalOpen.value = false;
    uploadFileList.value = [];
  } catch {
    // 上传失败：由 useUploadProjectFile 的 onError 提示，弹窗保持打开
  }
}

async function handleDelete(record: ProjectFileVO) {
  if (!record.id) return;
  try {
    await deleteFile([record.id]);
  } catch {
    // 删除失败：由 useDeleteProjectFile 的 onError 提示，此处仅拦截 reject 避免未处理拒绝
  }
}

function formatFileSize(bytes?: number) {
  if (!bytes) return '-';
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}
</script>

<template>
  <div>
    <div class="mb-3 flex items-center justify-between">
      <div class="text-base font-medium">项目文件</div>
      <AButton type="primary" size="small" @click="openUploadModal">
        <UploadOutlined class="mr-1" />
        上传文件
      </AButton>
    </div>

    <ATable
      :columns="columns"
      :data-source="fileList ?? []"
      :loading="isLoading"
      :pagination="false"
      row-key="id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'category'">
          <ATag :color="getCategoryColor(record.category)">
            {{ getCategoryLabel(record.category) }}
          </ATag>
        </template>
        <template v-if="column.key === 'fileName'">
          <a
            v-if="record.downloadUrl"
            class="text-blue-500 hover:text-blue-700"
            @click.prevent="downloadAttachmentByUrl(record.downloadUrl, record.fileName)"
          >
            {{ record.fileName }}
          </a>
          <span v-else>{{ record.fileName }}</span>
          <span class="ml-2 text-xs text-slate-400">
            {{ formatFileSize(record.fileSize) }}
          </span>
        </template>
        <template v-if="column.key === 'action'">
          <Popconfirm
            title="确定删除该文件吗？"
            ok-text="确定"
            cancel-text="取消"
            @confirm="handleDelete(record)"
          >
            <DeleteOutlined
              class="cursor-pointer text-red-500 hover:text-red-700"
            />
          </Popconfirm>
        </template>
      </template>
    </ATable>

    <AModal
      v-model:open="uploadModalOpen"
      title="上传项目文件"
      :confirm-loading="isUploading"
      @ok="handleUploadSubmit"
    >
      <a-form
        ref="uploadFormRef"
        :model="uploadForm"
        :rules="uploadRules"
        :label-col="{ span: 5 }"
        :wrapper-col="{ span: 18 }"
      >
        <a-form-item label="文件分类" name="category" required>
          <ASelect
            v-model:value="uploadForm.category"
            placeholder="请选择文件分类"
            :options="projectFileCategoryOptions"
          />
        </a-form-item>
        <a-form-item label="主题">
          <a-input v-model:value="uploadForm.theme" placeholder="请输入主题" />
        </a-form-item>
        <a-form-item label="说明">
          <a-textarea
            v-model:value="uploadForm.description"
            placeholder="请输入说明"
            :rows="2"
          />
        </a-form-item>
        <a-form-item label="选择文件" required>
          <AUpload
            :file-list="uploadFileList"
            :max-count="1"
            :custom-request="
              (options: Parameters<NonNullable<UploadProps['customRequest']>>[0]) => {
                handleUploadFile({
                  file: options.file as UploadFile,
                  fileList: [options.file as UploadFile],
                });
                options.onSuccess?.({});
              }
            "
            @remove="handleRemoveFile"
          >
            <AButton>
              <UploadOutlined class="mr-1" />
              选择文件
            </AButton>
          </AUpload>
        </a-form-item>
      </a-form>
    </AModal>
  </div>
</template>
