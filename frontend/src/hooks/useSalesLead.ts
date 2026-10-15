import { useMutation, useQuery, useQueryClient, type MutationOptions } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { message } from 'ant-design-vue';
import apiClient from '@/api/apiClient.ts';
import {
  postSalesLead,
  putSalesLead,
  deleteSalesLeadById,
  postSalesLeadQuery,
  postSalesLeadConvert,
  type PostSalesLeadConvertData,
  type SalesLeadDto,
  type SalesLeadQueryDto,
} from '@/api/axios';

/**
 * 销售线索数据层（add-lead-management-and-conversion-funnel 任务 4.3）。
 *
 * 列表/录入/更新/删除/一键转化，全部复用既有客户管理权限（后端已按 111/113/114/116/117 挂注解），
 * 前端以独立 queryKey 隔离缓存，流转成功后 invalidate 线索列表。
 */
const LEAD_QUERY_KEY = 'sales-lead';

export function useQuerySalesLead(options?: {
  pageNum?: number;
  pageSize?: number;
  name?: string;
  companyName?: string;
  status?: number;
  source?: string;
  ownerId?: number;
}) {
  const pageNum = ref(options?.pageNum || 1);
  const pageSize = ref(options?.pageSize || 10);
  const name = ref(options?.name);
  const companyName = ref(options?.companyName);
  const status = ref<number | undefined>(options?.status);
  const source = ref(options?.source);
  const ownerId = ref<number | undefined>(options?.ownerId);
  const query = useQuery({
    queryKey: computed(() => [
      LEAD_QUERY_KEY,
      pageNum.value,
      pageSize.value,
      name.value,
      companyName.value,
      status.value,
      source.value,
      ownerId.value,
    ]),
    queryFn: () =>
      postSalesLeadQuery({
        client: apiClient,
        query: {
          pageNum: pageNum.value,
          pageSize: pageSize.value,
        },
        body: {
          name: name.value || undefined,
          companyName: companyName.value || undefined,
          status: status.value,
          source: source.value || undefined,
          ownerId: ownerId.value,
        },
      }),
    select: (res) => res.data?.data,
  });
  function setFilters(next: {
    name?: string;
    companyName?: string;
    status?: number;
    source?: string;
    ownerId?: number;
  }) {
    name.value = next.name;
    companyName.value = next.companyName;
    status.value = next.status;
    source.value = next.source;
    ownerId.value = next.ownerId;
    pageNum.value = 1;
  }
  function reset() {
    name.value = undefined;
    companyName.value = undefined;
    status.value = undefined;
    source.value = undefined;
    ownerId.value = undefined;
    pageNum.value = 1;
  }
  return {
    pageNum,
    pageSize,
    setFilters,
    reset,
    ...query,
  };
}

export function useCreateSalesLead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: SalesLeadDto) => postSalesLead({ client: apiClient, body: payload }),
    onSuccess: () => {
      message.success('线索录入成功');
      void queryClient.invalidateQueries({ queryKey: [LEAD_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(error instanceof Error && error.message ? error.message : '录入失败，请稍后重试');
    },
  });
}

export function useUpdateSalesLead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: SalesLeadDto) => putSalesLead({ client: apiClient, body: payload }),
    onSuccess: () => {
      message.success('线索更新成功');
      void queryClient.invalidateQueries({ queryKey: [LEAD_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(error instanceof Error && error.message ? error.message : '更新失败，请稍后重试');
    },
  });
}

export function useDeleteSalesLead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteSalesLeadById({ client: apiClient, path: { id } }),
    onSuccess: () => {
      message.success('线索已删除');
      void queryClient.invalidateQueries({ queryKey: [LEAD_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(error instanceof Error && error.message ? error.message : '删除失败，请稍后重试');
    },
  });
}

export function useConvertSalesLead(
  options?: MutationOptions<unknown, Error, PostSalesLeadConvertData['body']>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: PostSalesLeadConvertData['body']) =>
      postSalesLeadConvert({ client: apiClient, body: payload }),
    ...options,
    onSuccess: (...args) => {
      message.success('线索已转客户/商机');
      void queryClient.invalidateQueries({ queryKey: [LEAD_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: ['customer-pool'] });
      void queryClient.invalidateQueries({ queryKey: ['sales'] });
      options?.onSuccess?.(...args);
    },
  });
}

// re-export 便于页面直接引用类型
export type { SalesLeadDto, SalesLeadQueryDto };