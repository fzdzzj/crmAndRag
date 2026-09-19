import apiClient, { axiosInstance } from '@/api/apiClient';
import {
  getAssistApplications,
  getAssistByIdAttachments,
  getAssistByIdDetail,
  getAssistMy,
  postAssistAppend,
  postAssistReapply,
  putAssist,
} from '@/api/axios';
import type {
  GetAssistByIdAttachmentsResponse,
  GetAssistApplicationsResponse,
  GetAssistMyResponse,
  AssistAppendDto,
  PostAssistReapplyData,
  PutAssistData,
} from '@/api/axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref, unref, type MaybeRef } from 'vue';

const ASSIST_QUERY_KEY = 'assists';

export type AssistRecord = NonNullable<
  NonNullable<GetAssistMyResponse['data']>['records']
>[number];
export type AssistApplicationRecord = NonNullable<
  NonNullable<GetAssistApplicationsResponse['data']>['records']
>[number];
export type AssistHandlePayload = NonNullable<PutAssistData['body']>;
export type AssistReapplyPayload = NonNullable<PostAssistReapplyData['body']>;
export type AssistAppendPayload = AssistAppendDto;
export type AssistApplyItem = NonNullable<
  NonNullable<AssistReapplyPayload['assistApplyList']>
>[number];
export type AssistAttachment = NonNullable<GetAssistByIdAttachmentsResponse['data']>[number];

export function getDuplicateAssistUserIds(items: AssistApplyItem[]): number[] {
  const counts = new Map<number, number>();
  for (const item of items) {
    if (item.assistUserId != null) counts.set(item.assistUserId, (counts.get(item.assistUserId) ?? 0) + 1);
  }
  return [...counts.entries()].filter(([, count]) => count > 1).map(([id]) => id);
}
export type AssistRelatedType = 'company' | 'contact' | 'approval' | 'activity' | 'task';

/**
 * 协助关联详情只允许通过 assistId 查询，避免前端把普通 recordId 拼进列表筛选后越权或丢失记录。
 * 公司、联系人以及审批/活动/任务均使用 assistId 受控详情接口；终态详情仍由协助详情快照负责展示。
 */
export async function fetchAssistRelatedDetail(
  assistId: number,
  type: AssistRelatedType,
): Promise<unknown> {
  const response = await axiosInstance.get(`/assist/${assistId}/${type}`);
  return response.data?.data;
}

/** 协助记录详情：终态详情由后端返回冻结快照，常规详情页也使用同一入口。 */
export async function fetchAssistRecordDetail(assistId: number): Promise<AssistRecord | null> {
  const response = await axiosInstance.get(`/assist/${assistId}/detail`);
  return (response.data?.data ?? null) as AssistRecord | null;
}

export async function fetchAssistSourceAttachments(
  assistId: number,
  type: 'activity' | 'task',
): Promise<AssistAttachment[]> {
  const response = await axiosInstance.get(`/assist/${assistId}/${type}/attachments`);
  return (response.data?.data ?? []) as AssistAttachment[];
}

export async function fetchAssistAttachments(assistId: number): Promise<AssistAttachment[]> {
  const response = await axiosInstance.get(`/assist/${assistId}/attachments`);
  return (response.data?.data ?? []) as AssistAttachment[];
}

export function useQueryAssistOpportunity(id: MaybeRef<number | undefined>) {
  const assistId = computed(() => unref(id));
  return useQuery({
    queryKey: computed(() => [ASSIST_QUERY_KEY, 'opportunity', assistId.value]),
    queryFn: async () => {
      const response = await axiosInstance.get(`/assist/${assistId.value}/opportunity`);
      return response.data?.data;
    },
    enabled: computed(() => assistId.value != null),
  });
}

export function useQueryMyAssists(params?: {
  assistStatus?: number;
  pageNum?: number;
  pageSize?: number;
}) {
  const pageNum = ref(params?.pageNum ?? 1);
  const pageSize = ref(params?.pageSize ?? 10);
  const assistStatus = ref<number | undefined>(params?.assistStatus);
  const query = useQuery({
    queryKey: computed(() => [
      ASSIST_QUERY_KEY,
      pageNum.value,
      pageSize.value,
      assistStatus.value,
    ]),
    queryFn: () =>
      getAssistMy({
        client: apiClient,
        query: {
          pageNum: pageNum.value,
          pageSize: pageSize.value,
          assistStatus: assistStatus.value,
        },
      }),
    select: (res) => res.data?.data,
  });
  return { pageNum, pageSize, assistStatus, ...query };
}

