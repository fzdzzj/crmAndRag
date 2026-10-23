<template>
  <div class="w-full">
    <Spin :spinning="isLoading" tip="Loading...">
      <Table
        data-tour="sales-lead-table"
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="leadColumns"
        :data-source="leadData?.records ?? []"
        :row-key="(record: SalesLeadListItem) => record.id!"
        :pagination="{
          current: pageNum,
          pageSize: pageSize,
          total: leadData?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex flex-wrap items-center justify-between gap-2">
            <h2>销售线索</h2>
            <div class="flex flex-wrap items-center gap-2">
              <Input.Search
                v-model:value="nameFilter"
                data-tour="sales-lead-name-filter"
                allow-clear
                placeholder="按姓名搜索"
                style="width: 160px"
                @search="applyFilter"
              />
              <Input.Search
                v-model:value="companyFilter"
                allow-clear
                placeholder="按公司搜索"
                style="width: 180px"
                @search="applyFilter"
              />
              <Select
                v-model:value="statusFilter"
                allow-clear
                placeholder="状态筛选"
                style="width: 140px"
                :options="statusOptions"
                @change="applyFilter"
              />
              <Button @click="handleResetFilter">重置</Button>
              <Button :loading="isRefetching" @click="refetch()">刷新</Button>
              <Permission :endpoint="'POST /sales/lead'">
                <Button type="primary" data-tour="sales-lead-create" @click="openCreate">
                  录入线索
                </Button>
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="statusColor((record as SalesLeadListItem).status)">
              {{ statusText((record as SalesLeadListItem).status) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <span class="inline-flex items-center gap-2">
              <Permission :endpoint="'GET /sales/lead/{id}'">
                <Popover content="查看" placement="top">
                  <EyeOutlined
                    class="cursor-pointer text-blue-500 hover:text-blue-700"
                    @click="handleView(record as SalesLeadListItem)"
                  />
                </Popover>
              </Permission>
              <Permission
                :endpoint="'PUT /sales/lead'"
              >
                <Popover content="跟进修改" placement="top">
                  <EditOutlined
                    class="cursor-pointer text-green-500 hover:text-green-700"
                    @click="openEdit(record as SalesLeadListItem)"
                  />
                </Popover>
              </Permission>
              <Permission
                :endpoint="'PUT /sales/lead'"
              >
                <Popover content="无效关闭" placement="top">
                  <StopOutlined
                    class="cursor-pointer text-orange-500 hover:text-orange-700"
                    @click="handleCloseInvalid(record as SalesLeadListItem)"
                  />
                </Popover>
              </Permission>
              <Permission :endpoint="'POST /sales/lead/convert'">
                <Popover content="一键转客户商机" placement="top">
                  <SwapRightOutlined
                    class="cursor-pointer text-purple-500 hover:text-purple-700"
                    @click="openConvert(record as SalesLeadListItem)"
                  />
                </Popover>
              </Permission>
              <Permission
                :endpoint="'DELETE /sales/lead/{id}'"
              >
                <Popover content="删除" placement="top">
                  <DeleteOutlined
                    class="cursor-pointer text-red-500 hover:text-red-700"
                    @click="handleDelete(record as SalesLeadListItem)"
                  />
                </Popover>
              </Permission>
            </span>
          </template>
        </template>
      </Table>
    </Spin>

    <!-- 快速录入 -->
    <Modal
      v-model:open="createVisible"
      title="录入销售线索"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="createLoading"
      @ok="confirmCreate"
    >
      <Form layout="vertical">
        <Form.Item label="联系人姓名" required>
          <Input v-model:value="createForm.name" placeholder="请输入姓名" />
        </Form.Item>
        <Form.Item label="公司名称" required>
          <Input v-model:value="createForm.companyName" placeholder="请输入公司名称" />
        </Form.Item>
        <Form.Item label="电话">
          <Input v-model:value="createForm.phone" placeholder="联系电话（选填）" />
        </Form.Item>
        <Form.Item label="邮箱">
          <Input v-model:value="createForm.email" placeholder="联系邮箱（选填）" />
        </Form.Item>
        <Form.Item label="线索来源">
          <Select v-model:value="createForm.source" allow-clear placeholder="选择来源" :options="sourceOptions" />
        </Form.Item>
        <Form.Item label="负责人（默认本人）">
          <Select
            v-model:value="createForm.ownerId"
            allow-clear
            show-search
            option-filter-prop="label"
            placeholder="选择负责人"
          >
            <SelectOption v-for="u in userOptions" :key="u.id" :value="u.id" :label="u.realName || `用户${u.id}`" />
          </Select>
        </Form.Item>
        <Form.Item label="备注">
          <Input.TextArea v-model:value="createForm.remark" :rows="2" placeholder="备注（选填）" />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 跟进修改 -->
    <Modal
      v-model:open="editVisible"
      title="跟进修改线索"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="editLoading"
      @ok="confirmEdit"
    >
      <Form layout="vertical">
        <Form.Item label="联系人姓名">
          <Input v-model:value="editForm.name" />
        </Form.Item>
        <Form.Item label="公司名称">
          <Input v-model:value="editForm.companyName" />
        </Form.Item>
        <Form.Item label="电话">
          <Input v-model:value="editForm.phone" />
        </Form.Item>
        <Form.Item label="邮箱">
          <Input v-model:value="editForm.email" />
        </Form.Item>
        <Form.Item label="线索状态">
          <Select v-model:value="editForm.status" :options="statusOptions" />
        </Form.Item>
        <Form.Item label="线索来源">
          <Select v-model:value="editForm.source" allow-clear :options="sourceOptions" />
        </Form.Item>
        <Form.Item label="备注">
          <Input.TextArea v-model:value="editForm.remark" :rows="2" />
        </Form.Item>
      </Form>
    </Modal>

    <!-- 一键转化 -->
    <Modal
      v-model:open="convertVisible"
      title="一键转化销售线索"
      ok-text="确认转化"
      cancel-text="取消"
      :confirm-loading="convertLoading"
      @ok="confirmConvert"
    >
      <Alert
        v-if="convertTarget"
        class="mb-3"
        type="info"
        show-icon
        :message="`将「${convertTarget.name || '此线索'}」转为客户公司（${convertTarget.companyName || ''}）及首要联系人`"
      />
      <Form layout="vertical">
        <Form.Item label="是否创建销售商机">
          <Switch v-model:checked="convertForm.createOpportunity" />
        </Form.Item>
        <template v-if="convertForm.createOpportunity">
          <Form.Item label="商机名称">
            <Input v-model:value="convertForm.opportunityName" placeholder="留空则使用公司名生成" />
          </Form.Item>
          <Form.Item label="预估金额">
            <InputNumber
              v-model:value="convertForm.estimatedAmount"
              class="w-full"
              :min="0"
              :precision="2"
              placeholder="预估商机金额（选填）"
            />
          </Form.Item>
        </template>
      </Form>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import Permission from '@/components/common/Permission.vue';
import { computed, ref } from 'vue';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popover,
  Select,
  Spin,
  Switch,
  Table,
  Tag,
  message,
} from 'ant-design-vue';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  StopOutlined,
  SwapRightOutlined,
} from '@ant-design/icons-vue';
import apiClient from '@/api/apiClient.ts';
import { getUserOptions } from '@/api/axios';
import {
  useQuerySalesLead,
  useCreateSalesLead,
  useUpdateSalesLead,
  useDeleteSalesLead,
  useConvertSalesLead,
} from '@/hooks/useSalesLead';

