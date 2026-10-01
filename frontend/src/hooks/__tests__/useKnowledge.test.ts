// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type Ref } from 'vue';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { message } from 'ant-design-vue';

vi.mock('@/api/apiClient.ts', () => ({
  default: { name: 'mockApiClient' },
}));

const sdkState = vi.hoisted(() => ({
  getKnowledgeBases: vi.fn(),
  getKnowledgeFiles: vi.fn(),
  postKnowledgeFiles: vi.fn(),
  deleteKnowledgeFilesById: vi.fn(),
  postKnowledgeFilesByIdReingest: vi.fn(),
  postKnowledgeRetrievalTest: vi.fn(),
}));

vi.mock('@/api/axios/sdk.gen.ts', () => ({
  getKnowledgeBases: (...args: unknown[]) => sdkState.getKnowledgeBases(...(args as [])),
  getKnowledgeFiles: (...args: unknown[]) => sdkState.getKnowledgeFiles(...(args as [])),
  postKnowledgeFiles: (...args: unknown[]) => sdkState.postKnowledgeFiles(...(args as [])),
  deleteKnowledgeFilesById: (...args: unknown[]) =>
    sdkState.deleteKnowledgeFilesById(...(args as [])),
  postKnowledgeFilesByIdReingest: (...args: unknown[]) =>
    sdkState.postKnowledgeFilesByIdReingest(...(args as [])),
  postKnowledgeRetrievalTest: (...args: unknown[]) =>
    sdkState.postKnowledgeRetrievalTest(...(args as [])),
}));

vi.mock('ant-design-vue', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  };
});

import {
  useKnowledgeBases,
  useKnowledgeFiles,
  useKnowledgeUpload,
  useKnowledgeFileRemove,
  useKnowledgeReingest,
  useRetrievalTest,
  type KnowledgeBaseVO,
  type KnowledgeFileVO,
} from '@/hooks/useKnowledge';

/** ant-design-vue 全局 Toast 的 mock 句柄（显式收窄，规避 @typescript-eslint/unbound-method） */
const toast = message as unknown as { success: Mock; error: Mock; warning: Mock };

function makeBase(overrides: Partial<KnowledgeBaseVO> = {}): KnowledgeBaseVO {
  return {
    id: 1,
    name: 'kb-default',
    displayName: '默认知识库',
    visibility: 'PUBLIC',
    ownerUserId: 'u-001',
    createTime: '2026-09-01T10:00:00',
    ...overrides,
  };
}

