<template>
  <a-modal
    v-model:open="innerOpen"
    title="新建客户"
    :confirm-loading="isAdding"
    ok-text="创建"
    cancel-text="取消"
    width="800px"
    @ok="submit"
    @cancel="close"
  >
    <a-tabs v-model:active-key="activeTab" data-tour="client-create-tabs">
      <a-tab-pane key="form" tab="表单创建">
        <a-form
          ref="formRef"
          :model="form"
          :rules="rules"
          :label-col="{ span: 6 }"
          :wrapper-col="{ span: 18 }"
          layout="horizontal"
          class="create-form"
        >
          <a-alert
            type="info"
            show-icon
            message="除公司名称外，其他信息均可后续补充"
            class="mb-3"
          />
          <a-form-item label="公司名称" name="companyName" required>
            <a-input
              v-model:value="form.companyName"
              placeholder="请输入公司名称"
            />
          </a-form-item>
          <a-form-item label="行业">
            <a-input
              v-model:value="form.industry"
              placeholder="请输入行业，如：石油/IT/互联网"
            />
          </a-form-item>
          <a-form-item label="客户属性">
            <a-select
              v-model:value="form.customerType"
              placeholder="请选择客户属性"
              allow-clear
            >
              <a-select-option value="代理">代理</a-select-option>
              <a-select-option value="直销">直销</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="归属集团">
            <a-select
              v-model:value="form.belongGroup"
              show-search
              :filter-option="false"
              :options="groupSelectOptions"
              placeholder="搜索或输入集团名称，可选“创建”选项"
              allow-clear
              :loading="isSearchingGroup || isCreatingGroup"
              @search="handleGroupSearch"
              @change="handleGroupChange"
              @input-key-down="handleGroupKeydown"
            />
          </a-form-item>
          <a-form-item label="部门">
            <a-select
              v-model:value="form.dept"
              show-search
              :filter-option="false"
              :options="deptSelectOptions"
              :placeholder="selectedGroupId || pendingGroupName ? '搜索或输入部门名称，可选“创建”选项' : '请先选择归属集团'"
              allow-clear
              :disabled="!selectedGroupId && !pendingGroupName"
              :loading="isSearchingDept || isCreatingDept"
              @search="handleDeptSearch"
              @change="handleDeptChange"
              @input-key-down="handleDeptKeydown"
            />
          </a-form-item>
          <a-form-item label="公司地址">
            <a-input
              v-model:value="form.address"
              placeholder="请输入地址"
            />
          </a-form-item>
          <a-form-item label="简介">
            <a-input
              v-model:value="form.description"
              placeholder="请输入公司简介"
            />
          </a-form-item>
          <a-form-item label="公司等级">
            <a-rate
              v-model:value="form.grade"
              :count="9"
            />
          </a-form-item>
        </a-form>
      </a-tab-pane>
      <a-tab-pane key="excel" tab="Excel导入">
        <div class="excel-section">
          <div class="excel-prompt">
            <span class="prompt-text">上传前请先按Excel模板中的格式编辑内容</span>
            <span class="download-link" @click="exportCompaniesTemplate()">
              <download-outlined v-if="!isExportingTemplate" />
              <a-spin v-else />
              <span v-if="!isExportingTemplate">下载模板</span>
            </span>
          </div>
          <div class="excel-import">
            <a-upload-dragger
              v-model:file-list="fileList"
              name="file"
              :max-count="1"
              :custom-request="handleCustomRequest"
            >
              <p class="ant-upload-drag-icon">
                <inbox-outlined></inbox-outlined>
              </p>
              <p class="ant-upload-text">点击或拖拽文件到此区域上传</p>
              <p class="ant-upload-hint">仅支持 .xlsx 或 .xls 格式的 Excel 文件</p>
            </a-upload-dragger>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  Modal as AModal,
  Input as AInput,
  Form as AForm,
  FormItem as AFormItem,
  Tabs as ATabs,
  TabPane as ATabPane,
  Select as ASelect,
  SelectOption as ASelectOption,
  Rate as ARate,
  Alert as AAlert,
  message,
  Spin as ASpin,
  UploadDragger as AUploadDragger,
} from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import {
  InboxOutlined,
  DownloadOutlined,
} from '@ant-design/icons-vue';
import type { PostCompanyData } from '@/api/axios';
import { TokenManager } from '@/utils/token';
import { useAddCompany, useGroupDeptMaster } from '@/hooks/useCompany';
import { useExportCompaniesTemplate } from '@/hooks/useCompany';
import { useImportCompanies } from '@/hooks/useCompany';
import { validateForm } from '@/utils/formValidate';
import type { UploadFile } from 'ant-design-vue';
import type { UploadRequestOption } from 'ant-design-vue/es/vc-upload/interface';

type CompanyCreateForm = NonNullable<PostCompanyData['body']>;

const ownerId = TokenManager.getPayload()?.userID;

const innerOpen = ref(false);
const activeTab = ref('form');

function createDefaultForm(nextOwnerId?: number): CompanyCreateForm {
  return {
    address: '',
    companyName: '',
    description: '',
    industry: '',
    customerType: undefined,
    belongGroup: undefined,
    dept: undefined,
    grade: 0,
    ownerId: nextOwnerId ?? 0,
  };
}

const form = ref<CompanyCreateForm>(createDefaultForm(ownerId));
const formRef = ref();

