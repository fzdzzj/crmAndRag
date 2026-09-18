import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref, type Ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import { message } from 'ant-design-vue';
import {
  getPlatformConfigItems,
  postPlatformConfigItems,
  getPlatformConfigItemsByKey,
  getPlatformConfigItemsByKeyHistory,
  postPlatformConfigItemsByKeyRollback,
  deletePlatformConfigItemsByKey,
  postPlatformConfigCacheRefresh,
} from '@/api/axios/sdk.gen.ts';
import type { ConfigItemView, ConfigHistoryView } from '@/api/axios/types.gen.ts';

export type { ConfigItemView, ConfigHistoryView };

/** 值类型（与后端 ConfigValueType 对齐） */
export type ConfigValueType = 'STRING' | 'INTEGER' | 'LONG' | 'BOOLEAN' | 'DOUBLE' | 'STRING_LIST';

/**
 * 收窄视图：后端 record 各字段恒序列化（值可为 null，但键名/命名空间/类型不会缺失），
 * 生成类型为全可选，这里收敛为页面使用的非空形状。
 */
export interface ConfigItem {
  key: string;
  namespace: string;
  valueType: ConfigValueType;
  value: string | null;
  defaultValue: string | null;
  description: string;
  version: number | null;
  updatedAt: string | null;
  updatedBy: string | null;
  deleted: boolean;
  sensitive: boolean;
  minValue: string | null;
  maxValue: string | null;
  allowedValues: string[] | null;
}

export interface ConfigHistory {
  version: number;
  operationType: string;
  oldValue: string | null;
  newValue: string | null;
  operatorRef: string | null;
  remark: string | null;
  createTime: string;
}

function narrowItem(raw: ConfigItemView): ConfigItem {
  return {
    key: raw.key ?? '',
    namespace: raw.namespace ?? '',
    valueType: (raw.valueType ?? 'STRING') as ConfigValueType,
    value: raw.value ?? null,
    defaultValue: raw.defaultValue ?? null,
    description: raw.description ?? '',
    version: raw.version ?? null,
    updatedAt: raw.updatedAt ?? null,
    updatedBy: raw.updatedBy ?? null,
    deleted: raw.deleted ?? false,
    sensitive: raw.sensitive ?? false,
    minValue: raw.minValue ?? null,
    maxValue: raw.maxValue ?? null,
    allowedValues: raw.allowedValues ? [...raw.allowedValues] : null,
  };
}

function narrowHistory(raw: ConfigHistoryView): ConfigHistory {
  return {
    version: raw.version ?? 0,
    operationType: raw.operationType ?? '',
    oldValue: raw.oldValue ?? null,
    newValue: raw.newValue ?? null,
    operatorRef: raw.operatorRef ?? null,
    remark: raw.remark ?? null,
    createTime: raw.createTime ?? '',
  };
}

/** 更新请求体（与后端 ConfigUpdateRequest 对齐） */
export interface ConfigUpdatePayload {
  key: string;
  value: string;
  remark?: string;
}

export const CONFIG_ITEMS_QUERY_KEY = 'platformConfigItems';

/** 命名空间展示顺序（与主仓 DynamicConfigKeyRegistry 五命名空间对齐） */
export const NAMESPACE_ORDER = [
  'ai.prompt',
  'ai.model',
  'rag.retrieval',
  'rag.intent',
  'business',
] as const;

export const NAMESPACE_LABELS: Record<string, string> = {
  'ai.prompt': 'AI 提示词',
  'ai.model': 'AI 模型',
  'rag.retrieval': 'RAG 检索',
  'rag.intent': 'RAG 意图',
  business: '业务参数',
};

export interface GroupedNamespace {
  namespace: string;
  label: string;
  items: ConfigItem[];
}

/** 拉取全部配置项（namespace 为空 = 全部） */
export async function fetchConfigItems(namespace?: string | null): Promise<ConfigItem[]> {
  const res = await getPlatformConfigItems({
    client: apiClient,
    query: namespace ? { namespace } : undefined,
  });
  return (res.data?.data ?? []).map(narrowItem);
}

