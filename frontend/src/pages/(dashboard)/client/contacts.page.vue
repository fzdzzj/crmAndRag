<template>
  <div class="w-full">
    <Spin :spinning="isLoading" tip="Loading...">
      <Table
        :scroll="{ x: 'max-content' }"
        size="small"
        :columns="contactColumns"
        :data-source="contactList"
        :row-key="(record: ContactRecord) => record.id ?? 0"
        :row-selection="bulkManageMode ? rowSelectionEnabled : undefined"
        :pagination="{
          current: current,
          pageSize: pageSize,
          total: total,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #title>
          <div class="flex items-center justify-between">
            <h2>联系人</h2>
            <div class="flex flex-wrap gap-2">
              <Permission :allow="['customer:CUSTOMER_DELETE_CONTACT']">
                <Button
                  v-if="bulkManageMode && !isDeleted"
                  type="primary"
                  danger
                  :disabled="selectedRowKeys.length === 0"
                  @click="onBulkSoftDelete"
                >
                  批量移入回收站
                </Button>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_RECOVER_CONTACT']">
                <Button
                  v-if="bulkManageMode && isDeleted"
                  type="primary"
                  :disabled="selectedRowKeys.length === 0"
                  @click="onBulkRestore"
                >
                  批量恢复
                </Button>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_DELETE_CONTACT']">
                <Button
                  v-if="bulkManageMode && isDeleted"
                  type="primary"
                  danger
                  :disabled="selectedRowKeys.length === 0"
                  @click="onBulkHardDelete"
                >
                  批量彻底删除
                </Button>
              </Permission>
              <Button :loading="isRefetching" @click="refetch()"> 刷新 </Button>
              <Permission :allow="['customer:CUSTOMER_EXPORT_CONTACT']">
                <Button
                  v-if="!bulkManageMode && !isDeleted"
                  :loading="isExporting"
                  @click="onExportContacts"
                >
                  导出联系人
                </Button>
              </Permission>
              <Button @click="handleToggleDeleted()">
                {{ isDeleted ? '返回联系人列表' : '回收站' }}
              </Button>
              <Button data-tour="contact-batch" @click="toggleBulkManage">
                {{ bulkManageMode ? '取消批量' : '批量管理' }}
              </Button>
              <Button @click="reset">重置筛选条件</Button>
              <Permission :allow="['customer:CUSTOMER_ADD_CONTACT']">
                <Button v-if="!isDeleted" type="primary" data-tour="contact-create" @click="openCreate">
                  新增联系人
                </Button>
              </Permission>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'remarks'">
            <template v-if="getRemarks(record as ContactRecord).length">
              <div class="flex flex-col gap-1">
                <div
                  v-for="remark in getRemarks(record as ContactRecord)"
                  :key="remark.id"
                  class="text-xs"
                >
                  <span class="text-gray-400">[{{ remark.remarkTypeDesc }}]</span>
                  <span v-if="remark.remarkContent" class="ml-1">{{ remark.remarkContent }}</span>
                  <span v-if="remark.remarkName" class="ml-1">{{ remark.remarkName }}</span>
                  <span v-if="remark.remarkDate" class="ml-1">{{ remark.remarkDate }}</span>
                </div>
              </div>
            </template>
            <span v-else class="text-gray-400">-</span>
          </template>
          <template v-if="column.key === 'action'">
            <template v-if="!isDeleted">
              <Permission :allow="['customer:CUSTOMER_QUERY_CONTACT']">
                <Popover content="查看" placement="top">
                  <EyeOutlined
                    class="mr-2 cursor-pointer text-blue-500 hover:text-blue-700"
                    @click="openView(record as ContactRecord)"
                  />
                </Popover>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_UPDATE_CONTACT']">
                <Popover content="编辑" placement="top">
                  <EditOutlined
                    data-tour="contact-edit"
                    class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                    @click="openEdit(record as ContactRecord)"
                  />
                </Popover>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_DELETE_CONTACT']">
                <Popover content="删除" placement="top">
                  <DeleteOutlined
                    class="cursor-pointer text-red-500 hover:text-red-700"
                    @click="softDeleteRecord(record as ContactRecord)"
                  />
                </Popover>
              </Permission>
            </template>
            <template v-else>
              <Permission :allow="['customer:CUSTOMER_RECOVER_CONTACT']">
                <Popover content="恢复" placement="top">
                  <RetweetOutlined
                    class="mr-2 cursor-pointer text-orange-500 hover:text-orange-700"
                    @click="restoreRecord(record as ContactRecord)"
                  />
                </Popover>
              </Permission>
              <Permission :allow="['customer:CUSTOMER_DELETE_CONTACT']">
                <Popover content="彻底删除" placement="top">
                  <DeleteOutlined
                    class="cursor-pointer text-red-500 hover:text-red-700"
                    @click="hardDeleteRecord(record as ContactRecord)"
                  />
                </Popover>
              </Permission>
            </template>
          </template>
        </template>
      </Table>
    </Spin>

    <CustomerModal ref="createModalRef" />
  </div>