const SelectOption = Select.Option;

definePage({
  name: 'salesLeadList',
});

interface SalesLeadListItem {
  id?: number;
  name?: string;
  companyName?: string;
  phone?: string;
  email?: string;
  source?: string;
  status?: number;
  ownerId?: number;
  ownerName?: string;
  convertedCompanyId?: number;
  convertedContactId?: number;
  convertedOpportunityId?: number;
  convertedTime?: string;
  remark?: string;
  createTime?: string;
}

const statusOptions = [
  { value: 0, label: '待跟进' },
  { value: 1, label: '跟进中' },
  { value: 2, label: '已转化' },
  { value: 3, label: '已关闭无效' },
];

const sourceOptions = [
  { value: '展会', label: '展会' },
  { value: '官网留资', label: '官网留资' },
  { value: '电话开拓', label: '电话开拓' },
  { value: '转介绍', label: '转介绍' },
  { value: '广告投放', label: '广告投放' },
  { value: '其他', label: '其他' },
];

function statusText(status?: number): string {
  return statusOptions.find((s) => s.value === status)?.label ?? '未知';
}

function statusColor(status?: number): string {
  switch (status) {
    case 0:
      return 'blue';
    case 1:
      return 'gold';
    case 2:
      return 'green';
    case 3:
      return 'default';
    default:
      return 'default';
  }
}

const leadColumns = computed(() => [
  { title: '姓名', dataIndex: 'name', key: 'name' },
  { title: '公司名称', dataIndex: 'companyName', key: 'companyName' },
  { title: '联系方式', dataIndex: 'phone', key: 'phone' },
  { title: '来源', dataIndex: 'source', key: 'source' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '负责人', dataIndex: 'ownerName', key: 'ownerName' },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime' },
  { title: '操作', key: 'action', width: 220 },
]);

