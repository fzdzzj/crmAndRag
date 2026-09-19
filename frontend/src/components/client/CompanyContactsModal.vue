<template>
  <a-modal
    v-model:open="openState"
    :title="modalTitle"
    :footer="null"
    width="720px"
    @cancel="close"
  >
    <template v-if="isLoading">
      <div style="text-align: center; padding: 24px 0">
        <a-spin tip="Loading..." />
      </div>
    </template>
    <template v-else>
      <a-table
        :scroll="{ x: 'max-content' }"
        :columns="columns"
        :data-source="contacts"
        :pagination="false"
        row-key="id"
        size="small"
      />
    </template>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { Modal, Table, Spin, message } from 'ant-design-vue';
import { useCompanyContacts } from '@/hooks/useCompany.ts';

const openState = ref(false);
const currentCompanyId = ref<number | undefined>(undefined);
const currentCompanyName = ref<string | undefined>(undefined);

const { data, isLoading, refetch } = useCompanyContacts(currentCompanyId);

const contacts = computed(() => data?.value ?? []);
const modalTitle = computed(
  () =>
    `联系人${currentCompanyName.value ? `（${currentCompanyName.value}）` : currentCompanyId.value ? `（公司ID: ${currentCompanyId.value}）` : ''}`,
);

const columns = [
  { title: '姓名', dataIndex: 'name', key: 'name' },
  { title: '职位', dataIndex: 'position', key: 'position' },
  { title: '部门', dataIndex: 'dept', key: 'dept' },
  { title: '座机', dataIndex: 'phone', key: 'phone' },
  { title: '手机', dataIndex: 'mobile', key: 'mobile' },
  { title: '邮箱', dataIndex: 'email', key: 'email' },
  { title: '备注', dataIndex: 'remark', key: 'remark' },
];

function getErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function open(companyId: number, companyName?: string) {
  currentCompanyId.value = companyId;
  currentCompanyName.value = companyName;
  openState.value = true;
  void refetch().catch((error: unknown) => {
    message.error(getErrorMessage(error, '加载失败'));
  });
}

function close() {
  openState.value = false;
}

defineExpose({ open, close });
</script>

<script lang="ts">
export default {
  components: {
    'a-modal': Modal,
    'a-table': Table,
    'a-spin': Spin,
  },
};
</script>

<style scoped></style>