export function useHandleAssist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: AssistHandlePayload) =>
      putAssist({ client: apiClient, body }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ASSIST_QUERY_KEY] });
    },
  });
}

export function useQueryMyApplications(params?: {
  assistStatus?: number;
  pageNum?: number;
  pageSize?: number;
}) {
  const pageNum = ref(params?.pageNum ?? 1);
  const pageSize = ref(params?.pageSize ?? 10);
  const assistStatus = ref<number | undefined>(params?.assistStatus);
  const query = useQuery({
    queryKey: computed(() => [
      ASSIST_QUERY_KEY,
      'applications',
      pageNum.value,
      pageSize.value,
      assistStatus.value,
    ]),
    queryFn: () =>
      getAssistApplications({
        client: apiClient,
        query: {
          pageNum: pageNum.value,
          pageSize: pageSize.value,
          assistStatus: assistStatus.value,
        },
      }),
    select: (res) => res.data?.data,
  });
  return { pageNum, pageSize, assistStatus, ...query };
}

export function useReapplyAssist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: AssistReapplyPayload) =>
      postAssistReapply({ client: apiClient, body }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ASSIST_QUERY_KEY] });
    },
  });
}

export function useAppendAssist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: AssistAppendPayload) =>
      postAssistAppend({ client: apiClient, body }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ASSIST_QUERY_KEY] });
    },
  });
}

export function useQueryAssistDetail(id: MaybeRef<number | undefined>) {
  const assistId = computed(() => unref(id));
  return useQuery({
    queryKey: computed(() => [ASSIST_QUERY_KEY, 'detail', assistId.value]),
    queryFn: () => getAssistByIdDetail({
      client: apiClient,
      path: { id: assistId.value! },
    }),
    select: (res) => res.data?.data,
    enabled: computed(() => assistId.value != null),
  });
}

export function useQueryAssistAttachments(id: MaybeRef<number | undefined>) {
  const assistId = computed(() => unref(id));
  return useQuery({
    queryKey: computed(() => [ASSIST_QUERY_KEY, 'attachments', assistId.value]),
    queryFn: () => getAssistByIdAttachments({
      client: apiClient,
      path: { id: assistId.value! },
    }),
    select: (res) => res.data?.data ?? [],
    enabled: computed(() => assistId.value != null),
    // 下载地址按当前响应使用，不写入持久化缓存；详情关闭后立即允许回收。
    staleTime: 0,
    gcTime: 0,
  });
}

export function useUploadAssistAttachment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, files }: { id: number; files: File[] }) => {
      // 当前后端是 @ModelAttribute multipart，OpenAPI 旧描述仍把 attachments 标成 query。
      // 直接构造 FormData，避免生成 SDK 把文件误编码为 URL 查询参数。
      const formData = new FormData();
      for (const [index, file] of files.entries()) {
        formData.append(`attachments[${index}].fileData`, file);
        formData.append(`attachments[${index}].fileName`, file.name);
        if (file.type) {
          formData.append(`attachments[${index}].fileType`, file.type);
        }
      }
      return axiosInstance.post(`/assist/${id}/attachments`, formData);
    },
    onSuccess: (_, variables) => {
      void queryClient.invalidateQueries({
        queryKey: [ASSIST_QUERY_KEY, 'attachments', variables.id],
      });
    },
  });
}

/** 待协助期间上传来源活动/任务附件，不依赖普通模块权限。 */
export function useUploadAssistSourceAttachment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, files }: { id: number; files: File[] }) => {
      const formData = new FormData();
      files.forEach((file, index) => {
        formData.append(`attachments[${index}].fileData`, file);
        formData.append(`attachments[${index}].fileName`, file.name);
        formData.append(`attachments[${index}].fileType`, file.type || 'application/octet-stream');
      });
      return axiosInstance.post(`/assist/${id}/source-attachments`, formData);
    },
    onSuccess: (_, variables) => {
      void queryClient.invalidateQueries({ queryKey: [ASSIST_QUERY_KEY, 'source-attachments', variables.id] });
      void queryClient.invalidateQueries({ queryKey: [ASSIST_QUERY_KEY, 'detail', variables.id] });
    },
  });
}

export function useApplyAssist() {
  return useMutation({
    mutationFn: async ({ modelName, recordId, applyList }: {
      modelName: string;
      recordId: number;
      applyList: AssistApplyItem[];
    }) => axiosInstance.post('/assist/apply', applyList, {
      params: { modelName, recordId },
    }),
  });
}