function makeFile(overrides: Partial<KnowledgeFileVO> = {}): KnowledgeFileVO {
  return {
    id: 1,
    documentId: 'doc-uuid-123',
    originalFilename: '产品手册.pdf',
    fileType: 'PDF',
    status: 'READY',
    segmentCount: 12,
    vectorCount: 12,
    knowledgeBaseId: 101,
    createTime: '2026-09-01T10:00:00',
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

describe('useKnowledgeBases', () => {
  it('成功加载知识库列表并映射 KnowledgeBaseVO 字段', async () => {
    sdkState.getKnowledgeBases.mockResolvedValue({
      data: {
        code: 1,
        data: [makeBase(), makeBase({ id: 2, name: 'kb-2', displayName: '售后库' })],
      },
    });
    const { result } = withSetup(() => useKnowledgeBases());
    await vi.waitFor(() => expect(result().isSuccess.value).toBe(true));
    expect(sdkState.getKnowledgeBases).toHaveBeenCalledWith(
      expect.objectContaining({ client: expect.anything() }),
    );
    expect(result().data.value).toHaveLength(2);
    expect(result().data.value?.[1]).toMatchObject({
      id: 2,
      name: 'kb-2',
      displayName: '售后库',
      visibility: 'PUBLIC',
    });
  });

  it('接口异常时 query 进入 isError 状态', async () => {
    sdkState.getKnowledgeBases.mockRejectedValue(new Error('boom'));
    const { result } = withSetup(() => useKnowledgeBases());
    await vi.waitFor(() => expect(result().isError.value).toBe(true));
  });
});

describe('useKnowledgeFiles', () => {
  it('未传 kbId 时发起全量查询且 query 参数为 undefined', async () => {
    sdkState.getKnowledgeFiles.mockResolvedValue({
      data: { code: 1, data: [makeFile()] },
    });
    const { result } = withSetup(() => useKnowledgeFiles());
    await vi.waitFor(() => expect(result().isSuccess.value).toBe(true));
    expect(sdkState.getKnowledgeFiles).toHaveBeenCalledWith(
      expect.objectContaining({ client: expect.anything(), query: undefined }),
    );
    expect(result().data.value).toHaveLength(1);
  });

  it('传入 ref(kbId) 时透传 query 参数 { kbId }', async () => {
    sdkState.getKnowledgeFiles.mockResolvedValue({
      data: { code: 1, data: [makeFile({ knowledgeBaseId: 101 })] },
    });
    const kbId: Ref<number | null> = ref(101);
    const { result } = withSetup(() => useKnowledgeFiles(kbId));
    await vi.waitFor(() => expect(result().isSuccess.value).toBe(true));
    expect(sdkState.getKnowledgeFiles).toHaveBeenCalledWith(
      expect.objectContaining({ client: expect.anything(), query: { kbId: 101 } }),
    );
  });

  it('响应式更新 kbId 时以新参数重新发起查询', async () => {
    sdkState.getKnowledgeFiles.mockResolvedValue({
      data: { code: 1, data: [] },
    });
    const kbId: Ref<number | null> = ref(101);
    const { result } = withSetup(() => useKnowledgeFiles(kbId));
    await vi.waitFor(() =>
      expect(sdkState.getKnowledgeFiles).toHaveBeenCalledWith(
        expect.objectContaining({ query: { kbId: 101 } }),
      ),
    );
    const callsBefore = sdkState.getKnowledgeFiles.mock.calls.length;
    kbId.value = 202;
    await vi.waitFor(() =>
      expect(sdkState.getKnowledgeFiles).toHaveBeenCalledWith(
        expect.objectContaining({ query: { kbId: 202 } }),
      ),
    );
    expect(sdkState.getKnowledgeFiles.mock.calls.length).toBeGreaterThan(callsBefore);
    expect(result().isSuccess.value).toBe(true);
  });
});

describe('useKnowledgeUpload', () => {
  it('上传成功触发 knowledgeFiles 缓存失效并弹出成功提示', async () => {
    sdkState.postKnowledgeFiles.mockResolvedValue({
      data: {
        code: 1,
        data: { uploadedFileId: 1, documentId: 'doc-uuid-123', chunkCount: 12, vectorCount: 12 },
      },
    });
    const { result, queryClient } = withSetup(() => useKnowledgeUpload());
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const file = new File(['demo'], '产品手册.pdf', { type: 'application/pdf' });
    const saved = await result().mutateAsync({ file, kbId: 101 });
    expect(sdkState.postKnowledgeFiles).toHaveBeenCalledWith(
      expect.objectContaining({ body: { file, kbId: 101 } }),
    );
    expect(saved.documentId).toBe('doc-uuid-123');
    await nextTick();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['knowledgeFiles'] });
    expect(toast.success).toHaveBeenCalledWith('上传成功，摄取中...');
  });

  it('上传失败时弹出错误提示', async () => {
    sdkState.postKnowledgeFiles.mockRejectedValue(new Error('文件格式不支持'));
    const { result } = withSetup(() => useKnowledgeUpload());
    const file = new File(['demo'], 'bad.exe');
    await expect(result().mutateAsync({ file, kbId: 101 })).rejects.toThrow('文件格式不支持');
    expect(toast.error).toHaveBeenCalledWith('文件格式不支持');
  });
});

