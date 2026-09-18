import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, type Ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import { message } from 'ant-design-vue';
import {
  getKnowledgeBases,
  getKnowledgeFiles,
  postKnowledgeFiles,
  deleteKnowledgeFilesById,
  postKnowledgeFilesByIdReingest,
  postKnowledgeRetrievalTest,
} from '@/api/axios/sdk.gen.ts';

// Types matching backend (from proposal + controller + openapi sync)
export interface KnowledgeBaseVO {
  id: number;
  name: string;
  displayName: string;
  visibility: string;
  ownerUserId: string;
  createTime: string;
}

export interface KnowledgeFileVO {
  id: number;
  documentId: string;
  originalFilename: string;
  fileType: string;
  status: string;
  segmentCount: number;
  vectorCount: number;
  knowledgeBaseId: number;
  createTime: string;
  errorMessage?: string;
}

export interface DocumentIngestionResult {
  uploadedFileId: number;
  documentId: string;
  chunkCount: number;
  vectorCount: number;
}

export interface KnowledgeAdminRetrievalRequest {
  kbId?: number | null;
  query: string;
  topK?: number;
  useVector?: boolean;
}

export interface KnowledgeAdminRetrievalResponse {
  query: string;
  topK: number;
  usedVector: boolean;
  candidates: Array<{
    chunkId: string;
    text: string;
    score: number;
    metadata?: Record<string, unknown>;
    knowledgeBaseId?: number;
    filename?: string;
  }>;
}

const KB_QUERY_KEY = 'knowledgeBases';
const FILES_QUERY_KEY = 'knowledgeFiles';

/** hey-api 生成类型为全可选（schema 未标 required），后端 VO 字段恒有值，这里收窄为页面形状 */
/* eslint-disable @typescript-eslint/no-explicit-any */
function narrow<T>(raw: any): T {
  return raw as T;
}
/* eslint-enable @typescript-eslint/no-explicit-any */

export function useKnowledgeBases() {
  return useQuery({
    queryKey: [KB_QUERY_KEY],
    queryFn: async () => {
      const res = await getKnowledgeBases({ client: apiClient });
      return (res.data?.data ?? []).map(narrow<KnowledgeBaseVO>);
    },
  });
}

export function useKnowledgeFiles(kbId?: Ref<number | null | undefined>) {
  return useQuery({
    queryKey: computed(() => [FILES_QUERY_KEY, kbId?.value ?? null]),
    queryFn: async () => {
      const res = await getKnowledgeFiles({
        client: apiClient,
        query: kbId?.value ? { kbId: kbId.value } : undefined,
      });
      return (res.data?.data ?? []).map(narrow<KnowledgeFileVO>);
    },
  });
}

export function useKnowledgeUpload() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { file: File; kbId: number }) => {
      const res = await postKnowledgeFiles({
        client: apiClient,
        body: { file: vars.file, kbId: vars.kbId },
      });
      return narrow<DocumentIngestionResult>(res.data?.data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [FILES_QUERY_KEY] });
      message.success('上传成功，摄取中...');
    },
    onError: (err: unknown) => {
      message.error((err as Error)?.message || '上传失败');
    },
  });
}

export function useKnowledgeFileRemove() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const res = await deleteKnowledgeFilesById({ client: apiClient, path: { id } });
      return Boolean(res.data?.data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [FILES_QUERY_KEY] });
      message.success('删除成功');
    },
    onError: (err: unknown) => message.error((err as Error)?.message || '删除失败'),
  });
}

export function useKnowledgeReingest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const res = await postKnowledgeFilesByIdReingest({ client: apiClient, path: { id } });
      return narrow<DocumentIngestionResult>(res.data?.data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [FILES_QUERY_KEY] });
      message.success('重建任务已提交，摄取中...');
    },
    onError: (err: unknown) => message.error((err as Error)?.message || '重建失败'),
  });
}

export function useRetrievalTest() {
  return useMutation({
    mutationFn: async (req: KnowledgeAdminRetrievalRequest) => {
      const res = await postKnowledgeRetrievalTest({
        client: apiClient,
        body: {
          kbId: req.kbId ?? undefined,
          query: req.query,
          topK: req.topK ?? 5,
          useVector: req.useVector ?? false,
        },
      });
      return narrow<KnowledgeAdminRetrievalResponse>(res.data?.data);
    },
    onError: (err: unknown) => message.error((err as Error)?.message || '检索测试失败'),
  });
}