/** 配置键列表查询（含关键字搜索：匹配键名 / 影响面描述 / 命名空间，按命名空间分组） */
export function useConfigItems(keyword?: Ref<string>) {
  const query = useQuery({
    queryKey: [CONFIG_ITEMS_QUERY_KEY],
    queryFn: () => fetchConfigItems(),
  });

  const items = computed(() => query.data.value ?? []);

  /** 按命名空间分组（保持 NAMESPACE_ORDER 顺序，未知命名空间排后） */
  const grouped = computed<GroupedNamespace[]>(() => {
    const kw = (keyword?.value ?? '').trim().toLowerCase();
    const filtered = kw
      ? items.value.filter(
          (it) =>
            it.key.toLowerCase().includes(kw) ||
            (it.description ?? '').toLowerCase().includes(kw) ||
            it.namespace.toLowerCase().includes(kw),
        )
      : items.value;
    const byNs = new Map<string, ConfigItem[]>();
    for (const it of filtered) {
      const list = byNs.get(it.namespace) ?? [];
      list.push(it);
      byNs.set(it.namespace, list);
    }
    const order = [
      ...NAMESPACE_ORDER,
      ...[...byNs.keys()].filter((k) => !(NAMESPACE_ORDER as readonly string[]).includes(k)),
    ];
    return order
      .filter((ns) => byNs.has(ns))
      .map((ns) => ({
        namespace: ns,
        label: NAMESPACE_LABELS[ns] ?? ns,
        items: (byNs.get(ns) ?? []).slice().sort((a, b) => a.key.localeCompare(b.key)),
      }));
  });

  return { query, items, grouped };
}

/** 单个配置项详情（敏感值掩码） */
export function useConfigDetail(key: Ref<string>) {
  return useQuery({
    queryKey: computed(() => [CONFIG_ITEMS_QUERY_KEY, 'detail', key.value]),
    queryFn: async () => {
      const res = await getPlatformConfigItemsByKey({ client: apiClient, path: { key: key.value } });
      return res.data?.data ? narrowItem(res.data.data) : undefined;
    },
    enabled: computed(() => Boolean(key.value)),
  });
}

/** 配置键版本历史（按版本倒序） */
export function useConfigHistory(key: Ref<string>) {
  return useQuery({
    queryKey: computed(() => [CONFIG_ITEMS_QUERY_KEY, 'history', key.value]),
    queryFn: async () => {
      const res = await getPlatformConfigItemsByKeyHistory({
        client: apiClient,
        path: { key: key.value },
      });
      return (res.data?.data ?? []).map(narrowHistory);
    },
    enabled: computed(() => Boolean(key.value)),
  });
}

/** 更新配置值（保存成功后刷新列表；校验失败由拦截器抛 ApiError 并提示） */
export function useConfigUpdate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: ConfigUpdatePayload) => {
      const res = await postPlatformConfigItems({ client: apiClient, body: payload });
      return res.data?.data ? narrowItem(res.data.data) : undefined;
    },
    onSuccess: (item) => {
      void queryClient.invalidateQueries({ queryKey: [CONFIG_ITEMS_QUERY_KEY] });
      message.success(`已保存 ${item?.key ?? ''}，热生效`);
    },
    onError: (err: unknown) => {
      message.error((err as Error)?.message || '保存失败');
    },
  });
}

/** 回滚到指定历史版本（回滚本身留痕并热生效） */
export function useConfigRollback() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { key: string; version: number; remark?: string }) => {
      const res = await postPlatformConfigItemsByKeyRollback({
        client: apiClient,
        path: { key: vars.key },
        body: { version: vars.version, remark: vars.remark },
      });
      return res.data?.data ? narrowItem(res.data.data) : undefined;
    },
    onSuccess: (_item, vars) => {
      void queryClient.invalidateQueries({ queryKey: [CONFIG_ITEMS_QUERY_KEY] });
      message.success(`已回滚 ${vars.key} 至 v${vars.version}`);
    },
    onError: (err: unknown) => {
      message.error((err as Error)?.message || '回滚失败');
    },
  });
}

