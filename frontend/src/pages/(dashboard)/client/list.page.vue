<template>
  <div class="w-full">
    <!-- Table -->
    <Spin
      v-if="!churnMode && !groupByGrade && !groupByCompany"
      :spinning="isLoading"
      tip="Loading..."
    >
      <Table
        data-tour="client-table"
        :scroll="{ x: 'max-content' }"
        size="small"
:columns="companyColumns" :data-source="companies?.records ?? []"
        :row-key="(record: CompanyListItem) => record.id"
        :row-selection="tagManageMode ? rowSelectionEnabled : undefined" :pagination="{
          current: current,
          pageSize: pageSize,
          total: total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }" @change="handleTableChange">
        <template #title>
          <div class="flex items-center justify-between">
            <h2>客户公司</h2>
            <div class="flex flex-wrap gap-2">
              <Button
v-if="tagManageMode && isDeleted" type="primary" danger :disabled="selectedRowKeys.length === 0"
                :loading="isHardDeleting" @click="hardDelete">
                批量删除
              </Button>
              <Button
v-if="tagManageMode && !isDeleted" type="primary" danger :disabled="selectedRowKeys.length === 0"
                :loading="isSoftDeleting" @click="softDelete">
                批量移动到回收站
              </Button>
              <Button
v-if="tagManageMode && isDeleted" type="primary" :disabled="selectedRowKeys.length === 0"
                :loading="isRestoring" @click="bulkRestore">
                批量恢复
              </Button>
              <Button :loading="isRefetching" @click="refetch()"> 刷新 </Button>
              <Button
                :type="churnMode ? 'primary' : 'default'"
                data-tour="client-churn-filter"
                @click="toggleChurnMode"
              >
                {{ churnMode ? '返回客户列表' : '流失风险' }}
              </Button>
              <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                <Button
                  type="primary"
                  :loading="triggerChurnPending"
                  data-tour="client-churn-scan"
                  @click="handleTriggerChurnScan"
                  >生成流失预警待办</Button
                >
              </Permission>
              <Button data-tour="client-recycle" @click="handleToggleDeleted()">
                {{ isDeleted ? '返回客户列表' : '回收站' }}
              </Button>
              <Button data-tour="client-batch" @click="toggleTagManage">
                {{ tagManageMode ? '取消批量' : '批量管理' }}
              </Button>
              <Button data-tour="client-export" :loading="isExporting" @click="exportCompanies()">
                导出列表
              </Button>
              <span class="inline-flex gap-2" data-tour="client-group">
                <Button @click="groupByGrade = !groupByGrade">
                  {{ groupByGrade ? '普通列表' : '按等级分组' }}
                </Button>
                <Button @click="groupByCompany = !groupByCompany">
                  {{ groupByCompany ? '普通列表' : '按公司分组' }}
                </Button>
              </span>
              <Permission :allow="['customer:CUSTOMER_ADD_COMPANY']">
                <Button type="primary" data-tour="client-create" @click="openCreateModal">新建客户</Button>
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <template v-if="!isDeleted">
              <Permission :allow="['customer:CUSTOMER_QUERY_COMPANY']">
                <Popover content="查看" placement="top">
                  <EyeOutlined
class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                    @click="editModalRef?.openView(record as CompanyListItem)" />
                </Popover>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                <Popover content="编辑" placement="top">
                  <EditOutlined
class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                    @click="openEditModal(record as CompanyListItem)" />
                </Popover>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_DELETE_CUSTOMER_COMPANY']">
                <Popover content="删除" placement="top">
                  <DeleteOutlined
class="cursor-pointer text-red-500 hover:text-red-700"
                    @click="handleDelete(record as CompanyListItem)" />
                </Popover>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                <Popover content="退回公海" placement="top">
                  <LogoutOutlined
class="cursor-pointer text-purple-500 hover:text-purple-700"
                    @click="openRelease(record as CompanyListItem)" />
                </Popover>
              </Permission>
            </template>
            <template v-else>
              <Permission :allow="['customer:CUSTOMER_RECOVER_CUSTOMER_COMPANY']">
                <Popover content="恢复" placement="top">
                  <RetweetOutlined
