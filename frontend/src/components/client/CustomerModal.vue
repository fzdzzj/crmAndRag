<template>
  <!-- 新增/编辑/查看联系人弹窗 -->
  <a-modal
    v-model:open="isModalOpen"
    :title="modalTitle"
    :confirm-loading="isSubmitting"
    :ok-text="isReadonly ? '关闭' : isEditing ? '保存' : '确认'"
    :cancel-text="isReadonly ? '' : '取消'"
    @ok="isReadonly ? closeModal() : onSubmit()"
    @cancel="closeModal"
  >
    <!-- 编辑/查看模式：直接显示表单 -->
    <template v-if="isEditing || isReadonly">
      <a-form ref="formRef" :model="formModel" :rules="rules" layout="vertical">
        <a-form-item label="公司ID" name="companyId">
          <a-select
            v-model:value="formModel.companyId"
            style="width: 100%"
            placeholder="请选择公司"
            :disabled="isReadonly"
          >
            <a-select-option
              v-for="item in companies?.records ?? []"
              :key="item.id"
              :value="item.id"
              >{{ item.companyName }}</a-select-option
            >
          </a-select>
        </a-form-item>
        <a-form-item label="姓名" name="name">
          <a-input
            v-model:value="formModel.name"
            data-tour="contact-form-name"
            placeholder="请输入姓名"
            :disabled="isReadonly"
          />
        </a-form-item>
        <a-form-item label="手机号" name="mobile">
          <a-input
            v-model:value="formModel.mobile"
            placeholder="请输入手机号"
            :disabled="isReadonly"
          />
        </a-form-item>
        <a-form-item label="职位">
          <a-input
            v-model:value="formModel.position"
            placeholder="请输入职位"
            :disabled="isReadonly"
          />
        </a-form-item>
        <a-form-item label="部门">
          <a-input
            v-model:value="formModel.dept"
            placeholder="请输入部门"
            :disabled="isReadonly"
          />
        </a-form-item>
        <a-form-item label="客户关系等级">
          <a-select
            v-model:value="formModel.relationLevel"
            placeholder="请选择关系等级"
            allow-clear
            :disabled="isReadonly"
          >
            <a-select-option
              v-for="opt in relationLevelOptions"
              :key="opt.value"
              :value="opt.value"
            >
              {{ opt.label }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="性别">
          <a-radio-group v-model:value="formModel.gender" :disabled="isReadonly">
            <a-radio :value="1">男</a-radio>
            <a-radio :value="2">女</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="家庭信息">
          <div class="flex flex-col gap-3">
            <div
              v-for="group in remarkGroups"
              :key="group.type"
              class="rounded border border-gray-100 bg-gray-50 p-2"
            >
              <div class="mb-1 text-xs font-medium text-gray-500">
                {{ group.label }}
              </div>
              <div class="flex flex-col gap-1">
                <div
                  v-for="(remark, rIndex) in group.items"
                  :key="remark.remarkType === RemarkType.OWN_BIRTHDAY ? 'own' : rIndex"
                  class="flex items-center gap-2"
                >
                  <template v-if="isReadonly">
                    <span v-if="remark.remarkName" class="text-sm">{{ remark.remarkName }}：</span>
                    <span v-if="remark.remarkContent" class="text-sm">{{ remark.remarkContent }}</span>
                    <span v-if="remark.remarkDate" class="text-sm">{{ remark.remarkDate }}</span>
                    <span v-if="!remark.remarkContent && !remark.remarkDate" class="text-sm text-gray-400">-</span>
                  </template>
                  <template v-else>
                    <a-input
                      v-if="remark.remarkType === RemarkType.RELATIVE_BIRTHDAY"
                      v-model:value="remark.remarkName"
                      placeholder="亲属姓名"
                      style="width: 120px; flex-shrink: 0"
                    />
                    <a-input
                      v-if="([RemarkType.PREFERENCE, RemarkType.ADDRESS, RemarkType.CUSTOM] as number[]).includes(remark.remarkType as number)"
                      v-model:value="remark.remarkContent"
                      placeholder="内容"
                      class="flex-1"
                    />
                    <a-date-picker
                      v-if="([RemarkType.OWN_BIRTHDAY, RemarkType.RELATIVE_BIRTHDAY] as number[]).includes(remark.remarkType as number)"
                      :value="remark.remarkDate ? dayjs(remark.remarkDate) : undefined"
                      placeholder="选择日期"
                      format="YYYY-MM-DD"
                      value-format="YYYY-MM-DD"
                      style="flex: 1"
                      @change="(val: string | Dayjs | null) => (remark.remarkDate = typeof val === 'string' ? val : undefined)"
                    />
                    <a-button
                      type="text"
                      danger
                      size="small"
                      @click="removeRemarkFromGroup(group.type, rIndex)"
                    >
                      <template #icon><DeleteOutlined /></template>
                    </a-button>
                  </template>
                </div>
              </div>
              <a-button
                v-if="!isReadonly && group.canAdd"
                type="dashed"
                size="small"
                class="mt-1"
                @click="addRemarkByType(group.type)"
              >
                <template #icon><PlusOutlined /></template>
                添加{{ group.label }}
              </a-button>
            </div>
            <div class="rounded border border-gray-100 bg-gray-50 p-2">
              <div class="mb-1 text-xs font-medium text-gray-500">自定义备注</div>
              <div class="flex flex-col gap-1">
                <div
                  v-for="(remark, cIndex) in customRemarks"
                  :key="remark.id ?? cIndex"
                  class="flex items-center gap-2"
                >
                  <template v-if="isReadonly">
                    <span v-if="remark.remarkName" class="text-sm">{{ remark.remarkName }}：</span>
                    <span v-if="remark.remarkContent" class="text-sm">{{ remark.remarkContent }}</span>
                    <span v-if="!remark.remarkContent && !remark.remarkName" class="text-sm text-gray-400">-</span>
                  </template>
                  <template v-else>
                    <a-input
                      v-model:value="remark.remarkContent"
                      placeholder="请输入备注内容"
                      class="flex-1"
                    />
                    <a-button
                      type="text"
                      danger
                      size="small"
                      @click="removeCustomRemark(cIndex)"
                    >
                      <template #icon><DeleteOutlined /></template>
                    </a-button>
                  </template>
                </div>
              </div>
              <a-button
                v-if="!isReadonly"
                type="dashed"
                size="small"
                class="mt-1"
                @click="addCustomRemark"
              >
                <template #icon><PlusOutlined /></template>
                添加自定义备注
              </a-button>
            </div>
          </div>
        </a-form-item>
        <a-form-item v-if="isReadonly" label="创建人">
          <a-input
            :value="editingRecord?.creatorName || '-'"
            readonly
            :bordered="false"
          />
        </a-form-item>
      </a-form>
    </template>

    <!-- 新增模式：使用 Tab 切换 -->
    <template v-else>
      <a-tabs v-model:active-key="activeTab">
        <a-tab-pane key="manual" tab="手动输入">
          <a-form ref="formRef" :model="formModel" :rules="rules" layout="vertical">
            <a-form-item label="公司ID" name="companyId">
              <a-select
                v-model:value="formModel.companyId"
                style="width: 100%"
                placeholder="请选择公司"
              >
                <a-select-option
                  v-for="item in companies?.records ?? []"
                  :key="item.id"
                  :value="item.id"
                  >{{ item.companyName }}</a-select-option
                >
              </a-select>
            </a-form-item>
            <a-form-item label="姓名" name="name">
              <a-input
                v-model:value="formModel.name"
                placeholder="请输入姓名"
              />
            </a-form-item>
            <a-form-item label="手机号" name="mobile">
              <a-input
                v-model:value="formModel.mobile"
                placeholder="请输入手机号"
              />
            </a-form-item>
            <a-form-item label="职位">
              <a-input
                v-model:value="formModel.position"
                placeholder="请输入职位"
              />
            </a-form-item>
            <a-form-item label="部门">
              <a-input
                v-model:value="formModel.dept"
                placeholder="请输入部门"
              />
            </a-form-item>
            <a-form-item label="客户关系等级">
              <a-select
                v-model:value="formModel.relationLevel"
                placeholder="请选择关系等级"
                allow-clear
              >
                <a-select-option
                  v-for="opt in relationLevelOptions"
                  :key="opt.value"
                  :value="opt.value"
                >
                  {{ opt.label }}
                </a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item label="性别">
              <a-radio-group v-model:value="formModel.gender">
                <a-radio :value="1">男</a-radio>
                <a-radio :value="2">女</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="家庭信息">
              <div class="flex flex-col gap-3">
                <div
                  v-for="group in remarkGroups"
                  :key="group.type"
                  class="rounded border border-gray-100 bg-gray-50 p-2"
                >
                  <div class="mb-1 text-xs font-medium text-gray-500">
                    {{ group.label }}
                  </div>
                  <div class="flex flex-col gap-1">
                    <div
                      v-for="(remark, rIndex) in group.items"
                      :key="remark.remarkType === RemarkType.OWN_BIRTHDAY ? 'own' : rIndex"
                      class="flex items-center gap-2"
                    >
                      <a-input
                        v-if="remark.remarkType === RemarkType.RELATIVE_BIRTHDAY"
                        v-model:value="remark.remarkName"
                        placeholder="亲属姓名"
                        style="width: 120px; flex-shrink: 0"
                      />
                      <a-input
                        v-if="([RemarkType.PREFERENCE, RemarkType.ADDRESS] as number[]).includes(remark.remarkType as number)"
                        v-model:value="remark.remarkContent"
                        placeholder="内容"
                        class="flex-1"
                      />
                      <a-date-picker
                        v-if="([RemarkType.OWN_BIRTHDAY, RemarkType.RELATIVE_BIRTHDAY] as number[]).includes(remark.remarkType as number)"
                        :value="remark.remarkDate ? dayjs(remark.remarkDate) : undefined"
                        placeholder="选择日期"
                        format="YYYY-MM-DD"
                        value-format="YYYY-MM-DD"
                        style="flex: 1"
                        @change="(val: string | Dayjs | null) => (remark.remarkDate = typeof val === 'string' ? val : undefined)"
                      />
                      <a-button
                        type="text"
                        danger
                        size="small"
                        @click="removeRemarkFromGroup(group.type, rIndex)"
                      >
                        <template #icon><DeleteOutlined /></template>
                      </a-button>
                    </div>
                  </div>
                  <a-button
                    v-if="group.canAdd"
                    type="dashed"
                    size="small"
                    class="mt-1"
                    @click="addRemarkByType(group.type)"
                  >
                    <template #icon><PlusOutlined /></template>
                    添加{{ group.label }}
                  </a-button>
                </div>
                <div class="rounded border border-gray-100 bg-gray-50 p-2">
                  <div class="mb-1 text-xs font-medium text-gray-500">自定义备注</div>
                  <div class="flex flex-col gap-1">
                    <div
                      v-for="(remark, cIndex) in customRemarks"
                      :key="remark.id ?? cIndex"
                      class="flex items-center gap-2"
                    >
                      <a-input
                        v-model:value="remark.remarkContent"
                        placeholder="请输入备注内容"
                        class="flex-1"
                      />
                      <a-button
                        type="text"
                        danger
                        size="small"
                        @click="removeCustomRemark(cIndex)"
                      >
                        <template #icon><DeleteOutlined /></template>
                      </a-button>
                    </div>
                  </div>
                  <a-button
                    type="dashed"
                    size="small"
                    class="mt-1"
                    @click="addCustomRemark"
                  >
                    <template #icon><PlusOutlined /></template>
                    添加自定义备注
                  </a-button>
                </div>
              </div>
            </a-form-item>
          </a-form>
        </a-tab-pane>

        <a-tab-pane key="excel" tab="Excel 导入">
          <div
            class="rounded border border-dashed border-gray-200 p-4"
            style="min-height: 200px"
          >
            <div class="mb-4 flex items-center justify-between">
              <span class="text-gray-600">上传前请先按Excel模板中的格式编辑内容</span>
              <a-button
                type="link"
                :loading="isExportingTemplate"
                @click="exportContactsTemplate()"
              >
                下载模板
              </a-button>
            </div>
            <a-upload-dragger
              v-model:file-list="fileList"
              name="file"
              :max-count="1"
              :custom-request="handleCustomRequest"
            >
              <p class="text-gray-600">
                点击或拖拽文件到此区域上传
              </p>
              <p class="mt-2 text-sm text-gray-400">
                支持单个 .xlsx 或 .xls 文件上传
              </p>
            </a-upload-dragger>
          </div>
        </a-tab-pane>
      </a-tabs>
    </template>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import {
  Modal as AModal,
  Input as AInput,
  Form as AForm,
  FormItem as AFormItem,
  RadioGroup as ARadioGroup,
  Radio as ARadio,
  Select as ASelect,
  SelectOption as ASelectOption,
  UploadDragger as AUploadDragger,
  Button as AButton,
  Tabs as ATabs,
  TabPane as ATabPane,
  DatePicker as ADatePicker,
  message,
} from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import type { UploadFile } from 'ant-design-vue';
import type { UploadRequestOption } from 'ant-design-vue/es/vc-upload/interface';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';

import {
  useCreateContact,
  useUpdateContact,
  useExportContactsTemplate,
  useImportContacts,
} from '@/hooks/useContact';
import { useQueryCompanies } from '@/hooks/useCompany';
import { validateForm } from '@/utils/formValidate';
import type {
  GetContactSearchResponse,
  PostContactData,
  PutContactData,
} from '@/api/axios';
import {
  relationLevelOptions,
  RemarkType,
  remarkTypeMap,
} from '@/constants/contact/constants';

type ContactRecord = NonNullable<
  NonNullable<GetContactSearchResponse['data']>['records']
>[number];
type ContactFormModel = NonNullable<PostContactData['body']>;
type ContactUpdatePayload = NonNullable<PutContactData['body']>[number];
type ContactRemarkPayload = NonNullable<ContactFormModel['remarks']>[number];
type ContactRecordRemark = NonNullable<ContactRecord['remarks']>[number];

const { data: companies } = useQueryCompanies({ all: true });

const isModalOpen = ref(false);
const isEditing = ref(false);
const isReadonly = ref(false);
const editingId = ref<number | undefined>(undefined);
const editingRecord = ref<ContactRecord>();
const isSubmitting = ref(false);
const activeTab = ref('manual');
const formRef = ref();

const rules: Record<string, Rule[]> = {
  companyId: [{ required: true, message: '请选择公司', trigger: 'change' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  mobile: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
};

function createEmptyForm(): ContactFormModel {
  return {
    id: undefined,
    companyId: undefined,
    name: '',
    position: undefined,
    dept: undefined,
    relationLevel: undefined,
    mobile: undefined,
    gender: undefined,
    remarks: [],
  };
}

function toEditableRemark(remark: ContactRecordRemark): ContactRemarkPayload {
  return {
    id: remark.id,
    contactId: remark.contactId,
    remarkType: remark.remarkType,
    remarkContent: remark.remarkContent,
    remarkName: remark.remarkName,
    remarkDate: remark.remarkDate,
  };
}

function toEditableForm(record: ContactRecord): ContactFormModel {
  return {
    id: record.id,
    companyId: record.companyId,
    name: record.name ?? '',
    position: record.position,
    dept: record.dept,
    relationLevel: record.relationLevel,
    mobile: record.mobile,
    gender: record.gender,
    remarks: (record.remarks ?? []).map(toEditableRemark),
  };
}

function sanitizeRemarks(
  remarks: ContactFormModel['remarks'],
): ContactRemarkPayload[] | undefined {
  const sanitized = (remarks ?? [])
    .filter((remark) => remark.remarkType !== undefined)
    .map((remark) => {
      const base = {
        id: remark.id,
        contactId: remark.contactId,
        remarkType: remark.remarkType,
      };

      switch (remark.remarkType) {
        case RemarkType.PREFERENCE:
        case RemarkType.ADDRESS:
        case RemarkType.CUSTOM:
          // 后端这三类只接受 remarkContent，避免旧表单数据残留非法字段。
          return { ...base, remarkContent: remark.remarkContent };
        case RemarkType.OWN_BIRTHDAY:
          return { ...base, remarkDate: remark.remarkDate };
        case RemarkType.RELATIVE_BIRTHDAY:
          return {
            ...base,
            remarkName: remark.remarkName,
            remarkDate: remark.remarkDate,
          };
        default:
          return base;
      }
    });
  return sanitized.length > 0 ? sanitized : undefined;
}

const formModel = reactive<ContactFormModel>(createEmptyForm());

const modalTitle = computed(() => {
  if (isReadonly.value) return '查看联系人';
  return isEditing.value ? '编辑联系人' : '新增联系人';
});

const { mutate: createContact } = useCreateContact();
const { mutate: updateContact } = useUpdateContact();
const { mutate: exportContactsTemplate, isPending: isExportingTemplate } =
  useExportContactsTemplate();
const { mutate: importContacts } = useImportContacts();

interface RemarkGroup {
  type: number;
  label: string;
  items: ContactRemarkPayload[];
  canAdd: boolean;
}

const remarkGroups = computed<RemarkGroup[]>(() => {
  const remarks = formModel.remarks ?? [];
  return [
    RemarkType.PREFERENCE,
    RemarkType.ADDRESS,
    RemarkType.OWN_BIRTHDAY,
    RemarkType.RELATIVE_BIRTHDAY,
  ].map((type) => {
    const items = remarks.filter((remark) => remark.remarkType === type);
    return {
      type,
      label: remarkTypeMap[type],
      items,
      canAdd: type === RemarkType.OWN_BIRTHDAY ? items.length === 0 : true,
    };
  });
});

const customRemarks = computed(() =>
  (formModel.remarks ?? []).filter((remark) => remark.remarkType === RemarkType.CUSTOM),
);

function addRemarkByType(type: number) {
  if (!formModel.remarks) {
    formModel.remarks = [];
  }
  formModel.remarks.push({
    remarkType: type,
    remarkContent: undefined,
    remarkName: undefined,
    remarkDate: undefined,
  });
}

function addCustomRemark() {
  addRemarkByType(RemarkType.CUSTOM);
}

function removeRemarkFromGroup(type: number, indexInGroup: number) {
  const remarks = formModel.remarks ?? [];
  const typeIndices = remarks
    .map((remark, index) => (remark.remarkType === type ? index : -1))
    .filter((index) => index >= 0);
  const actualIndex = typeIndices[indexInGroup];
  if (actualIndex !== undefined) {
    remarks.splice(actualIndex, 1);
  }
}

function removeCustomRemark(index: number) {
  const remarks = formModel.remarks ?? [];
  const customIndices = remarks
    .map((remark, currentIndex) => (
      remark.remarkType === RemarkType.CUSTOM ? currentIndex : -1
    ))
    .filter((currentIndex) => currentIndex >= 0);
  const actualIndex = customIndices[index];
  if (actualIndex !== undefined) {
    remarks.splice(actualIndex, 1);
  }
}

const fileList = ref<UploadFile[]>([]);

function handleCustomRequest(options: UploadRequestOption) {
  importContacts(options.file as File, {
    onSuccess: () => {
      fileList.value = [];
    },
  });
}

function resetForm() {
  editingId.value = undefined;
  editingRecord.value = undefined;
  isReadonly.value = false;
  activeTab.value = 'manual';
  Object.assign(formModel, createEmptyForm());
}

function openCreate() {
  isEditing.value = false;
  resetForm();
  isModalOpen.value = true;
}

function openEdit(record: ContactRecord, readonly = false) {
  isEditing.value = true;
  isReadonly.value = readonly;
  editingId.value = record.id;
  editingRecord.value = record;
  Object.assign(formModel, toEditableForm(record));
  isModalOpen.value = true;
}

function openView(record: ContactRecord) {
  openEdit(record, true);
}

function closeModal() {
  isModalOpen.value = false;
  editingRecord.value = undefined;
}

async function onSubmit() {
  if (activeTab.value === 'excel' && !isEditing.value) {
    closeModal();
    return;
  }

  isSubmitting.value = true;

  if (!(await validateForm(formRef.value))) {
    isSubmitting.value = false;
    return;
  }

  const today = dayjs().format('YYYY-MM-DD');
  const invalidBirthday = (formModel.remarks ?? []).some(
    (remark) =>
      (remark.remarkType === RemarkType.OWN_BIRTHDAY ||
        remark.remarkType === RemarkType.RELATIVE_BIRTHDAY) &&
      !!remark.remarkDate &&
      remark.remarkDate > today,
  );
  if (invalidBirthday) {
    message.error('出生日期不能晚于今天');
    isSubmitting.value = false;
    return;
  }

  if (isEditing.value) {
    const payload: ContactUpdatePayload = {
      ...formModel,
      id: editingId.value,
      companyId: formModel.companyId !== undefined ? Number(formModel.companyId) : undefined,
      gender: formModel.gender !== undefined ? Number(formModel.gender) : undefined,
      remarks: sanitizeRemarks(formModel.remarks),
    };

    updateContact([payload], {
      onSuccess: () => {
        isModalOpen.value = false;
      },
      onSettled: () => {
        isSubmitting.value = false;
      },
    });
    return;
  }

  const payload: ContactFormModel = {
    ...formModel,
    id: undefined,
    companyId: formModel.companyId !== undefined ? Number(formModel.companyId) : undefined,
    gender: formModel.gender !== undefined ? Number(formModel.gender) : undefined,
    remarks: sanitizeRemarks(formModel.remarks),
  };

  createContact(payload, {
    onSuccess: () => {
      isModalOpen.value = false;
    },
    onSettled: () => {
      isSubmitting.value = false;
    },
  });
}

defineExpose({
  openCreate,
  openEdit,
  openView,
});
</script>

<script lang="ts">
import {
  Modal,
  Input,
  Form,
  FormItem,
  RadioGroup,
  Radio,
  Select,
  SelectOption,
  UploadDragger,
  Button,
  Tabs,
  TabPane,
  DatePicker,
} from 'ant-design-vue';

export default {
  components: {
    'a-modal': Modal,
    'a-form': Form,
    'a-form-item': FormItem,
    'a-input': Input,
    'a-radio-group': RadioGroup,
    'a-radio': Radio,
    'a-select': Select,
    'a-select-option': SelectOption,
    'a-upload-dragger': UploadDragger,
    'a-button': Button,
    'a-tabs': Tabs,
    'a-tab-pane': TabPane,
    'a-date-picker': DatePicker,
  },
};
</script>
