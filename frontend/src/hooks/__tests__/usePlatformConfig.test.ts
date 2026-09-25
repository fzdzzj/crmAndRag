// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type Ref } from 'vue';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';

vi.mock('@/api/apiClient.ts', () => ({
  default: { name: 'mockApiClient' },
}));

const sdkState = vi.hoisted(() => ({
  getPlatformConfigItems: vi.fn(),
  postPlatformConfigItems: vi.fn(),
  getPlatformConfigItemsByKey: vi.fn(),
  getPlatformConfigItemsByKeyHistory: vi.fn(),
  postPlatformConfigItemsByKeyRollback: vi.fn(),
  deletePlatformConfigItemsByKey: vi.fn(),
  postPlatformConfigCacheRefresh: vi.fn(),
}));

vi.mock('@/api/axios/sdk.gen.ts', () => ({
  getPlatformConfigItems: (...args: unknown[]) => sdkState.getPlatformConfigItems(...(args as [])),
  postPlatformConfigItems: (...args: unknown[]) => sdkState.postPlatformConfigItems(...(args as [])),
  getPlatformConfigItemsByKey: (...args: unknown[]) =>
    sdkState.getPlatformConfigItemsByKey(...(args as [])),
  getPlatformConfigItemsByKeyHistory: (...args: unknown[]) =>
    sdkState.getPlatformConfigItemsByKeyHistory(...(args as [])),
  postPlatformConfigItemsByKeyRollback: (...args: unknown[]) =>
    sdkState.postPlatformConfigItemsByKeyRollback(...(args as [])),
  deletePlatformConfigItemsByKey: (...args: unknown[]) =>
    sdkState.deletePlatformConfigItemsByKey(...(args as [])),
  postPlatformConfigCacheRefresh: (...args: unknown[]) =>
    sdkState.postPlatformConfigCacheRefresh(...(args as [])),
}));

vi.mock('ant-design-vue', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  };
});

import {
  CONFIG_ITEMS_QUERY_KEY,
  useConfigItems,
  useConfigUpdate,
  useConfigReset,
  validateNumericRange,
  validateStringList,
  type ConfigItemView,
} from '@/hooks/usePlatformConfig';

function makeItem(overrides: Partial<ConfigItemView> = {}): ConfigItemView {
  return {
    key: 'rag.retrieval.topK',
    namespace: 'rag.retrieval',
    valueType: 'INTEGER',
    value: '8',
    defaultValue: '5',
    description: '检索召回条数，影响上下文长度与 token 成本',
    version: 3,
    updatedAt: '2026-09-01T10:00:00',
    updatedBy: 'admin',
    deleted: false,
    sensitive: false,
    minValue: '1',
    maxValue: '50',
    allowedValues: null,
    ...overrides,
  };
}

/** 在无 @vue/test-utils 依赖下挂载一个使用 vue-query 的组合式函数 */
function withSetup<T>(composable: () => T): { result: () => T; queryClient: QueryClient } {
  let result!: T;
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const host = document.createElement('div');
  const App = defineComponent({
    setup() {
      result = composable();
      return () => h('div');
    },
  });
  const app = createApp(App);
  app.use(VueQueryPlugin, { queryClient });
  app.mount(host);
  return { result: () => result, queryClient };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useConfigItems', () => {
  it('加载配置项并按命名空间分组排序', async () => {
    sdkState.getPlatformConfigItems.mockResolvedValue({
      data: {
        code: 1,
        data: [
          makeItem(),
          makeItem({ key: 'ai.model.name', namespace: 'ai.model', valueType: 'STRING', value: 'glm-4' }),
          makeItem({ key: 'ai.prompt.system', namespace: 'ai.prompt', value: '你是助手' }),
        ],
      },
    });
    const { result } = withSetup(() => useConfigItems());
    await vi.waitFor(() => expect(result().query.isSuccess.value).toBe(true));
    expect(sdkState.getPlatformConfigItems).toHaveBeenCalledWith(
      expect.objectContaining({ client: expect.anything() }),
    );
    expect(result().grouped.value.map((g) => g.namespace)).toEqual([
      'ai.prompt',
      'ai.model',
      'rag.retrieval',
    ]);
    expect(result().items.value).toHaveLength(3);
  });

  it('关键字搜索过滤键名/描述并保留匹配组', async () => {
    sdkState.getPlatformConfigItems.mockResolvedValue({
      data: {
        code: 1,
        data: [
          makeItem(),
          makeItem({ key: 'ai.model.name', namespace: 'ai.model', valueType: 'STRING' }),
        ],
      },
    });
    const kw: Ref<string> = ref('topk');
    const { result } = withSetup(() => useConfigItems(kw));
    await vi.waitFor(() => expect(result().query.isSuccess.value).toBe(true));
    expect(result().grouped.value).toHaveLength(1);
    expect(result().grouped.value[0].items[0].key).toBe('rag.retrieval.topK');
  });

  it('新命名空间插在 rag.retrieval 之后、rag.intent 之前且标签为中文', async () => {
    sdkState.getPlatformConfigItems.mockResolvedValue({
      data: {
        code: 1,
        data: [
          makeItem({ key: 'rag.retrieval.topK' }),
          makeItem({ key: 'rag.context.neighbors', namespace: 'rag.context' }),
          makeItem({ key: 'rag.chunking.strategy', namespace: 'rag.chunking' }),
          makeItem({ key: 'rag.query.hyde.enabled', namespace: 'rag.query' }),
          makeItem({ key: 'rag.intent.categories', namespace: 'rag.intent' }),
        ],
      },
    });
    const { result } = withSetup(() => useConfigItems());
    await vi.waitFor(() => expect(result().query.isSuccess.value).toBe(true));
    expect(result().grouped.value.map((g) => g.namespace)).toEqual([
      'rag.retrieval',
      'rag.context',
      'rag.chunking',
      'rag.query',
      'rag.intent',
    ]);
    expect(result().grouped.value.map((g) => g.label)).toEqual([
      'RAG 检索',
      'RAG 上下文',
      'RAG 切分',
      'RAG 查询增强',
      'RAG 意图',
    ]);
  });

  it('只有旧命名空间数据时分组保持既有顺序不变', async () => {
    sdkState.getPlatformConfigItems.mockResolvedValue({
      data: {
        code: 1,
        data: [
          makeItem({ key: 'ai.model.name', namespace: 'ai.model', valueType: 'STRING' }),
          makeItem({ key: 'ai.prompt.system', namespace: 'ai.prompt', value: '你是助手' }),
          makeItem({ key: 'rag.retrieval.topK' }),
        ],
      },
    });
    const { result } = withSetup(() => useConfigItems());
    await vi.waitFor(() => expect(result().query.isSuccess.value).toBe(true));
    expect(result().grouped.value.map((g) => g.namespace)).toEqual([
      'ai.prompt',
      'ai.model',
      'rag.retrieval',
    ]);
    expect(result().grouped.value.map((g) => g.label)).toEqual(['AI 提示词', 'AI 模型', 'RAG 检索']);
  });

  it('接口错误进入 isError 状态', async () => {
    sdkState.getPlatformConfigItems.mockRejectedValue(new Error('boom'));
    const { result } = withSetup(() => useConfigItems());
    await vi.waitFor(() => expect(result().query.isError.value).toBe(true));
    expect(result().items.value).toEqual([]);
  });
});