class="cursor-pointer text-orange-500 hover:text-orange-700"
                    @click="handleRestore(record as CompanyListItem)" />
                </Popover>
              </Permission>
            </template>
          </template>
        </template>
      </Table>
    </Spin>

    <template v-if="groupByGrade">
      <div class="mb-4 flex items-center justify-between">
        <h2>客户公司（按等级分组）</h2>
        <Button @click="groupByGrade = false">返回列表</Button>
      </div>
      <ACollapse v-model:active-key="activeGradeKeys">
        <ACollapsePanel v-for="group in computedGroups" :key="String(group.grade)">
          <template #header>
            <span class="inline-flex items-center gap-1">
              <span v-for="i in 9" :key="i">
                <StarFilled v-if="i <= group.grade" style="color: #faad14; font-size: 12px" />
                <StarOutlined v-else style="color: #d9d9d9; font-size: 12px" />
              </span>
              <span class="ml-1 text-gray-500">({{ group.items.length }}家)</span>
            </span>
          </template>
          <Table
            :scroll="{ x: 'max-content' }"
            :columns="companyColumns"
            :data-source="group.items"
            :row-key="(record: CompanyListItem) => record.id"
            :pagination="false"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'action'">
                <template v-if="!isDeleted">
                  <Permission :allow="['customer:CUSTOMER_QUERY_COMPANY']">
                    <Popover content="查看" placement="top">
                      <EyeOutlined class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700" @click="editModalRef?.openView(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                  <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                    <Popover content="编辑" placement="top">
                      <EditOutlined class="mr-2 cursor-pointer text-green-500 hover:text-green-700" @click="openEditModal(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                  <Permission :allow="['customer:CUSTOMER_DELETE_CUSTOMER_COMPANY']">
                    <Popover content="删除" placement="top">
                      <DeleteOutlined class="cursor-pointer text-red-500 hover:text-red-700" @click="handleDelete(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                  <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                    <Popover content="退回公海" placement="top">
                      <LogoutOutlined class="cursor-pointer text-purple-500 hover:text-purple-700" @click="openRelease(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                </template>
              </template>
            </template>
          </Table>
        </ACollapsePanel>
      </ACollapse>
    </template>

    <template v-if="groupByCompany">
      <div class="mb-4 flex items-center justify-between">
        <h2>客户公司（按公司分组）</h2>
        <Button @click="groupByCompany = false">返回列表</Button>
      </div>
      <div class="mb-3 max-w-sm">
        <Input
          v-model:value="companySearchText"
          placeholder="搜索公司名称"
          allow-clear
        />
      </div>
      <Spin :spinning="isAllCompaniesFetching" tip="正在加载全部客户...">
      <ACollapse v-model:active-key="activeCompanyKeys">
        <ACollapsePanel v-for="group in filteredCompanyGroups" :key="group.company">
          <template #header>
            <span class="inline-flex items-center gap-1">
              <span>{{ group.company }}</span>
              <span class="ml-1 text-gray-500">({{ group.items.length }}家)</span>
            </span>
          </template>
          <Table
            :columns="companyColumns"
            :data-source="group.items"
            :row-key="(record: CompanyListItem) => record.id"
            :pagination="false"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'action'">
                <template v-if="!isDeleted">
                  <Permission :allow="['customer:CUSTOMER_QUERY_COMPANY']">
                    <Popover content="查看" placement="top">
                      <EyeOutlined class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700" @click="editModalRef?.openView(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                  <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                    <Popover content="编辑" placement="top">
                      <EditOutlined class="mr-2 cursor-pointer text-green-500 hover:text-green-700" @click="openEditModal(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                  <Permission :allow="['customer:CUSTOMER_DELETE_CUSTOMER_COMPANY']">
                    <Popover content="删除" placement="top">
                      <DeleteOutlined class="cursor-pointer text-red-500 hover:text-red-700" @click="handleDelete(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                  <Permission :allow="['customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY']">
                    <Popover content="退回公海" placement="top">
                      <LogoutOutlined class="cursor-pointer text-purple-500 hover:text-purple-700" @click="openRelease(record as CompanyListItem)" />
                    </Popover>
                  </Permission>
                </template>
              </template>
            </template>
          </Table>
        </ACollapsePanel>
      </ACollapse>
      </Spin>
    </template>

    <template v-if="churnMode">
      <div class="mb-3 flex items-center justify-between">
        <h2>流失风险客户</h2>
        <Button :loading="churnRefetching" @click="churnRefetch()">刷新风险清单</Button>
      </div>
      <Alert
        class="mb-3"
        type="warning"
        show-icon
        message="以下是近 60 天无已完成跟进、无商业活动、无合同回款进展的私海客户；建议先走客户诊断，再用 Insight→Action 转待办或转活动。"
      />
      <Table
        data-tour="client-churn-table"
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="churnColumns"
        :data-source="churnRisks"
        :loading="churnLoading"
        :row-key="(record: { companyId?: number }) => record.companyId!"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'daysSinceLastInteraction'">
            <span class="text-red-500">{{ (record as ChurnWarningRow).daysSinceLastInteraction }}</span>
          </template>
        </template>
      </Table>
    </template>

    <CompanyEditModal ref="editModalRef" />
    <CompanyCreateModal ref="createModalRef" />
    <CustomerModal ref="contactModalRef" />

    <Modal
      v-model:open="releaseVisible"
      title="退回公海"
      ok-text="确认退回"
      cancel-text="取消"
      :confirm-loading="releaseLoading"
      @ok="confirmRelease"
    >
      <Alert
        v-if="releaseTarget"
        class="mb-3"
        type="warning"
        show-icon
        :message="`确定将「${releaseTarget.companyName || releaseTarget.id}」退回公海池？退回后可由其他销售认领。`"
      />
      <Input.TextArea
        v-model:value="releaseReason"
        class="mt-2"
        :rows="2"
        placeholder="请输入退回原因（如：无效客户 / 客户不匹配 / 长期未跟进）"
      />
    </Modal>
  </div>
</template>

<script lang="ts" setup>
import Permission from '@/components/common/Permission.vue';
import { computed, ref, watch, h } from 'vue';
import { useRoute } from 'vue-router';
import {
  Button,
  Collapse,
  Input,
  Alert,
  message,
  Modal,
  Popover,
  Spin,
  Table,
  Tooltip,
} from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
const ACollapse = Collapse;
const ACollapsePanel = Collapse.Panel;
import {
  useDeleteCompanies,
  useQueryCompaniesWithURLSearchParamsAsync,
  useRestoreCompanies,
  useHardDeleteCompanies,
  useExportCompanies,
  useBatchDeleteCompanies,
} from '@/hooks/useCompany.ts';
import { useReleasePoolCustomer } from '@/hooks/useCustomerPool.ts';
import {
  useQueryCustomerChurnWarning,
  useTriggerCustomerChurnWarning,
} from '@/hooks/useCustomerChurnWarning.ts';
import {
  getContactGetContactByCompanyId,
  getCompanyCustom,
  type GetContactGetContactByCompanyIdResponse,
  type GetCustomerChurnWarningListResponse,
} from '@/api/axios';
import apiClient from '@/api/apiClient.ts';
import { useQuery } from '@tanstack/vue-query';
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
type CompanyTablePagination = Parameters<
  NonNullable<TableProps<CompanyListItem>['onChange']>
>[0];
type CompanyRowSelection = NonNullable<TableProps<CompanyListItem>['rowSelection']>;

type ChurnWarningRow = NonNullable<
  NonNullable<GetCustomerChurnWarningListResponse['data']>[number]
>;

const churnColumns = [
  { title: '客户公司', dataIndex: 'companyName', key: 'companyName' },
  { title: '负责人ID', dataIndex: 'ownerId', key: 'ownerId' },
  { title: '无互动天数', dataIndex: 'daysSinceLastInteraction', key: 'daysSinceLastInteraction' },
  { title: '最后一笔互动', dataIndex: 'lastInteractionTime', key: 'lastInteractionTime' },
  { title: '判定窗口(天)', dataIndex: 'windowDays', key: 'windowDays' },
];
import CompanyEditModal from '@/components/client/CompanyEditModal.vue';
import CompanyCreateModal from '@/components/client/CompanyCreateModal.vue';
import CustomerModal from '@/components/client/CustomerModal.vue';
import {
  EditOutlined,
  DeleteOutlined,
  RetweetOutlined,
  EyeOutlined,
  StarFilled,
  StarOutlined,
  LogoutOutlined,
} from '@ant-design/icons-vue';
import { useBuildFilterColumn } from '@/utils/buildFilterColumn';

definePage({
  name: 'clientList',
});

const editModalRef = ref();
const createModalRef = ref();
const contactModalRef = ref();
const route = useRoute();
const tagManageMode = ref(false);
const selectedRowKeys = ref<number[]>([]);
const groupByGrade = ref(false);
const activeGradeKeys = ref<string[]>([]);
const groupByCompany = ref(false);
const activeCompanyKeys = ref<string[]>([]);
const companySearchText = ref('');

/** 流失风险筛选模式（add-customer-churn-warning-and-reactivation 任务 3.1） */
const churnMode = ref(false);
const {
  data: churnRiskData,
  isLoading: churnLoading,
  isRefetching: churnRefetching,
  refetch: churnRefetch,
} = useQueryCustomerChurnWarning({ limit: 200 });
const { isPending: triggerChurnPending, mutate: runTriggerChurnScan } =
  useTriggerCustomerChurnWarning();
const churnRisks = computed<ChurnWarningRow[]>(() => churnRiskData.value ?? []);

function toggleChurnMode() {
  churnMode.value = !churnMode.value;
  if (churnMode.value) {
    groupByGrade.value = false;
    groupByCompany.value = false;
    tagManageMode.value = false;
    void churnRefetch();
  }
}

function handleTriggerChurnScan() {
  Modal.confirm({
    title: '生成流失预警待办',
    content:
      '将为近 60 天无任何互动（无已完成跟进 / 无商业活动 / 无合同回款进展）的私海客户负责人生成高优先待办（已存在进行中流失预警待办的将自动跳过），是否继续？',
    okText: '生成',
    cancelText: '取消',
    onOk: () => runTriggerChurnScan(),
  });
}

type CompanyContact = NonNullable<
  NonNullable<GetContactGetContactByCompanyIdResponse['data']>[number]
>;

/** 按公司 ID 缓存联系人（id -> 联系人列表），避免修改 vue-query 的只读数据 */
const contactsMap = ref<Record<number, CompanyContact[]>>({});

const {
  current,
  pageSize,
  isDeleted,
  data: companies,
  isLoading,
  refetch,
  isRefetching,
  filters,
  setFilters,
  toggleDeleted,
} = useQueryCompaniesWithURLSearchParamsAsync();

let openedAssistCompanyId: number | undefined;
watch(
  [() => route.query['filters.id'], companies], ([rawId, companyData]) => {
    const companyId = Number(rawId);
    if (!Number.isFinite(companyId) || companyId <= 0 || companyId === openedAssistCompanyId) {
      return;
    }
    const record = companyData?.records?.find((item) => item.id === companyId);
    if (record) {
      openedAssistCompanyId = companyId;
      editModalRef.value?.openView(record as CompanyListItem);
    }
  },
  { immediate: true },
);

/** 按公司分组用的全量客户查询（跟随回收站状态，切换分组时才启用） */
const {
  data: allCompaniesData,
  isFetching: isAllCompaniesFetching,
} = useQuery({
  queryKey: computed(() => ['company-all-for-group', isDeleted.value]),
  enabled: groupByCompany,
  queryFn: () =>
    getCompanyCustom({
      client: apiClient,
      query: { pageNum: 1, pageSize: 100000, isDeleted: isDeleted.value },
    }),
  select: (res) => res.data?.data?.records ?? [],
});

const { buildFilterColumn } = useBuildFilterColumn({
  filters,
  setFilters,
});

const computedGroups = computed(() => {
  const records = companies?.value?.records ?? [];
  const groups: Record<number, CompanyListItem[]> = {};
  records.forEach((c) => {
    const grade = c.grade ?? 0;
    if (!groups[grade]) groups[grade] = [];
    groups[grade].push(c as CompanyListItem);
  });
  return Object.entries(groups)
    .sort(([a], [b]) => Number(b) - Number(a))
    .map(([grade, items]) => ({
      grade: Number(grade),
      items,
    }));
});

/**
 * 按公司名分组（同名公司可能有多个部门记录），展开查看各部门。
 * 分组必须基于全量客户，而不是当前页，否则分组不完整。
 */
const companyGroups = computed(() => {
  const records = allCompaniesData?.value ?? [];
  const groups: Record<string, CompanyListItem[]> = {};
  records.forEach((c) => {
    const company = (c.companyName ?? '').trim() || '未命名公司';
    const key = company;
    if (!groups[key]) groups[key] = [];
    groups[key].push(c as CompanyListItem);
  });
  return Object.entries(groups)
    .sort(([a], [b]) => a.localeCompare(b, 'zh-Hans-CN'))
    .map(([company, items]) => ({ company, items }));
});

/** 按公司名关键词过滤分组（支持搜索筛选公司） */
const filteredCompanyGroups = computed(() => {
  const keyword = companySearchText.value.trim();
  if (!keyword) {
    return companyGroups.value;
  }
  return companyGroups.value.filter((g) => g.company.includes(keyword));
});

watch(groupByGrade, (val) => {
  if (val) {
    activeGradeKeys.value = computedGroups.value.map(g => String(g.grade));
    groupByCompany.value = false;
  }
});

watch(groupByCompany, (val) => {
  if (val) {
    activeCompanyKeys.value = filteredCompanyGroups.value.map((g) => g.company);
    groupByGrade.value = false;
  }
});

// 全量数据加载完成后，若分组视图仍为空则默认全部展开
watch(allCompaniesData, (records) => {
  if (groupByCompany.value && records && records.length > 0 && activeCompanyKeys.value.length === 0) {
    activeCompanyKeys.value = filteredCompanyGroups.value.map((g) => g.company);
  }
});

watch(
  () => companies.value?.records ?? [],
  async (records) => {
    const companyIds = records
      .map((company) => company.id)
      .filter((id): id is number => typeof id === 'number');
    if (companyIds.length === 0) return;

    const settled = await Promise.allSettled(
      companyIds.map((id) =>
        getContactGetContactByCompanyId({
          client: apiClient,
          query: { id },
        }),
      ),
    );
    const next: Record<number, CompanyContact[]> = {};
    companyIds.forEach((id, index) => {
      const result = settled[index];
      if (result.status === 'fulfilled') {
        const contacts = result.value.data?.data ?? [];
        next[id] = contacts;
      }
    });
    if (Object.keys(next).length > 0) {
      contactsMap.value = { ...contactsMap.value, ...next };
    }
  },
  { immediate: true },
);

const { mutate: deleteCompanies } = useDeleteCompanies();
const { mutate: restoreCompanies, isPending: isRestoring } =
  useRestoreCompanies();
const { mutate: hardDeleteCompanies, isPending: isHardDeleting } =
  useHardDeleteCompanies();
const { mutate: exportCompanies, isPending: isExporting } =
  useExportCompanies();
const { mutate: softDeleteCompanies, isPending: isSoftDeleting } =
  useBatchDeleteCompanies();

const companyColumns = computed(() => [
  buildFilterColumn({
    title: '行业',
    dataIndex: 'industry',
    type: 'text',
    key: 'industry',
  }),
  buildFilterColumn({
    title: '名称',
    dataIndex: 'companyName',
    type: 'text',
    key: 'companyName',
  }),
  {
    title: '联系人',
    key: 'contacts',
    dataIndex: 'contacts',
    width: 200,
    customRender: ({ record }: { record: CompanyListItem }) => {
      const contacts = contactsMap.value[record.id] ?? [];
      if (contacts.length === 0) {
        return h('span', { class: 'text-gray-400' }, '-');
      }
      const renderContact = (contact: CompanyContact) =>
        h('span', { class: 'whitespace-nowrap' }, [
          h(
            'a',
            {
              class:
                'cursor-pointer text-blue-600 hover:text-blue-800 hover:underline',
              onClick: () => contactModalRef.value?.openView(contact),
            },
            contact.name || '-',
          ),
          contact.dept
            ? h(
                'span',
                { class: 'ml-1 text-xs text-gray-400' },
                `（${contact.dept}）`,
              )
            : null,
        ]);
      const visibleContacts = contacts.slice(0, 4);
      const restCount = contacts.length - visibleContacts.length;
      return h(
        Tooltip,
        {
          title: h(
            'div',
            { class: 'flex flex-col gap-1' },
            contacts.map((contact) =>
              h(
                'span',
                { class: 'whitespace-nowrap' },
                contact.dept
                  ? `${contact.name || '-'}（${contact.dept}）`
                  : (contact.name ?? '-'),
              ),
            ),
          ),
        },
        {
          default: () =>
            h('div', { class: 'flex flex-col gap-0.5' }, [
              ...visibleContacts.map((contact) =>
                h('div', { class: 'leading-5' }, renderContact(contact)),
              ),
              restCount > 0
                ? h(
                    'span',
                    { class: 'text-xs text-gray-400' },
                    `等 ${restCount} 人`,
                  )
                : null,
            ]),
        },
      );
    },
  },
  buildFilterColumn({
    title: '部门',
    dataIndex: 'dept',
    type: 'text',
    key: 'dept',
  }),
  buildFilterColumn({
    title: '级别',
    dataIndex: 'grade',
    type: 'number',
    key: 'grade',
    tooltip: '0星=潜在客户，9星=核心客户；数值越大代表客户价值越高',
    customRender: ({ text }: { text: number }) => {
      const grade = Math.min(Math.max(Number(text) || 0, 0), 9);
      const stars: ReturnType<typeof h>[] = [];
      for (let i = 1; i <= 9; i++) {
        stars.push(
          h(i <= grade ? StarFilled : StarOutlined, {
            style: { color: i <= grade ? '#faad14' : '#d9d9d9', fontSize: '12px' },
          }),
        );
      }
      return h('span', { class: 'inline-flex gap-0.5' }, stars);
    },
  }),
  buildFilterColumn({
    title: '地址',
    dataIndex: 'address',
    type: 'text',
    key: 'address',
  }),
  buildFilterColumn({
    title: '简介',
    dataIndex: 'description',
    type: 'text',
    key: 'description',
  }),
  buildFilterColumn({
    title: '所属集团',
    dataIndex: 'belongGroup',
    type: 'text',
    key: 'belongGroup',
  }),
  buildFilterColumn({
    title: '客户属性',
    dataIndex: 'customerType',
    type: 'select',
    key: 'customerType',
    selectOptions: [
      { label: '代理', value: '代理' },
      { label: '直销', value: '直销' },
    ],
  }),
  {
    title: '操作',
    key: 'action',
    dataIndex: 'action',
    width: 150,
    fixed: 'right' as const,
  },
]);

const total = computed(() => companies?.value?.total);

const openEditModal = (company: CompanyListItem) => {
  editModalRef.value?.open(company);
};

const handleDelete = (company: CompanyListItem) => {
  if (company.id === undefined) {
    return;
  }
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除客户公司"${company.companyName || company.id}"吗？删除后可在回收站中恢复。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      deleteCompanies(company.id, {
        onSuccess() {
          void refetch();
        },
      });
    },
  });
};