</template>

<script lang="ts" setup>
import { computed, h, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Button, message, Popover, Spin, Table } from 'ant-design-vue';
import type { TableProps } from 'ant-design-vue';
import Permission from '@/components/common/Permission.vue';
import { useQueryContactsWithURLSearchParamsAsync } from '@/hooks/useContact.ts';
import { useExportContacts } from '@/hooks/useContact.ts';
import { useContactActions } from '@/hooks/useContactActions.ts';
import {
  DeleteOutlined,
  EditOutlined,
  RetweetOutlined,
  EyeOutlined,
} from '@ant-design/icons-vue';
import CustomerModal from '@/components/client/CustomerModal.vue';
import type { GetContactSearchResponse } from '@/api/axios';
import { useBuildFilterColumn } from '@/utils/buildFilterColumn';

type ContactRecord = NonNullable<
  NonNullable<GetContactSearchResponse['data']>['records']
>[number];
type ContactRemark = NonNullable<ContactRecord['remarks']>[number];
type ContactRowSelection = NonNullable<TableProps<ContactRecord>['rowSelection']>;

definePage({
  name: 'clientContacts',
});

const {
  current,
  pageSize,
  filters,
  setFilters,
  reset,
  data: contacts,
  isLoading,
  refetch,
  isRefetching,
  isDeleted,
  toggleDeleted,
} = useQueryContactsWithURLSearchParamsAsync();

const bulkManageMode = ref(false);
const selectedRowKeys = ref<number[]>([]);

const { buildFilterColumn } = useBuildFilterColumn({
  filters,
  setFilters,
});

const contactList = computed<ContactRecord[]>(() => contacts.value?.records ?? []);

const { mutate: exportContacts, isPending: isExporting } = useExportContacts();
function onExportContacts() {
  exportContacts();
}

const { confirmSoftDelete, confirmHardDelete, confirmRestore } =
  useContactActions(() => refetch());

