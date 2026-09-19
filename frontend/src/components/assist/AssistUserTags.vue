<script setup lang="ts">
import { Tag } from 'ant-design-vue';
import { computed } from 'vue';
import { normalizeAssistModelName } from './assistSnapshot';

export interface AssistUserItem {
  id?: number;
  assistUserName?: string;
  assistUserDeptName?: string;
  assistStatus?: number;
  modelName?: string;
  recordId?: number;
}

const props = defineProps<{
  /** 协助人列表 */
  users?: AssistUserItem[] | null;
  /** 紧凑模式：表格单元格 / 移动端 */
  compact?: boolean;
  modelName?: string;
  recordId?: number;
}>();

const emit = defineEmits<{ open: [user: AssistUserItem] }>();

const visibleUsers = computed(() => (props.users ?? []).filter((user) => {
  // 新契约带来源模型和记录 ID 时严格匹配；旧契约无字段时保持兼容。
  if (user.modelName == null && user.recordId == null) return true;
  return normalizeAssistModelName(user.modelName) === normalizeAssistModelName(props.modelName)
    && user.recordId === props.recordId;
}));

const statusMap: Record<number, { text: string; color: string }> = {
  0: { text: '待协助', color: 'warning' },
  1: { text: '已协助', color: 'success' },
  2: { text: '已驳回', color: 'error' },
  3: { text: '已拒绝', color: 'default' },
  4: { text: '已取消', color: 'default' },
};
</script>

<template>
  <div
    v-if="visibleUsers.length > 0"
    class="flex flex-wrap items-center gap-1.5"
  >
    <span
      v-for="(user, idx) in visibleUsers"
      :key="idx"
      class="inline-flex items-center gap-1.5 rounded-full border border-gray-200 bg-gray-50 py-0.5 pl-2 pr-1"
      :class="[compact ? 'text-xs' : 'text-xs sm:text-sm', user.id ? 'cursor-pointer hover:border-blue-300 hover:bg-blue-50' : '']"
      @click="user.id && emit('open', user)"
    >
      <span class="font-medium text-gray-800">
        {{ user.assistUserName ?? '?' }}
      </span>
      <span class="text-gray-400" :class="compact ? 'text-[10px]' : 'text-[10px] sm:text-xs'">
        {{ user.assistUserDeptName ?? '无部门' }}
      </span>
      <Tag
        :color="statusMap[user.assistStatus ?? 0]?.color ?? 'default'"
        class="m-0"
      >
        {{ statusMap[user.assistStatus ?? 0]?.text ?? '未知' }}
      </Tag>
    </span>
  </div>
  <span v-else class="text-gray-400">-</span>
</template>
