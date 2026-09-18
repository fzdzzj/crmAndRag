<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { Modal, message } from 'ant-design-vue';
import MainLayout from '@/layout/MainLayout.vue';
import {
  useConfigItems,
  useConfigUpdate,
  useConfigReset,
  useConfigCacheRefresh,
  useConfigEditor,
  type ConfigItem,
} from '@/hooks/usePlatformConfig';

definePage({
  name: 'platformConfig',
});

const keyword = ref('');
const remarkDraft = ref('');
const { query, grouped } = useConfigItems(keyword);
const updateMutation = useConfigUpdate();
const resetMutation = useConfigReset();
const cacheMutation = useConfigCacheRefresh();
const { editing, draftValue, draftError, openEditor, closeEditor } = useConfigEditor();

const groupCount = computed(() => grouped.value.reduce((n, g) => n + g.items.length, 0));

const columns = [
  { title: '配置键', dataIndex: 'key', key: 'key', width: 230 },
  { title: '类型', dataIndex: 'valueType', key: 'valueType', width: 100 },
  { title: '当前值', key: 'value', width: 170 },
  { title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 120 },
  { title: '范围/枚举', key: 'range', width: 190 },
  { title: '影响面说明', dataIndex: 'description', key: 'description' },
  { title: '操作', key: 'action', width: 160 },
];

/** Boolean 开关（草稿字符串 true/false ↔ a-switch） */
const boolDraft = computed({
  get: () => draftValue.value === 'true',
  set: (v: boolean) => {
    draftValue.value = v ? 'true' : 'false';
  },
});

/** STRING_LIST：编辑态为换行文本，提交时序列化为紧凑 JSON 数组 */
const listDraft = computed({
  get: () => {
    try {
      const arr = JSON.parse(draftValue.value || '[]');
      return Array.isArray(arr) ? arr.join('\n') : draftValue.value;
    } catch {
      return draftValue.value;
    }
  },
  set: (v: string) => {
    draftValue.value = v;
  },
});

function displayValue(item: ConfigItem): string {
  if (item.sensitive && item.value) return '******';
  return item.value ?? '';
}

function effectiveDisplay(item: ConfigItem): string {
  if (item.value !== null && item.value !== '') return displayValue(item);
  return `${item.defaultValue ?? ''}（默认）`;
}

function rangeHint(item: ConfigItem): string {
  if (item.allowedValues?.length) return `枚举：${item.allowedValues.join(' / ')}`;
  if (item.minValue !== null || item.maxValue !== null) {
    return `范围：[${item.minValue ?? '-∞'}, ${item.maxValue ?? '+∞'}]`;
  }
  return '';
}

function startEdit(item: ConfigItem) {
  if (item.sensitive) {
    message.warning('敏感键不支持在前端查看或编辑明文值');
    return;
  }
  remarkDraft.value = '';
  openEditor(item);
}

/** 组装最终提交值（STRING_LIST 序列化），非法返回 null 并提示 */
function buildSubmitValue(): string | null {
  const item = editing.value;
  if (!item) return null;
  if (draftError.value) {
    message.warning(draftError.value);
    return null;
  }
  if (item.valueType === 'STRING_LIST') {
    const lines = draftValue.value
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);
    if (!lines.length) {
      message.warning('列表至少填写一行');
      return null;
    }
    return JSON.stringify(lines);
  }
  return draftValue.value.trim();
}

/** 保存前确认弹窗：回显「旧值 → 新值 + 影响面描述」 */
function confirmSave() {
  const item = editing.value;
  if (!item) return;
  const newValue = buildSubmitValue();
  if (newValue === null) return;
  const oldValue = item.value ?? `${item.defaultValue ?? ''}（默认）`;
  Modal.confirm({
    title: `确认修改配置 ${item.key}`,
    width: 540,
    okText: '确认保存',
    cancelText: '取消',
    content: h('div', { class: 'space-y-2 text-sm' }, [
      h('p', [
        '旧值：',
        h('span', { class: 'font-mono break-all' }, oldValue),
        ' → 新值：',
        h('span', { class: 'font-mono font-semibold break-all' }, newValue),
      ]),
      h('p', { class: 'text-orange-600' }, `影响面：${item.description}`),
      h('p', { class: 'text-gray-500' }, '保存后立即热生效，请确认操作无误。'),
    ]),
    onOk: () =>
      updateMutation
        .mutateAsync({ key: item.key, value: newValue, remark: remarkDraft.value || undefined })
        .then(() => {
          closeEditor();
        }),
  });
}

function confirmReset(item: ConfigItem) {
  Modal.confirm({
    title: `确认恢复默认 ${item.key}`,
    width: 540,
    okText: '恢复默认',
    cancelText: '取消',
    content: h('div', { class: 'space-y-2 text-sm' }, [
      h('p', [
        '当前值：',
        h('span', { class: 'font-mono' }, displayValue(item) || '（无覆盖）'),
        ' → 将恢复静态默认：',
        h('span', { class: 'font-mono font-semibold' }, item.defaultValue ?? '（无）'),
      ]),
      h('p', { class: 'text-orange-600' }, `影响面：${item.description}`),
      h('p', { class: 'text-gray-500' }, '软删除动态覆盖（历史保留，可回滚复活）。'),
    ]),
    onOk: () => resetMutation.mutateAsync({ key: item.key, remark: '前端恢复默认' }),
  });
}
</script>

