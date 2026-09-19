import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { message } from 'ant-design-vue';
import { computed, ref, watchEffect, type Ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import {
  deleteSalesById,
  getSalesDetailByContractByContractId,
  getSalesDetailByOpportunityId,
  getSalesQuery,
  getSalesStage,
  getSalesStageAttachment,
  postSales,
  postSalesStageApproval,
  putSales,
  putSalesStage,
} from '@/api/axios';
import type {
  GetSalesDetailByContractByContractIdResponse,
  GetSalesDetailByOpportunityIdResponse,
  GetSalesQueryResponse,
  GetSalesStageAttachmentData,
  GetSalesStageData,
  PostSalesData,
  PostSalesStageApprovalData,
  PutSalesData,
  PutSalesStageData,
} from '@/api/axios';
import { createQueryWithURLSearchParamsAsync } from './common/useURLSearchParamsAsync';

const SALE_QUERY_KEY = 'sales';
const STAGE_APPROVAL_QUERY_KEY = 'stage-approvals';

export type SaleRecord = NonNullable<NonNullable<GetSalesQueryResponse['data']>['records']>[number];
export type SalesOpportunityDTO = NonNullable<PostSalesData['body']>;
export type OpportunityDetailVO = NonNullable<GetSalesDetailByOpportunityIdResponse['data']>;
export type OpportunityDetailByContractVO = NonNullable<GetSalesDetailByContractByContractIdResponse['data']>;

export type SaleSearchFilters = {
  opportunityName?: string;
  companyName?: string;
  contactName?: string;
  ownerName?: string;
  creatorName?: string;
  approverName?: string;
  source?: string;
  description?: string;
  stage?: number;
  minAmount?: string;
  maxAmount?: string;
  minCreateTime?: string;
  maxCreateTime?: string;
  minExpectedCloseDate?: string;
  maxExpectedCloseDate?: string;
  ownerId?: number;
  ownerUserName?: string;
};

export function useQuerySales(params?: { all?: boolean }) {
  const { all = false } = params ?? {};
  const current = ref(1);
  const pageSize = ref(5);
  const salesQuery = useQuery({
    queryKey: computed(() => [SALE_QUERY_KEY, current.value, pageSize.value]),
    queryFn: () =>
      getSalesQuery({
        client: apiClient,
        query: {
          pageNum: current.value,
          pageSize: pageSize.value,
        },
      }),
    select: (res) => res.data?.data,
  });

  watchEffect(() => {
    if (all) {
      pageSize.value = salesQuery.data.value?.total ?? pageSize.value;
    }
  });

  return {
    current,
    pageSize,
    ...salesQuery,
  };
}

export function useQuerySalesWithFilters(params?: {
  all?: boolean;
  filters?: SaleSearchFilters;
  current?: number;
  pageSize?: number;
}) {
  const { all = false } = params ?? {};
  const current = ref(params?.current || 1);
  const pageSize = ref(params?.pageSize || 10);
  const filters = ref<SaleSearchFilters>(params?.filters ?? {});

  const salesQuery = useQuery({
    queryKey: computed(() => [
      SALE_QUERY_KEY,
      current.value,
      pageSize.value,
      filters.value,
      all,
    ]),
    queryFn: () =>
      getSalesQuery({
        client: apiClient,
        query: {
          pageNum: current.value,
          pageSize: pageSize.value,
          ...filters.value,
          minAmount: filters.value.minAmount
            ? Number(filters.value.minAmount)
            : undefined,
          maxAmount: filters.value.maxAmount
            ? Number(filters.value.maxAmount)
            : undefined,
        },
      }),
    select: (res) => res.data?.data,
  });

  watchEffect(() => {
    if (all) {
      pageSize.value = salesQuery.data.value?.total ?? pageSize.value;
    }
  });

  function setFilters(next: Partial<SaleSearchFilters>) {
    filters.value = { ...filters.value, ...next };
  }

  function reset() {
    const newFilters = { ...filters.value };
    for (const key in newFilters) {
      if (Object.prototype.hasOwnProperty.call(newFilters, key)) {
        (newFilters as Record<string, unknown>)[key] = undefined;
      }
    }
    filters.value = newFilters;
  }

  return {
    current,
    pageSize,
    filters,
    setFilters,
    reset,
    ...salesQuery,
  };
}

export const useQuerySalesWithURLSearchParamsAsync =
  createQueryWithURLSearchParamsAsync({
    queryHook: useQuerySalesWithFilters,
    depGetter: (query) => ({
      current: query.current.value,
      pageSize: query.pageSize.value,
      filters: query.filters.value,
    }),
  });

export function useDeleteSale() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: number) =>
      (await deleteSalesById({ client: apiClient, path: { id } })).data?.data,
    onSuccess: () => {
      message.success('删除成功');
      void queryClient.invalidateQueries({ queryKey: [SALE_QUERY_KEY] });
    },
  });
}

