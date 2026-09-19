<script setup lang="ts">
import { computed, h, ref } from 'vue';
import {
  Table as ATable,
  Button as AButton,
  Input as AInput,
  Select as ASelect,
  SelectOption as ASelectOption,
  Form as AForm,
  FormItem as AFormItem,
  InputNumber as AInputNumber,
  Tag,
  message,
} from 'ant-design-vue';
import type { TableColumnType } from 'ant-design-vue';
import { useCreateDept, useQueryAllDepts } from '@/hooks/useDept';
import type { DeptRecord } from '@/hooks/useDept';

// 部门管理需要看到全部部门（含停用），否则停用后无法再编辑/启用
const { data: depts } = useQueryAllDepts();
const { mutate: createDept, isPending: creating } = useCreateDept();

const form = ref({ deptName: '', status: 1, sort: 0 });
const filterStatus = ref<number | undefined>(undefined);

const filteredDepts = computed(() => {
  const all = depts.value ?? [];
  return filterStatus.value === undefined
    ? all
    : all.filter((dept) => dept.status === filterStatus.value);
});

const resetForm = () => {
  form.value = { deptName: '', status: 1, sort: 0 };
};

const submit = () => {
  if (!form.value.deptName?.trim()) {
    message.warning('请输入部门名称');
    return;
  }
  createDept(
    {
      deptName: form.value.deptName.trim(),
      status: form.value.status,
      sort: form.value.sort,
    },
    {
      onSuccess: () => {
        message.success('新增部门成功');
        resetForm();
      },
      onError: (e: unknown) => {
        message.error((e as { message?: string }).message ?? '新增部门失败');
      },
    },
  );
};

const columns: TableColumnType<DeptRecord>[] = [
  { title: '部门名称', dataIndex: 'deptName', key: 'deptName' },
  { title: '排序', dataIndex: 'sort', key: 'sort', width: 80 },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    width: 90,
    customRender: ({ text }: { text?: number }) =>
      h(Tag, { color: text === 1 ? 'success' : 'error' }, () =>
        text === 1 ? '启用' : '停用',
      ),
  },
];
</script>

<template>
  <div class="w-full">
    <div class="mb-3 flex items-center justify-end">
      <a-select
        v-model:value="filterStatus"
        placeholder="状态筛选"
        allow-clear
        style="width: 120px"
      >
        <a-select-option :value="1">启用</a-select-option>
        <a-select-option :value="0">停用</a-select-option>
      </a-select>
    </div>
    <a-form layout="inline" class="mb-3" style="row-gap: 8px">
      <a-form-item label="部门名称">
        <a-input
          v-model:value="form.deptName"
          placeholder="请输入部门名称"
          style="width: 180px"
        />
      </a-form-item>
      <a-form-item label="状态">
        <a-select v-model:value="form.status" style="width: 90px">
          <a-select-option :value="1">启用</a-select-option>
          <a-select-option :value="0">停用</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="排序">
        <a-input-number
          v-model:value="form.sort"
          :min="0"
          style="width: 90px"
        />
      </a-form-item>
      <a-form-item>
        <a-button
          type="primary"
          size="small"
          :loading="creating"
          @click="submit"
        >
          新增
        </a-button>
      </a-form-item>
    </a-form>
    <a-table
      :data-source="filteredDepts"
      :columns="columns"
      row-key="id"
      size="small"
      :pagination="false"
    />
  </div>
</template>