const contactColumns = computed(() => [
  buildFilterColumn({
    title: '姓名',
    dataIndex: 'name',
    type: 'text',
    key: 'name',
  }),
  buildFilterColumn({
    title: '职位',
    dataIndex: 'position',
    type: 'text',
    key: 'position',
  }),
  buildFilterColumn({
    title: '部门',
    dataIndex: 'dept',
    type: 'text',
    key: 'dept',
  }),
  buildFilterColumn({
    title: '公司',
    dataIndex: 'companyName',
    type: 'text',
    key: 'companyName',
  }),
  buildFilterColumn({
    title: '客户关系等级',
    dataIndex: 'relationLevel',
    type: 'number',
    key: 'relationLevel',
    customRender: ({ text }: { text: number }) => {
      const level = Math.min(Math.max(Number(text) || 0, 0), 9);
      if (level === 0) return '-';
      const levelLabels = ['', 'L1', 'L2', 'L3', 'L4', 'L5', 'L6', 'L7', 'L8', 'L9'];
      const colors = ['', '#52c41a', '#73d13d', '#95de64', '#b7eb8f', '#faad14', '#ffc53d', '#fa8c16', '#ff7a45', '#ff4d4f'];
      return h('span', {
        class: 'inline-flex items-center gap-1',
      }, [
        h('span', {
          style: {
            display: 'inline-block',
            width: `${level * 10}%`,
            height: '8px',
            maxWidth: '80px',
            borderRadius: '4px',
            background: `linear-gradient(to right, ${colors[level]} 100%, #f0f0f0 0)`,
          },
        }),
        h('span', {
          style: { color: colors[level], fontWeight: 500, fontSize: '12px' },
        }, levelLabels[level]),
      ]);
    },
  }),
  buildFilterColumn({
    title: '手机',
    dataIndex: 'mobile',
    type: 'text',
    key: 'mobile',
  }),
  { title: '备注', key: 'remarks', dataIndex: 'remarks' },
  {
    title: '操作',
    key: 'action',
    dataIndex: 'action',
    width: 150,
    fixed: 'right' as const,
  },
]);

const total = computed(() => contacts.value?.total ?? 0);

const rowSelectionEnabled = computed<ContactRowSelection>(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys.map((key) => Number(key));
  },
}));

function handleTableChange(pagination: { current?: number; pageSize?: number }) {
  if (pagination.current !== undefined) {
    current.value = pagination.current;
  }
  if (pagination.pageSize !== undefined) {
    pageSize.value = pagination.pageSize;
  }
}

function handleToggleDeleted() {
  toggleDeleted();
  current.value = 1;
}

function toggleBulkManage() {
  bulkManageMode.value = !bulkManageMode.value;
  selectedRowKeys.value = [];
}

function onBulkSoftDelete() {
  if (!selectedRowKeys.value.length) {
    message.info('请选择联系人');
    return;
  }
  confirmSoftDelete(selectedRowKeys.value, {
    onDone: () => {
      selectedRowKeys.value = [];
    },
  });
}

function onBulkRestore() {
  if (!selectedRowKeys.value.length) {
    message.info('请选择联系人');
    return;
  }
  confirmRestore(selectedRowKeys.value, {
    onDone: () => {
      selectedRowKeys.value = [];
    },
  });
}

function onBulkHardDelete() {
  if (!selectedRowKeys.value.length) {
    message.info('请选择联系人');
    return;
  }
  confirmHardDelete(selectedRowKeys.value, {
    onDone: () => {
      selectedRowKeys.value = [];
    },
  });
}

function getRemarks(record: ContactRecord): ContactRemark[] {
  return record.remarks ?? [];
}

function softDeleteRecord(record: ContactRecord) {
  if (record.id !== undefined) {
    confirmSoftDelete([record.id]);
  }
}

function restoreRecord(record: ContactRecord) {
  if (record.id !== undefined) {
    confirmRestore([record.id]);
  }
}

function hardDeleteRecord(record: ContactRecord) {
  if (record.id !== undefined) {
    confirmHardDelete([record.id]);
  }
}

const createModalRef = ref<InstanceType<typeof CustomerModal>>();
const route = useRoute();

let openedAssistContactId: number | undefined;
watch(
  [() => route.query['filters.id'], contacts], ([rawId, contactData]) => {
    const contactId = Number(rawId);
    if (!Number.isFinite(contactId) || contactId <= 0 || contactId === openedAssistContactId) {
      return;
    }
    const record = contactData?.records?.find((item) => item.id === contactId);
    if (record) {
      openedAssistContactId = contactId;
      createModalRef.value?.openView(record);
    }
  },
  { immediate: true },
);

function openCreate() {
  createModalRef.value?.openCreate();
}

function openEdit(record: ContactRecord) {
  createModalRef.value?.openEdit(record);
}

function openView(record: ContactRecord) {
  createModalRef.value?.openView(record);
}
</script>