describe('useConfigUpdate', () => {
  it('保存成功后刷新列表缓存', async () => {
    sdkState.postPlatformConfigItems.mockResolvedValue({
      data: { code: 1, data: makeItem({ value: '10', version: 4 }) },
    });
    const { result, queryClient } = withSetup(() => useConfigUpdate());
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const saved = await result().mutateAsync({
      key: 'rag.retrieval.topK',
      value: '10',
      remark: '调高召回',
    });
    expect(saved.value).toBe('10');
    expect(sdkState.postPlatformConfigItems).toHaveBeenCalledWith(
      expect.objectContaining({
        body: { key: 'rag.retrieval.topK', value: '10', remark: '调高召回' },
      }),
    );
    await nextTick();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: [CONFIG_ITEMS_QUERY_KEY] });
  });

  it('校验失败时抛出后端可读错误信息', async () => {
    sdkState.postPlatformConfigItems.mockRejectedValue(new Error('必须是整数，实际值：abc'));
    const { result } = withSetup(() => useConfigUpdate());
    await expect(result().mutateAsync({ key: 'x', value: 'abc' })).rejects.toThrow('必须是整数');
  });
});

describe('useConfigReset', () => {
  it('软删恢复默认走 DELETE items/{key}', async () => {
    sdkState.deletePlatformConfigItemsByKey.mockResolvedValue({
      data: { code: 1, data: makeItem({ value: null, version: null }) },
    });
    const { result } = withSetup(() => useConfigReset());
    await result().mutateAsync({ key: 'rag.retrieval.topK', remark: '回默认' });
    expect(sdkState.deletePlatformConfigItemsByKey).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { key: 'rag.retrieval.topK' },
        query: { remark: '回默认' },
      }),
    );
  });
});

describe('validateNumericRange', () => {
  it('整数范围校验', () => {
    expect(validateNumericRange('8', 'INTEGER', '1', '50')).toBeNull();
    expect(validateNumericRange('0', 'INTEGER', '1', '50')).toBe('不能小于 1');
    expect(validateNumericRange('51', 'INTEGER', '1', '50')).toBe('不能大于 50');
    expect(validateNumericRange('1.5', 'INTEGER', '1', '50')).toBe('必须是整数');
    expect(validateNumericRange('abc', 'INTEGER', null, null)).toBe('必须是有效数值');
    expect(validateNumericRange('0.75', 'DOUBLE', '0', '1')).toBeNull();
  });
});

describe('validateStringList', () => {
  it('JSON 数组文本校验', () => {
    expect(validateStringList('["a","b"]')).toBeNull();
    expect(validateStringList('[]')).toBe('必须是非空 JSON 字符串数组');
    expect(validateStringList('["a", 1]')).toBe('数组条目必须为非空字符串');
    expect(validateStringList('not json')).toBe('必须是合法 JSON（如 ["类目A","类目B"]）');
  });
});

describe('query keys', () => {
  it('导出稳定 query key 常量', () => {
    expect(CONFIG_ITEMS_QUERY_KEY).toBe('platformConfigItems');
  });
});
