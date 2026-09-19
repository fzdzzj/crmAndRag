<template>
  <a-modal
    v-model:open="innerOpen"
    :title="isReadonly ? '查看客户' : '编辑客户'"
    :confirm-loading="isUpdating"
    :ok-text="isReadonly ? '关闭' : '保存'"
    :cancel-text="isReadonly ? '' : '取消'"
    :closable="true"
    width="800px"
    @ok="isReadonly ? close() : submit()"
    @cancel="close"
  >
    <a-form
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 18 }"
      layout="horizontal"
      class="edit-form"
    >
      <a-form-item label="公司名称">
        <a-input
          v-model:value="form.companyName"
          placeholder="请输入公司名称"
          :disabled="isReadonly"
        />
      </a-form-item>
      <a-form-item label="行业">
        <a-input
          v-model:value="form.industry"
          placeholder="请输入行业"
          :disabled="isReadonly"
        />
      </a-form-item>
      <a-form-item label="客户属性">
        <a-select
          v-model:value="form.customerType"
          placeholder="请选择客户属性"
          allow-clear
          :disabled="isReadonly"
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
          :disabled="isReadonly"
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
          :placeholder="isReadonly ? '' : (selectedGroupId || pendingGroupName ? '搜索或输入部门名称，可选“创建”选项' : '请先选择归属集团')"
          allow-clear
          :disabled="isReadonly || (!selectedGroupId && !pendingGroupName)"
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
          :disabled="isReadonly"
        />
      </a-form-item>
      <a-form-item label="简介">
        <a-input
          v-model:value="form.description"
          placeholder="请输入公司简介"
          :disabled="isReadonly"
        />
      </a-form-item>
      <a-form-item label="公司等级">
        <a-rate
          v-if="isReadonly"
          :value="form.grade"
          :count="9"
          disabled
        />
        <a-rate
          v-else
          v-model:value="form.grade"
          :count="9"
        />
      </a-form-item>
    </a-form>
    <template v-if="isReadonly">
      <a-divider>联系人</a-divider>
      <template v-if="isContactsLoading">
        <div style="text-align: center; padding: 24px 0">
          <a-spin tip="Loading..." />
        </div>
      </template>
      <a-table
        v-else
        :scroll="{ x: 'max-content' }"
        :columns="contactColumns"
        :data-source="contacts"
        :pagination="false"
        row-key="id"
        size="small"
      />
    </template>
  </a-modal>
  <CustomerModal ref="contactModalRef" />
</template>

<script setup lang="ts">
import { ref, computed, h } from 'vue';
import {
  Modal as AModal,
  Input as AInput,
  Form as AForm,
  message,
  FormItem as AFormItem,
  Divider as ADivider,
  Table as ATable,
  Spin as ASpin,
  Select as ASelect,
  Rate as ARate,
} from 'ant-design-vue';
import { useUpdateCompany, useCompanyContacts, useGroupDeptMaster } from '@/hooks/useCompany.ts';
import type { GetContactGetContactByCompanyIdResponse } from '@/api/axios';
import CustomerModal from '@/components/client/CustomerModal.vue';
interface CompanyListItem {
  id: number;
  companyName: string;
  industry: string;
  customerType: string;
  belongGroup: string;
  dept: string;
  address: string;
  description: string;
  grade: number;
  creatorName: string;
  ownerName: string;
  createTime: string | number[];
  updateTime: string | number[];
  isDeleted: boolean;
  creatorId: number;
  ownerId: number;
}

type EditForm = {
  companyName: string;
  industry: string;
  customerType?: string;
  belongGroup?: string;
  dept: string;
  address: string;
  description: string;
  grade: number;
};

const innerOpen = ref(false);
const editingId = ref<number | null>(null);
const isReadonly = ref(false);
const currentCompanyId = ref<number | undefined>(undefined);
const contactModalRef = ref();

// 联系人相关
const {
  data: contactsData,
  isLoading: isContactsLoading,
  refetch: refetchContacts,
} = useCompanyContacts(currentCompanyId);

const contacts = computed(() => contactsData?.value ?? []);

type CompanyContact = NonNullable<
  NonNullable<GetContactGetContactByCompanyIdResponse['data']>[number]
>;

const contactColumns = [
  {
    title: '姓名',
    dataIndex: 'name',
    key: 'name',
    customRender: ({ record }: { record: CompanyContact }) =>
      h(
        'a',
        {
          class:
            'cursor-pointer text-blue-600 hover:text-blue-800 hover:underline',
          onClick: () => contactModalRef.value?.openView(record),
        },
        record.name || '-',
      ),
  },
  { title: '职位', dataIndex: 'position', key: 'position' },
  { title: '手机', dataIndex: 'mobile', key: 'mobile' },
  { title: '备注', dataIndex: 'remark', key: 'remark' },
];
const form = ref<EditForm>({
  companyName: '',
  industry: '',
  customerType: undefined,
  belongGroup: undefined,
  dept: '',
  address: '',
  description: '',
  grade: 0,
});

const { mutate: updateCompany, isPending: isUpdating } = useUpdateCompany();

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
  syncGroupByName,
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

function getErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function open(company: CompanyListItem, readonly = false) {
  editingId.value = company.id;
  isReadonly.value = readonly;
  currentCompanyId.value = readonly ? company.id : undefined;
  form.value = {
    companyName: company.companyName,
    industry: company.industry,
    customerType: company.customerType ?? undefined,
    belongGroup: company.belongGroup ?? undefined,
    dept: company.dept ?? '',
    address: company.address,
    description: company.description,
    grade: company.grade ?? 0,
  };
  // 回填：按集团名匹配主数据并加载该集团下部门
  if (company.belongGroup) {
    void syncGroupByName(company.belongGroup);
  }
  innerOpen.value = true;
  if (readonly) {
    void refetchContacts().catch((error: unknown) => {
      message.error(getErrorMessage(error, '加载联系人失败'));
    });
  }
}

function openView(company: CompanyListItem) {
  open(company, true);
}

function close() {
  innerOpen.value = false;
  editingId.value = null;
  isReadonly.value = false;
  currentCompanyId.value = undefined;
  selectGroup(undefined);
  clearPending();
}

async function submit() {
  if (!form.value.companyName) {
    message.error('公司名称不能为空');
    return;
  }
  const id = editingId.value;
  if (!id) {
    message.error('未选择要编辑的公司');
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

  const payload = [{ ...form.value, id }] as unknown as Parameters<
    typeof updateCompany
  >[0];
  updateCompany(payload, {
    onSuccess: () => {
      close();
    },
  });
}

defineExpose({ open, openView });
</script>

<style scoped>
.edit-form {
  padding: 8px 4px;
}
:deep(.ant-form-item) {
  margin-bottom: 12px;
}
:deep(.ant-form-item-label > label) {
  font-weight: 500;
}
</style>