describe('useKnowledgeFileRemove', () => {
  it('删除成功触发缓存失效并弹出成功提示', async () => {
    sdkState.deleteKnowledgeFilesById.mockResolvedValue({ data: { code: 1, data: true } });
    const { result, queryClient } = withSetup(() => useKnowledgeFileRemove());
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const removed = await result().mutateAsync('doc-uuid-123');
    expect(sdkState.deleteKnowledgeFilesById).toHaveBeenCalledWith(
      expect.objectContaining({ path: { id: 'doc-uuid-123' } }),
    );
    expect(removed).toBe(true);
    await nextTick();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['knowledgeFiles'] });
    expect(toast.success).toHaveBeenCalledWith('删除成功');
  });

  it('删除失败时弹出错误提示', async () => {
    sdkState.deleteKnowledgeFilesById.mockRejectedValue(new Error('文档不存在或无权限'));
    const { result } = withSetup(() => useKnowledgeFileRemove());
    await expect(result().mutateAsync('doc-x')).rejects.toThrow('文档不存在或无权限');
    expect(toast.error).toHaveBeenCalledWith('文档不存在或无权限');
  });
});

describe('useKnowledgeReingest', () => {
  it('重建成功触发缓存失效并弹出成功提示', async () => {
    sdkState.postKnowledgeFilesByIdReingest.mockResolvedValue({
      data: {
        code: 1,
        data: { uploadedFileId: 1, documentId: 'doc-uuid-456', chunkCount: 8, vectorCount: 8 },
      },
    });
    const { result, queryClient } = withSetup(() => useKnowledgeReingest());
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const reingested = await result().mutateAsync('doc-uuid-456');
    expect(sdkState.postKnowledgeFilesByIdReingest).toHaveBeenCalledWith(
      expect.objectContaining({ path: { id: 'doc-uuid-456' } }),
    );
    expect(reingested.documentId).toBe('doc-uuid-456');
    await nextTick();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['knowledgeFiles'] });
    expect(toast.success).toHaveBeenCalledWith('重建任务已提交，摄取中...');
  });

  it('重建失败时弹出错误提示', async () => {
    sdkState.postKnowledgeFilesByIdReingest.mockRejectedValue(new Error('重建失败'));
    const { result } = withSetup(() => useKnowledgeReingest());
    await expect(result().mutateAsync('doc-x')).rejects.toThrow('重建失败');
    expect(toast.error).toHaveBeenCalledWith('重建失败');
  });
});

describe('useRetrievalTest', () => {
  it('默认参数补齐：topK 回落 5、useVector 回落 false', async () => {
    sdkState.postKnowledgeRetrievalTest.mockResolvedValue({
      data: {
        code: 1,
        data: {
          query: '产品报价',
          topK: 5,
          usedVector: false,
          candidates: [
            {
              chunkId: 'c-1',
              text: '产品报价说明',
              score: 0.92,
              knowledgeBaseId: 1,
              filename: '报价.pdf',
            },
          ],
        },
      },
    });
    const { result } = withSetup(() => useRetrievalTest());
    const res = await result().mutateAsync({ query: '产品报价' });
    expect(sdkState.postKnowledgeRetrievalTest).toHaveBeenCalledWith(
      expect.objectContaining({
        body: { kbId: undefined, query: '产品报价', topK: 5, useVector: false },
      }),
    );
    expect(res.candidates).toHaveLength(1);
    expect(res.topK).toBe(5);
    expect(res.usedVector).toBe(false);
  });

  it('显式全参数透传：kbId / query / topK / useVector', async () => {
    sdkState.postKnowledgeRetrievalTest.mockResolvedValue({
      data: {
        code: 1,
        data: { query: '退款', topK: 8, usedVector: true, candidates: [] },
      },
    });
    const { result } = withSetup(() => useRetrievalTest());
    await result().mutateAsync({ kbId: 1, query: '退款', topK: 8, useVector: true });
    expect(sdkState.postKnowledgeRetrievalTest).toHaveBeenCalledWith(
      expect.objectContaining({
        body: { kbId: 1, query: '退款', topK: 8, useVector: true },
      }),
    );
  });

  it('检索失败时拦截错误并弹出错误提示', async () => {
    sdkState.postKnowledgeRetrievalTest.mockRejectedValue(new Error('检索服务不可用'));
    const { result } = withSetup(() => useRetrievalTest());
    await expect(result().mutateAsync({ query: '产品报价' })).rejects.toThrow('检索服务不可用');
    expect(toast.error).toHaveBeenCalledWith('检索服务不可用');
  });
});
