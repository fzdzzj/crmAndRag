<script setup lang="ts">
import { computed, h } from 'vue';
import {
  Button as AButton,
  Input as AInput,
  Select as ASelect,
  SelectOption as ASelectOption,
  Textarea as ATextarea,
} from 'ant-design-vue';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue';
import { useQueryUserOptions } from '@/hooks/useUser';
import { getDuplicateAssistUserIds, type AssistApplyItem } from '@/hooks/useAssist';

const props = defineProps<{ value?: AssistApplyItem[]; allowRemove?: boolean }>();
const emit = defineEmits<{ 'update:value': [value: AssistApplyItem[]] }>();

const { data } = useQueryUserOptions();
const users = computed(() => data.value ?? []);

const rows = computed<AssistApplyItem[]>({
  get: () => props.value ?? [],
  set: (v) => emit('update:value', v),
});
const duplicateIds = computed(() => new Set(getDuplicateAssistUserIds(rows.value)));

const addRow = () => {
  rows.value = [...rows.value, { assistUserId: undefined, applyPurpose: '', applyRequirement: '' }];
};

const removeRow = (index: number) => {
  rows.value = rows.value.filter((_, i) => i !== index);
};

const updateRow = (index: number, patch: Partial<AssistApplyItem>) => {
  const next = rows.value.map((row, i) => (i === index ? { ...row, ...patch } : row));
  rows.value = next;
};
</script>

<template>
  <div class="w-full">
    <div
      v-for="(row, index) in rows"
      :key="index"
      class="mb-3 rounded border border-gray-200 p-2"
    >
      <div class="mb-2 flex items-center justify-between">
        <span class="text-sm text-gray-600">协助人 {{ index + 1 }}</span>
        <AButton
          v-if="props.allowRemove !== false"
          type="text"
          danger
          size="small"
          :icon="h(DeleteOutlined)"
          @click="removeRow(index)"
        >
          删除
        </AButton>
      </div>
      <div class="mb-2">
        <a-select
          :value="row.assistUserId"
          placeholder="请选择协助人"
          show-search
          option-filter-prop="label"
          style="width: 100%"
          @change="(v) => updateRow(index, { assistUserId: v as number })"
        >
          <a-select-option
            v-for="user in users"
            :key="user.id"
            :value="user.id!"
            :label="`${user.realName}（${user.deptName ?? '未分配部门'}）`"
          >
            {{ user.realName }}（{{ user.deptName ?? '未分配部门' }}）
          </a-select-option>
        </a-select>
      </div>
      <div v-if="row.assistUserId != null && duplicateIds.has(row.assistUserId)" class="mb-2 text-xs text-red-500">
        同一次申请不能重复选择该协助人
      </div>
      <div class="mb-2">
        <a-input
          :value="row.applyPurpose"
          placeholder="协作目的：如 补充材料 / 填写信息 / 跟进客户 / 协助流转 / 自定义"
          @change="(e) => updateRow(index, { applyPurpose: (e.target as HTMLInputElement).value })"
        />
      </div>
      <a-textarea
        :value="row.applyRequirement"
        :rows="2"
        placeholder="协作要求：需要对方具体做什么"
        @change="(e) => updateRow(index, { applyRequirement: (e.target as HTMLTextAreaElement).value })"
      />
    </div>
    <AButton type="dashed" block :icon="h(PlusOutlined)" @click="addRow">
      添加协助人
    </AButton>
  </div>
</template>