const handleRestore = (company: CompanyListItem) => {
  restoreCompanies([company.id], {
    onSuccess() {
      void refetch();
    },
  });
};

const releaseMutate = useReleasePoolCustomer();
const releaseVisible = ref(false);
const releaseTarget = ref<CompanyListItem | null>(null);
const releaseReason = ref<string | undefined>(undefined);
const releaseLoading = ref(false);

function openRelease(company: CompanyListItem) {
  releaseTarget.value = company;
  releaseReason.value = undefined;
  releaseVisible.value = true;
}

function confirmRelease() {
  if (!releaseTarget.value || releaseTarget.value.id === undefined) {
    message.warning('请选择要退回的客户');
    return;
  }
  releaseLoading.value = true;
  releaseMutate.mutate(
    { id: releaseTarget.value.id, reason: releaseReason.value },
    {
      onSuccess() {
        releaseVisible.value = false;
        void refetch();
      },
      onSettled() {
        releaseLoading.value = false;
      },
    },
  );
}

const handleTableChange = (pagination: CompanyTablePagination) => {
  current.value = pagination.current ?? current.value;
  pageSize.value = pagination.pageSize ?? pageSize.value;
};

function handleToggleDeleted() {
  toggleDeleted();
  current.value = 1;
}

const rowSelectionEnabled = computed<CompanyRowSelection>(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys as number[];
  },
}));