/** 软删除覆盖 = 恢复静态默认（历史保留，可回滚复活） */
export function useConfigReset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { key: string; remark?: string }) => {
      const res = await deletePlatformConfigItemsByKey({
        client: apiClient,
        path: { key: vars.key },
        query: vars.remark ? { remark: vars.remark } : undefined,
      });
      return res.data?.data ? narrowItem(res.data.data) : undefined;
    },
    onSuccess: (item) => {
      void queryClient.invalidateQueries({ queryKey: [CONFIG_ITEMS_QUERY_KEY] });
      message.success(`${item?.key ?? ''} 已恢复默认值`);
    },
    onError: (err: unknown) => {
      message.error((err as Error)?.message || '恢复默认失败');
    },
  });
}

/** 手动触发缓存全量刷新（多实例兜底），返回刷新后条目数 */
export function useConfigCacheRefresh() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const res = await postPlatformConfigCacheRefresh({ client: apiClient });
      return res.data?.data as number;
    },
    onSuccess: (count) => {
      void queryClient.invalidateQueries({ queryKey: [CONFIG_ITEMS_QUERY_KEY] });
      message.success(`缓存已刷新（${count} 项）`);
    },
    onError: (err: unknown) => {
      message.error((err as Error)?.message || '缓存刷新失败');
    },
  });
}

/** 数值范围校验（Integer/Long/Double 输入护栏；min/max 为后端下发的字符串） */
export function validateNumericRange(
  raw: string,
  valueType: ConfigValueType,
  minValue: string | null,
  maxValue: string | null,
): string | null {
  const num = Number(raw);
  if (!Number.isFinite(num)) return '必须是有效数值';
  if (valueType === 'INTEGER' || valueType === 'LONG') {
    if (!Number.isInteger(num)) return '必须是整数';
  }
  if (minValue !== null && num < Number(minValue)) return `不能小于 ${minValue}`;
  if (maxValue !== null && num > Number(maxValue)) return `不能大于 ${maxValue}`;
  return null;
}

/** STRING_LIST 校验（紧凑 JSON 数组文本） */
export function validateStringList(raw: string): string | null {
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed) || parsed.length === 0) return '必须是非空 JSON 字符串数组';
    if (parsed.some((x) => typeof x !== 'string' || !x.trim())) return '数组条目必须为非空字符串';
    return null;
  } catch {
    return '必须是合法 JSON（如 ["类目A","类目B"]）';
  }
}

/** 编辑弹窗草稿状态（供页面复用，避免组件内散落状态逻辑） */
export function useConfigEditor() {
  const editing = ref<ConfigItem | null>(null);
  const draftValue = ref('');

  function openEditor(item: ConfigItem) {
    editing.value = item;
    draftValue.value = item.value ?? item.defaultValue ?? '';
  }

  function closeEditor() {
    editing.value = null;
    draftValue.value = '';
  }

  /** 当前草稿的本地校验错误；null = 通过 */
  const draftError = computed(() => {
    const item = editing.value;
    if (!item) return null;
    const raw = draftValue.value.trim();
    if (!raw) return '值不能为空';
    switch (item.valueType) {
      case 'BOOLEAN':
        return raw === 'true' || raw === 'false' ? null : '必须是 true/false';
      case 'INTEGER':
      case 'LONG':
      case 'DOUBLE':
        return validateNumericRange(raw, item.valueType, item.minValue ?? null, item.maxValue ?? null);
      case 'STRING_LIST':
        return validateStringList(raw);
      case 'STRING':
      default: {
        if (item.allowedValues?.length && !item.allowedValues.includes(raw)) {
          return `取值必须在枚举范围内：${item.allowedValues.join(' / ')}`;
        }
        return null;
      }
    }
  });

  return { editing, draftValue, draftError, openEditor, closeEditor };
}
