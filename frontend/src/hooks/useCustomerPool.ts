import { useMutation, useQuery, useQueryClient, type MutationOptions } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { message } from 'ant-design-vue';
import apiClient from '@/api/apiClient.ts';
import {
  getCompanyPool,
  postCompanyPoolClaimById,
  postCompanyPoolReleaseById,
  postCompanyPoolAssign,
  postCompanyPoolRecycleTrigger,
  type PostCompanyPoolAssignData,
} from '@/api/axios';

/**
 * 客户公海池数据层（add-customer-public-sea-pool 任务 5.x）。
 *
 * 公海池查询/认领/退回/分派/手动回收，全部复用既有客户权限（116 查询 / 113 更新）， 前端以公海专属 queryKey 隔离缓存，流转成功后 invalidate 公海列表。
 */
const POOL_QUERY_KEY = 'customer-pool';

export function useQueryCustomerPool(options?: {
  current?: number;
  pageSize?: number;
  companyName?: string;
  industry?: string;
  grade?: number;
}) {
  const current = ref(options?.current || 1);
  const pageSize = ref(options?.pageSize || 10);
  const companyName = ref(options?.companyName);
  const industry = ref(options?.industry);
  const grade = ref<number | undefined>(options?.grade);
  const query = useQuery({
    queryKey: computed(() => [
      POOL_QUERY_KEY,
      current.value,
      pageSize.value,
      companyName.value,
      industry.value,
      grade.value,
    ]),
    queryFn: () =>
      getCompanyPool({
        client: apiClient,
        query: {
          pageNum: current.value,
          pageSize: pageSize.value,
          companyName: companyName.value || undefined,
          industry: industry.value || undefined,
          grade: grade.value,
        },
      }),
    select: (res) => res.data?.data,
  });
  function setFilters(next: { companyName?: string; industry?: string; grade?: number }) {
    companyName.value = next.companyName;
    industry.value = next.industry;
    grade.value = next.grade;
  }
  return {
    current,
    pageSize,
    setFilters,
    ...query,
  };
}

export function useClaimPoolCustomer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postCompanyPoolClaimById({ client: apiClient, path: { id } }),
    onSuccess: () => {
      message.success('认领成功');
      void queryClient.invalidateQueries({ queryKey: [POOL_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(error instanceof Error && error.message ? error.message : '认领失败，请稍后重试');
    },
  });
}

export function useReleasePoolCustomer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: { id: number; reason?: string }) =>
      postCompanyPoolReleaseById({
        client: apiClient,
        path: { id: payload.id },
        query: { reason: payload.reason || undefined },
      }),
    onSuccess: () => {
      message.success('已退回公海');
      void queryClient.invalidateQueries({ queryKey: [POOL_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(error instanceof Error && error.message ? error.message : '退回失败，请稍后重试');
    },
  });
}

export function useAssignPoolCustomer(
  options?: MutationOptions<unknown, Error, PostCompanyPoolAssignData['body']>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: PostCompanyPoolAssignData['body']) =>
      postCompanyPoolAssign({ client: apiClient, body: payload }),
    ...options,
    onSuccess: (...args) => {
      message.success('分派成功');
      void queryClient.invalidateQueries({ queryKey: [POOL_QUERY_KEY] });
      options?.onSuccess?.(...args);
    },
  });
}

export function useTriggerPoolRecycle() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => postCompanyPoolRecycleTrigger({ client: apiClient }),
    onSuccess: (res) => {
      const count = Number(res.data?.data ?? 0);
      message.success(count > 0 ? `回收完成，共回收 ${count} 个超时客户` : '暂无超时未跟进的客户');
      void queryClient.invalidateQueries({ queryKey: [POOL_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(error instanceof Error && error.message ? error.message : '回收失败，请稍后重试');
    },
  });
}