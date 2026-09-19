import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query';
import { message } from 'ant-design-vue';
import { computed, ref, type Ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import { getContract, getInvoiceInfoContractByContractId, getPaymentRecordContractByContractId, postInvoiceInfoQuery, postPaymentRecordQuery, postInvoiceInfo, postPaymentRecord, deleteInvoiceInfo, deletePaymentRecord, getInvoiceInfoById, getPaymentRecordById, putInvoiceInfo, putPaymentRecord } from '@/api/axios';
import type { PostInvoiceInfoData, PostPaymentRecordData, PutInvoiceInfoData, PutPaymentRecordData } from '@/api/axios';

const INVOICE_QUERY_KEY = 'invoice';
const PAYMENT_QUERY_KEY = 'payment-list';
const CONTRACT_QUERY_KEY = 'contract';

/**
 * 获取合同列表（用于下拉选择）
 */
export function useContractList() {
  return useQuery({
    queryKey: [CONTRACT_QUERY_KEY, 'list'],
    queryFn: () =>
      getContract({
        client: apiClient,
        query: {
          pageNum: 1,
          pageSize: 1000,
        },
      }),
    select: (res) => res.data?.data?.records ?? [],
    staleTime: 1000 * 60 * 10,
  });
}

// 根据合同ID查询开票信息列表
export function useInvoiceByContract(contractId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [INVOICE_QUERY_KEY, contractId.value]),
    enabled: computed(() => !!contractId.value),
    queryFn: () =>
      getInvoiceInfoContractByContractId({ client: apiClient, path: { contractId: contractId.value! } }),
    select: (res) => res.data?.data,
    gcTime: 1000 * 60 * 30,
    staleTime: 1000 * 60 * 5,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });
}

// 根据合同ID查询回款记录列表（用于开票关联）
export function usePaymentListByContract(contractId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => ['contracts', contractId.value]),
    enabled: computed(() => !!contractId.value),
    queryFn: () =>
      getPaymentRecordContractByContractId({ client: apiClient, path: { contractId: contractId.value! } }),
    select: (res) => res.data?.data || [],
    gcTime: 1000 * 60 * 30,
    staleTime: 1000 * 60 * 5,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });
}

// 根据合同ID查询回款记录列表
export function usePaymentByContract(contractId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [PAYMENT_QUERY_KEY, contractId.value]),
    enabled: computed(() => !!contractId.value),
    queryFn: () =>
      getPaymentRecordContractByContractId({ client: apiClient, path: { contractId: contractId.value! } }),
    select: (res) => res.data?.data,
    gcTime: 1000 * 60 * 30,
    staleTime: 1000 * 60 * 5,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });
}

/**
 * 分页查询开票信息列表（财务页面使用）
 */
export function useInvoiceList() {
  const pageNum = ref(1);
  const pageSize = ref(10);

  const query = useQuery({
    queryKey: computed(() => [
      INVOICE_QUERY_KEY,
      'list',
      pageNum.value,
      pageSize.value,
    ]),
    queryFn: () =>
      postInvoiceInfoQuery({
        client: apiClient,
        query: {
          pageNum: pageNum.value,
          pageSize: pageSize.value,
        },
        body: {},
      }),
    select: (res) => res.data?.data,
  });

  return {
    ...query,
    pageNum,
    pageSize,
  };
}

/**
 * 分页查询回款记录列表（财务页面使用）
 */
export function usePaymentList() {
  const pageNum = ref(1);
  const pageSize = ref(10);

  const query = useQuery({
    queryKey: computed(() => [
      PAYMENT_QUERY_KEY,
      'list',
      pageNum.value,
      pageSize.value,
    ]),
    queryFn: () =>
      postPaymentRecordQuery({
        client: apiClient,
        query: {
          pageNum: pageNum.value,
          pageSize: pageSize.value,
        },
        body: {},
      }),
    select: (res) => res.data?.data,
  });

  return {
    ...query,
    pageNum,
    pageSize,
  };
}

// 创建开票信息
export function useCreateInvoice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: PostInvoiceInfoData['body']) =>
      postInvoiceInfo({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('开票信息创建成功');
      void queryClient.invalidateQueries({ queryKey: [INVOICE_QUERY_KEY] });
    },
  });
}

// 创建回款记录
export function useCreatePayment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: PostPaymentRecordData['body']) =>
      postPaymentRecord({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('回款记录创建成功');
      void queryClient.invalidateQueries({ queryKey: [PAYMENT_QUERY_KEY] });
    },
  });
}

// 删除开票信息
export function useDeleteInvoice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) => deleteInvoiceInfo({ client: apiClient, body: ids }),
    onSuccess: () => {
      message.success('删除成功');
      void queryClient.invalidateQueries({ queryKey: [INVOICE_QUERY_KEY] });
    },
  });
}

// 删除回款记录
export function useDeletePayment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) => deletePaymentRecord({ client: apiClient, body: ids }),
    onSuccess: () => {
      message.success('删除成功');
      void queryClient.invalidateQueries({ queryKey: [PAYMENT_QUERY_KEY] });
    },
  });
}

// 获取开票信息详情
export function useInvoiceDetail(invoiceId: Ref<number | null | undefined>) {
  return useQuery({
    queryKey: computed(() => [INVOICE_QUERY_KEY, 'detail', invoiceId.value]),
    enabled: computed(() => !!invoiceId.value),
    queryFn: () => getInvoiceInfoById({ client: apiClient, path: { id: invoiceId.value! } }),
    select: (res) => res.data?.data,
  });
}

// 获取回款记录详情
export function usePaymentDetail(paymentId: Ref<number | null | undefined>) {
  return useQuery({
    queryKey: computed(() => [PAYMENT_QUERY_KEY, 'detail', paymentId.value]),
    enabled: computed(() => !!paymentId.value),
    queryFn: () => getPaymentRecordById({ client: apiClient, path: { id: paymentId.value! } }),
    select: (res) => res.data?.data,
  });
}

// 更新开票信息
export function useUpdateInvoice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: PutInvoiceInfoData['body']) =>
      putInvoiceInfo({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('更新成功');
      void queryClient.invalidateQueries({ queryKey: [INVOICE_QUERY_KEY] });
    },
  });
}

// 更新回款记录
export function useUpdatePayment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: PutPaymentRecordData['body']) =>
      putPaymentRecord({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('更新成功');
      void queryClient.invalidateQueries({ queryKey: [PAYMENT_QUERY_KEY] });
    },
  });
}
