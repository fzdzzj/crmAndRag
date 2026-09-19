<script setup lang="ts">
import { computed } from 'vue';
import { Select as ASelect, SelectOption as ASelectOption } from 'ant-design-vue';
import { useQueryUserOptions } from '@/hooks/useUser';

const props = defineProps<{ value?: number[] }>();
const emit = defineEmits<{ 'update:value': [value: number[]] }>();

// 轻量用户选择接口：仅返回在职用户，避免拉全量用户（含手机/邮箱）数据
const { data } = useQueryUserOptions();
const users = computed(() => data.value ?? []);

const selected = computed({
  get: () => props.value ?? [],
  set: (v: number[]) => emit('update:value', v),
});
</script>

<template>
  <a-select
    v-model:value="selected"
    mode="multiple"
    :max-tag-count="3"
    placeholder="请选择协助人（可多选，显示姓名+部门）"
    option-filter-prop="label"
    allow-clear
    style="width: 100%"
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
</template>