const rules: Record<string, Rule[]> = {
  companyName: [{ required: true, message: '公司名称不能为空', trigger: 'blur' }],
};

const { mutate: addCompany, isPending: isAdding } = useAddCompany();
const { mutate: importCompanies } = useImportCompanies();
const { mutate: exportCompaniesTemplate, isPending: isExportingTemplate } =
  useExportCompaniesTemplate();

const {
  groupOptions,
  deptOptions,
  selectedGroupId,
  isSearchingGroup,
  isSearchingDept,
  isCreatingGroup,
  isCreatingDept,
  pendingGroupName,
  pendingDeptName,
  groupCreateOption,
  deptCreateOption,
  groupSearchText,
  deptSearchText,
  searchGroups,
  searchDepts,
  selectGroup,
  markPendingGroup,
  markPendingDept,
  clearPending,
  commitPending,
} = useGroupDeptMaster();

const groupSelectOptions = computed(() =>
  [
    ...groupOptions.value.map((g) => ({ value: g.groupName, label: g.groupName })),
    ...(groupCreateOption.value ? [groupCreateOption.value] : []),
  ],
);
const deptSelectOptions = computed(() =>
  [
    ...deptOptions.value.map((d) => ({ value: d.deptName, label: d.deptName })),
    ...(deptCreateOption.value ? [deptCreateOption.value] : []),
  ],
);

function handleGroupSearch(value: string) {
  void searchGroups(value);
}

function handleGroupChange(value: unknown) {
  if (value === '__create_group__') {
    const text = groupSearchText.value.trim();
    if (text) {
      markPendingGroup(text);
      form.value.belongGroup = text;
    }
    return;
  }
  selectGroup(typeof value === 'string' ? value : undefined);
}

function handleGroupKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter') return;
  const value = (e.target as HTMLInputElement).value?.trim();
  if (!value) return;
  if (groupOptions.value.some((g) => g.groupName === value)) return;
  e.preventDefault();
  markPendingGroup(value);
  form.value.belongGroup = value;
}

function handleDeptSearch(value: string) {
  void searchDepts(value);
}

function handleDeptChange(value: unknown) {
  if (value === '__create_dept__') {
    const text = deptSearchText.value.trim();
    if (text) {
      markPendingDept(text);
      form.value.dept = text;
    }
    return;
  }
  markPendingDept(undefined);
  deptSearchText.value = typeof value === 'string' ? value : '';
  if (typeof value !== 'string') {
    form.value.dept = '';
  }
}

function handleDeptKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter') return;
  const value = (e.target as HTMLInputElement).value?.trim();
  if (!value) return;
  if (!selectedGroupId.value && !pendingGroupName.value) return;
  if (deptOptions.value.some((d) => d.deptName === value)) return;
  e.preventDefault();
  markPendingDept(value);
  form.value.dept = value;
}

const fileList = ref<UploadFile[]>([]);

function open() {
  // 重置表单
  form.value = createDefaultForm(ownerId);
  fileList.value = [];
  activeTab.value = 'form';
  selectGroup(undefined);
  clearPending();
  void searchGroups('');
  innerOpen.value = true;
}

function close() {
  innerOpen.value = false;
}

async function submit() {
  if (activeTab.value === 'excel') {
    message.warning('请切换到表单创建标签页进行创建');
    return;
  }

  if (!(await validateForm(formRef.value))) {
    return;
  }

  if (!ownerId) {
    message.error('未找到管理员ID，请先登录');
    return;
  }

  const pendingItems = [pendingGroupName.value, pendingDeptName.value].filter(
    (v): v is string => !!v,
  );
  if (pendingItems.length > 0) {
    try {
      await new Promise<void>((resolve, reject) => {
        AModal.confirm({
          title: '确认创建主数据并提交',
          content: `将同时创建${pendingItems.map((v) => `"${v}"`).join('、')}，确认后提交客户。`,
          okText: '确认并提交',
          cancelText: '再想想',
          onOk: () => resolve(),
          onCancel: () => reject(new Error('cancelled')),
        });
      });
    } catch {
      return; // 用户取消
    }
  }

  const ready = await commitPending();
  if (!ready) {
    return;
  }

  const payload: CompanyCreateForm = {
    ...form.value,
    ownerId,
  };

  addCompany(payload, {
    onSuccess: () => {
      close();
    },
  });
}

function handleCustomRequest(options: UploadRequestOption) {
  importCompanies(options.file as File, {
    onSuccess: () => {
      fileList.value = [];
      close();
    },
  });
}

defineExpose({ open });
</script>

<style scoped>
.create-form {
  padding: 8px 4px;
  max-height: 500px;
  overflow-y: auto;
}

:deep(.ant-form-item) {
  margin-bottom: 12px;
}

:deep(.ant-form-item-label > label) {
  font-weight: 500;
}

.excel-section {
  padding: 16px;
}

.excel-prompt {
  background-color: #f5f5f5;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.prompt-text {
  color: #666;
}

.download-link {
  display: flex;
  align-items: center;
  color: #1890ff;
  cursor: pointer;
  gap: 4px;
}

.download-link:hover {
  text-decoration: underline;
}

.excel-import {
  border: 1px dashed #d9d9d9;
  border-radius: 4px;
  padding: 16px;
}
</style>
