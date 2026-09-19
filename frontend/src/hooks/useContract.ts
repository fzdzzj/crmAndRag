// src/hooks/useContract.ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query';
import { message } from 'ant-design-vue';
import { computed, watchEffect, ref } from 'vue';
import type { Ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import { getContract, deleteContract, getContractById, postContract, putContract, putOrder } from '@/api/axios';
import type { PostContractData, PutContractData, PutOrderData } from '@/api/axios';

//Contracts
export function useQueryContracts(params?: { all?: boolean }) {
  const { all = false } = params ?? {};
  const pageNum = ref(1);
  const pageSize = ref(5);

  const contractsQuery = useQuery({
    queryKey: computed(() => ['contracts', pageNum.value, pageSize.value]),
    queryFn: () =>
      getContract({
        client: apiClient,
        query: {
          pageNum: pageNum.value,
          pageSize: pageSize.value,
        },
      }),
    select: (res) => res.data?.data,
    gcTime: 1000 * 60 * 30,
    staleTime: 1000 * 60 * 5,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });
  watchEffect(() => {
    if (all) {
      pageSize.value = contractsQuery.data.value?.total ?? pageSize.value;
    }
  });
  return {
    pageNum,
    pageSize,
    ...contractsQuery,
  };
}

//删除逻辑
export function useDeleteContract() {
  return useMutation({
    mutationFn: (ids: number[]) =>
      deleteContract({ client: apiClient, body: ids }).then((res) => res.data?.data),
    onSuccess: (result) => {
      if (result) {
        message.success('删除成功');
      }
    },
  });
}

const CONTRACT_DETAIL_QUERY_KEY = 'contract-detail';
export function useContractDetail(id: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [CONTRACT_DETAIL_QUERY_KEY, id.value]),
    enabled: computed(() => !!id.value),
    queryFn: () => getContractById({ client: apiClient, path: { id: id.value! } }),
    select: (res) => res.data?.data,
  });
}

export function useCreateContract() {
  const queryClient = useQueryClient();
  const createContractMutation = useMutation({
    mutationFn: (data: PostContractData['body']) =>
      postContract({ client: apiClient, body: data }).then((res) => res.data?.data),
    onSuccess: () => {
      message.success('创建合同成功');
      void queryClient.invalidateQueries({ queryKey: [CONTRACT_DETAIL_QUERY_KEY] });
    },
  });
  return createContractMutation;
}

export function useUpdateContract() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: PutContractData['body']) =>
      putContract({ client: apiClient, body: data }).then((res) => res.data?.data),
    onSuccess: () => {
      message.success('更新合同成功');
      void queryClient.invalidateQueries({ queryKey: [CONTRACT_DETAIL_QUERY_KEY] });
    },
  });
}

export function useUpdateContractOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orders: PutOrderData['body']) =>
      putOrder({ client: apiClient, body: orders }).then((res) => res.data?.data),
    onSuccess: () => {
      message.success('更新合同订单成功');
      void queryClient.invalidateQueries({ queryKey: [CONTRACT_DETAIL_QUERY_KEY] });
    },
  });
}

export function useQueryContractDetail(id: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [CONTRACT_DETAIL_QUERY_KEY, id.value]),
    enabled: computed(() => !!id.value),
    queryFn: () => getContractById({ client: apiClient, path: { id: id.value! } }),
    select: (res) => res.data?.data,
  });
}
