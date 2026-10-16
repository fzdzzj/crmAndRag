<script setup lang="ts">
import { computed } from 'vue';
import {
  SYNC_ENTITIES,
  type SyncEntityKey,
  type SyncResult,
} from '@/hooks/useKnowledge';

const props = defineProps<{
  results?: Partial<Record<SyncEntityKey, SyncResult>>;
  syncing?: boolean;
  syncingAll?: boolean;
}>();

const emit = defineEmits<{
  sync: [entity: SyncEntityKey];
  syncAll: [];
}>();

/** 实体行（含各自最近结果），模板无需重复索引访问以便 TS 收窄。 */
const resultEntries = computed(() =>
  SYNC_ENTITIES.map((entity) => ({
    entity,
    result: props.results?.[entity.key],
  })),
);

const hasAnyResult = computed(() =>
  resultEntries.value.some((row) => row.result != null),
);
</script>

<template>
  <div class="border rounded-lg p-4">
    <div class="font-medium mb-1">向量库运维 · 全量回埋</div>
    <div class="text-xs text-amber-600 mb-3">
      成本敏感：调用 /sync/* 将真实触发 DashScope 重嵌入，首次执行须 Owner
      显式授权；本卡不自动触发任何真实回埋。
    </div>

    <div class="flex flex-wrap gap-2 mb-3">
      <button
        v-for="e in SYNC_ENTITIES"
        :key="e.key"
        class="px-3 py-1 text-sm rounded border hover:bg-gray-50"
        :disabled="syncing"
        @click="emit('sync', e.key)"
      >
        {{ e.label }}
      </button>
      <button
        class="px-3 py-1 text-sm rounded border bg-gray-100 hover:bg-gray-50"
        :disabled="syncingAll"
        @click="emit('syncAll')"
      >
        全部同步
      </button>
    </div>

    <div v-if="hasAnyResult" class="bg-gray-50 p-3 rounded text-sm">
      <div class="font-mono mb-2">最近同步结果</div>
      <div
        v-for="{ entity, result } in resultEntries"
        :key="entity.key"
        class="mb-1"
      >
        <span class="inline-block w-24 text-gray-600">{{ entity.label }}</span>
        <span v-if="result" class="font-mono">
          成功 {{ result.success }} / 失败 {{ result.failure }} / 总计
          {{ result.total }}
        </span>
        <span v-else class="text-gray-400">未同步</span>
      </div>
    </div>
    <div v-else class="text-gray-400 text-sm">尚无同步记录</div>
  </div>
</template>