const nameFilter = ref('');
const companyFilter = ref('');
const statusFilter = ref<number | undefined>(undefined);
const {
  pageNum,
  pageSize,
  data: leadData,
  isLoading,
  isRefetching,
  refetch,
  setFilters,
  reset,
} = useQuerySalesLead();

function applyFilter() {
  setFilters({
    name: nameFilter.value || undefined,
    companyName: companyFilter.value || undefined,
    status: statusFilter.value,
  });
}

function handleResetFilter() {
  nameFilter.value = '';
  companyFilter.value = '';
  statusFilter.value = undefined;
  reset();
}

const handleTableChange = (pagination: { current?: number; pageSize?: number }) => {
  if (pagination.current !== undefined) {
    pageNum.value = pagination.current;
  }
  if (pagination.pageSize !== undefined) {
    pageSize.value = pagination.pageSize;
  }
};

// 负责人下拉（可选，加载失败不阻塞）
const userOptions = ref<{ id: number; realName?: string }[]>([]);
getUserOptions({ client: apiClient })
  .then((res) => {
    const data = res.data?.data;
    userOptions.value = Array.isArray(data) ? (data as { id: number; realName?: string }[]) : [];
  })
  .catch(() => {
    // 负责人下拉加载失败不阻断页面主流程
  });

// 录入
const createVisible = ref(false);
const createLoading = ref(false);
const createForm = ref<{
  name?: string;
  companyName?: string;
  phone?: string;
  email?: string;
  source?: string;
  ownerId?: number;
  remark?: string;
}>({});
const { mutateAsync: createLead } = useCreateSalesLead();
function openCreate() {
  createForm.value = {};
  createVisible.value = true;
}
async function confirmCreate() {
  if (!createForm.value.name || !createForm.value.companyName) {
    message.warning('姓名与公司名称必填');
    return;
  }
  createLoading.value = true;
  try {
    await createLead({ ...createForm.value });
    createVisible.value = false;
  } finally {
    createLoading.value = false;
  }
}

// 编辑
const editVisible = ref(false);
const editLoading = ref(false);
const editForm = ref<{
  id?: number;
  name?: string;
  companyName?: string;
  phone?: string;
  email?: string;
  source?: string;
  status?: number;
  remark?: string;
}>({});
const { mutateAsync: updateLead } = useUpdateSalesLead();
function openEdit(record: SalesLeadListItem) {
  editForm.value = {
    id: record.id,
    name: record.name,
    companyName: record.companyName,
    phone: record.phone,
    email: record.email,
    source: record.source,
    status: record.status,
    remark: record.remark,
  };
  editVisible.value = true;
}
async function confirmEdit() {
  if (!editForm.value.id) {
    return;
  }
  editLoading.value = true;
  try {
    await updateLead({ ...editForm.value });
    editVisible.value = false;
  } finally {
    editLoading.value = false;
  }
}

// 无效关闭
function handleCloseInvalid(record: SalesLeadListItem) {
  if (!record.id) {
    return;
  }
  Modal.confirm({
    title: '确认关闭为无效',
    content: `将线索「${record.name || record.id}」标记为已关闭无效，此操作会暂停后续跟进。`,
    okText: '确认',
    cancelText: '取消',
    onOk: async () => {
      await updateLead({ id: record.id, status: 3 });
    },
  });
}

// 一键转化
const convertVisible = ref(false);
const convertLoading = ref(false);
const convertTarget = ref<SalesLeadListItem | null>(null);
const convertForm = ref<{
  createOpportunity?: boolean;
  opportunityName?: string;
  estimatedAmount?: number;
}>({});
const { mutateAsync: convertLead } = useConvertSalesLead();
function openConvert(record: SalesLeadListItem) {
  convertTarget.value = record;
  convertForm.value = { createOpportunity: false, opportunityName: '', estimatedAmount: undefined };
  convertVisible.value = true;
}
async function confirmConvert() {
  if (!convertTarget.value?.id) {
    return;
  }
  convertLoading.value = true;
  try {
    await convertLead({
      leadId: convertTarget.value.id,
      createOpportunity: convertForm.value.createOpportunity ?? false,
      opportunityName: convertForm.value.opportunityName || undefined,
      estimatedAmount: convertForm.value.estimatedAmount,
    });
    convertVisible.value = false;
  } finally {
    convertLoading.value = false;
  }
}

// 查看
function handleView(record: SalesLeadListItem) {
  message.info(`线索：${record.name || ''}（${record.companyName || ''}）`);
}

// 删除
const { mutateAsync: deleteLeadById } = useDeleteSalesLead();
function handleDelete(record: SalesLeadListItem) {
  if (!record.id) {
    return;
  }
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除线索「${record.name || record.id}」吗？`,
    okText: '确认',
    cancelText: '取消',
    onOk: async () => {
      await deleteLeadById(record.id!);
    },
  });
}
</script>