<template>
  <MainLayout>
    <div class="p-6 space-y-6 bg-white min-h-full" data-tour="platform-config-page">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-2xl font-semibold">平台动态配置</h1>
          <p class="text-sm text-gray-500 mt-1">
            仅超级管理员可访问 · 修改即时热生效 · 敏感键掩码不可编辑
          </p>
        </div>
        <a-button :loading="cacheMutation.isPending.value" @click="cacheMutation.mutate()">
          刷新缓存
        </a-button>
      </div>

      <div class="flex flex-wrap items-center gap-4">
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索键名 / 说明 / 命名空间"
          class="max-w-md"
          allow-clear
          data-tour="platform-config-search"
        />
        <span class="text-sm text-gray-400">
          共 {{ groupCount }} 项{{ query.isLoading.value ? '，加载中…' : '' }}
        </span>
      </div>

      <a-alert
        v-if="query.isError.value"
        type="error"
        show-icon
        message="配置加载失败"
        description="请检查登录态与权限（该端点仅超管可访问），或稍后重试。"
      />

      <a-spin :spinning="query.isLoading.value">
        <div class="space-y-8">
          <section v-for="group in grouped" :key="group.namespace">
            <h2 class="text-lg font-semibold mb-2">
              {{ group.label }}
              <span class="text-xs font-normal text-gray-400 ml-1">{{ group.namespace }}</span>
            </h2>
            <a-table
              :data-source="group.items"
              :columns="columns"
              :pagination="false"
              row-key="key"
              size="middle"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'value'">
                  <span class="font-mono break-all">{{ effectiveDisplay(record as ConfigItem) }}</span>
                  <a-tag v-if="(record as ConfigItem).version" color="blue" class="ml-1">
                    v{{ (record as ConfigItem).version }}
                  </a-tag>
                  <a-tag v-if="(record as ConfigItem).sensitive" color="red" class="ml-1">敏感</a-tag>
                </template>
                <template v-else-if="column.key === 'range'">
                  <span class="text-xs text-gray-500">{{ rangeHint(record as ConfigItem) || '—' }}</span>
                </template>
                <template v-else-if="column.key === 'action'">
                  <a-space>
                    <a-button
                      type="link"
                      size="small"
                      :disabled="(record as ConfigItem).sensitive"
                      @click="startEdit(record as ConfigItem)"
                    >
                      修改
                    </a-button>
                    <a-button
                      type="link"
                      size="small"
                      danger
                      :disabled="(record as ConfigItem).value === null"
                      @click="confirmReset(record as ConfigItem)"
                    >
                      恢复默认
                    </a-button>
                  </a-space>
                </template>
              </template>
            </a-table>
          </section>

          <a-empty v-if="!query.isLoading.value && !grouped.length" description="无匹配配置项" />
        </div>
      </a-spin>

      <!-- 编辑弹窗：按类型出表单控件 -->
      <a-modal
        :open="Boolean(editing)"
        :title="editing ? `修改 ${editing.key}` : ''"
        ok-text="下一步：确认影响面"
        cancel-text="取消"
        :confirm-loading="updateMutation.isPending.value"
        :ok-button-props="{ disabled: Boolean(draftError) }"
        @ok="confirmSave"
        @cancel="closeEditor"
      >
        <div v-if="editing" class="space-y-3 py-2">
          <a-alert type="warning" show-icon :message="`影响面：${editing.description}`" />
          <p class="text-sm text-gray-500">
            当前值：<span class="font-mono">{{ effectiveDisplay(editing) }}</span>
            <span v-if="rangeHint(editing)"> · {{ rangeHint(editing) }}</span>
          </p>
          <template v-if="editing.valueType === 'BOOLEAN'">
            <div class="flex items-center gap-3">
              <a-switch v-model:checked="boolDraft" checked-children="开" un-checked-children="关" />
              <span class="text-sm text-gray-500">开关切换后仍需点击保存确认，防误触</span>
            </div>
          </template>
          <template v-else-if="editing.valueType === 'INTEGER' || editing.valueType === 'LONG'">
            <a-input-number
              v-model:value="draftValue"
              class="w-full"
              :precision="0"
              :min="editing.minValue !== null ? Number(editing.minValue) : undefined"
              :max="editing.maxValue !== null ? Number(editing.maxValue) : undefined"
            />
          </template>
          <template v-else-if="editing.valueType === 'DOUBLE'">
            <a-input-number v-model:value="draftValue" class="w-full" :step="0.01" />
          </template>
          <template v-else-if="editing.valueType === 'STRING_LIST'">
            <a-textarea
              v-model:value="listDraft"
              :rows="4"
              placeholder="每行一个条目，保存时序列化为 JSON 数组"
            />
          </template>
          <template v-else>
            <a-select
              v-if="editing.allowedValues?.length"
              v-model:value="draftValue"
              class="w-full"
              :options="editing.allowedValues.map((v) => ({ label: v, value: v }))"
            />
            <a-textarea v-else v-model:value="draftValue" :rows="3" />
          </template>
          <p v-if="draftError" class="text-sm text-red-500">{{ draftError }}</p>
          <a-input v-model:value="remarkDraft" placeholder="变更备注（可选，写入版本历史）" />
        </div>
      </a-modal>
    </div>
  </MainLayout>
</template>