function toggleTagManage() {
  tagManageMode.value = !tagManageMode.value;
  if (!tagManageMode.value) {
    selectedRowKeys.value = [];
  }
}

function hardDelete() {
  if (selectedRowKeys.value.length === 0) {
    message.info('请选择客户');
    return;
  }
  Modal.confirm({
    title: '确认彻底删除',
    content: `确定要彻底删除选中的 ${selectedRowKeys.value.length} 个客户吗？此操作不可恢复。`,
    onOk() {
      hardDeleteCompanies(selectedRowKeys.value, {
        onSuccess() {
          selectedRowKeys.value = [];
          void refetch();
        },
      });
    },
  });
}

function softDelete() {
  if (selectedRowKeys.value.length === 0) {
    message.info('请选择客户');
    return;
  }
  softDeleteCompanies(selectedRowKeys.value, {
    onSuccess() {
      selectedRowKeys.value = [];
      void refetch();
    },
  });
}

function bulkRestore() {
  if (selectedRowKeys.value.length === 0) {
    message.info('请选择客户');
    return;
  }
  restoreCompanies(selectedRowKeys.value, {
    onSuccess() {
      selectedRowKeys.value = [];
      void refetch();
    },
  });
}

function openCreateModal() {
  createModalRef.value?.open();
}
</script>