export function useGetSaleStageAttachment() {
  return useMutation({
    mutationFn: async (data: GetSalesStageAttachmentData['query']) =>
      (await getSalesStageAttachment({ client: apiClient, query: data })).data?.data,
    onSuccess: () => {
      message.success('通过销售机会阶段审批ID获取附件成功');
    },
  });
}

export function useCreateSale() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: SalesOpportunityDTO) =>
      (await postSales({ client: apiClient, body: data })).data?.data,
    onSuccess: () => {
      message.success('创建销售机会成功');
      void queryClient.invalidateQueries({ queryKey: [SALE_QUERY_KEY] });
    },
  });
}

export function useUpdateSale() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: NonNullable<PutSalesData['body']>) =>
      (await putSales({ client: apiClient, body: data })).data?.data,
    onSuccess: () => {
      message.success('修改销售机会成功');
      void queryClient.invalidateQueries({ queryKey: [SALE_QUERY_KEY] });
    },
  });
}

export function useListStageApprovals() {
  return useMutation({
    mutationFn: async (params: GetSalesStageData['query']) =>
      (await getSalesStage({ client: apiClient, query: params })).data?.data,
  });
}

export function useAdvanceStage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: PostSalesStageApprovalData['body']) =>
      (await postSalesStageApproval({ client: apiClient, body: data })).data?.data,
    onSuccess: () => {
      message.success('推进申请已提交');
      void queryClient.invalidateQueries({ queryKey: [SALE_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [STAGE_APPROVAL_QUERY_KEY] });
    },
  });
}

export function useApproveStage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: PutSalesStageData['body']) =>
      (await putSalesStage({ client: apiClient, body: data })).data?.data,
    onSuccess: () => {
      message.success('审批操作成功');
      void queryClient.invalidateQueries({ queryKey: [STAGE_APPROVAL_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [SALE_QUERY_KEY] });
    },
  });
}

export function useGetStageAttachments() {
  return useMutation({
    mutationFn: async (approvalIds: number[]) =>
      (await getSalesStageAttachment({ client: apiClient, query: { approvalIds } })).data?.data,
  });
}

export function useSaleDetail(saleId: Ref<number | null | undefined>) {
  return useQuery({
    queryKey: computed(() => [SALE_QUERY_KEY, 'detail', saleId.value]),
    enabled: computed(() => !!saleId.value),
    queryFn: async () => {
      const res = await getSalesQuery({
        client: apiClient,
        query: {
          pageNum: 1,
          pageSize: 100,
        },
      });
      const record = res.data?.data?.records?.find((r) => r.id === saleId.value);
      return record ?? null;
    },
  });
}

export function useSaleOpportunityDetail(saleOppoId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [SALE_QUERY_KEY, 'opportunity-detail', saleOppoId.value]),
    enabled: computed(() => !!saleOppoId.value),
    queryFn: async () =>
      (await getSalesDetailByOpportunityId({
        client: apiClient,
        path: { opportunityId: saleOppoId.value! },
      })).data?.data,
  });
}

export function useSaleOpportunityDetailByContract(contractId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [
      SALE_QUERY_KEY,
      'opportunity-detail-by-contract',
      contractId.value,
    ]),
    enabled: computed(() => !!contractId.value),
    queryFn: async () =>
      (await getSalesDetailByContractByContractId({
        client: apiClient,
        path: { contractId: contractId.value! },
      })).data?.data,
  });
}
