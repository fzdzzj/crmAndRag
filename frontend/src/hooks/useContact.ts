import { computed, ref, watchEffect, type Ref } from 'vue';
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query';
import { message } from 'ant-design-vue';
import apiClient from '@/api/apiClient.ts';
import { getContactSearch, postContact, putContact, deleteContactLogicalBatch, deleteContact, putContactRecover, getContactTemplate, getContactExcel, postContactList, getContactBirthdayMessageById } from '@/api/axios';
import type {
  DeleteContactData,
  PostContactData,
  PutContactData,
  PutContactRecoverData,
} from '@/api/axios';
import { downloadFromBase64 } from '@/utils/download.ts';
import { createQueryWithURLSearchParamsAsync } from './common/useURLSearchParamsAsync';

export type ContactSearchFilters = {
  id?: number;
  companyId?: number;
  companyName?: string;
  name?: string;
  position?: string;
  phone?: string;
  mobile?: string;
  email?: string;
  gender?: number;
  relationLevel?: number;
  dept?: string;
  isDeleted?: boolean;
};

const CONTACT_QUERY_KEY = 'contacts';
type CreateContactPayload = NonNullable<PostContactData['body']>;
type UpdateContactPayload = NonNullable<PutContactData['body']>;
type DeleteContactPayload = NonNullable<DeleteContactData['body']>;
type RestoreContactPayload = NonNullable<PutContactRecoverData['body']>;

export function useQueryContacts(params?: {
  all?: boolean;
  filters?: ContactSearchFilters;
  current?: number;
  pageSize?: number;
}) {
  const { all = false } = params ?? {};
  const current = ref(params?.current || 1);
  const pageSize = ref(params?.pageSize || 10);
  const isDeleted = ref(false);
  const filters = ref<ContactSearchFilters>({
    id: params?.filters?.id,
    companyId: params?.filters?.companyId,
    companyName: params?.filters?.companyName,
    name: params?.filters?.name,
    position: params?.filters?.position,
    phone: params?.filters?.phone,
    mobile: params?.filters?.mobile,
    email: params?.filters?.email,
    gender: params?.filters?.gender,
    relationLevel: params?.filters?.relationLevel,
    dept: params?.filters?.dept,
  });
  const query = useQuery({
    queryKey: computed(() => [
      CONTACT_QUERY_KEY,
      current.value,
      pageSize.value,
      isDeleted.value,
      filters.value,
      all,
    ]),
    queryFn: () =>
      getContactSearch({
        client: apiClient,
        query: {
          pageNum: current.value,
          pageSize: pageSize.value,
          isDeleted: isDeleted.value,
          ...filters.value,
        },
      }),
    select: (res) => res.data?.data,
  });
  watchEffect(() => {
    if (all) {
      pageSize.value = query.data.value?.total ?? pageSize.value;
    }
  });
  function setFilters(next: Partial<ContactSearchFilters>) {
    filters.value = { ...filters.value, ...next };
  }
  function reset() {
    for (const key in filters.value) {
      if (Object.prototype.hasOwnProperty.call(filters.value, key)) {
        filters.value[key as keyof ContactSearchFilters] = undefined;
      }
    }
  }
  function toggleDeleted() {
    isDeleted.value = !isDeleted.value;
  }
  return {
    current,
    pageSize,
    isDeleted,
    filters,
    setFilters,
    reset,
    toggleDeleted,
    ...query,
  };
}

export const useQueryContactsWithURLSearchParamsAsync =
  createQueryWithURLSearchParamsAsync({
    queryHook: useQueryContacts,
    depGetter: (query) => ({
      pageNum: query.current.value,
      pageSize: query.pageSize.value,
      filters: query.filters.value,
    }),
  });

// 创建联系人
export function useCreateContact() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateContactPayload) =>
      postContact({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('新增成功');
      void queryClient.invalidateQueries({ queryKey: [CONTACT_QUERY_KEY] });
    },
  });
}

// 更新联系人
export function useUpdateContact() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: UpdateContactPayload) =>
      putContact({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('更新成功');
      void queryClient.invalidateQueries({ queryKey: [CONTACT_QUERY_KEY] });
    },
  });
}

// 软删除联系人（移入回收站）
export function useSoftDeleteContact() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) =>
      deleteContactLogicalBatch({ client: apiClient, body: ids }).then((res) => res.data?.data),
    onSuccess: () => {
      message.success('已移入回收站');
      void queryClient.invalidateQueries({ queryKey: [CONTACT_QUERY_KEY] });
    },
  });
}

// 硬删除联系人
export function useHardDeleteContact() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: DeleteContactPayload) =>
      deleteContact({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('硬删除成功');
      void queryClient.invalidateQueries({ queryKey: [CONTACT_QUERY_KEY] });
    },
  });
}

// 恢复联系人
export function useRestoreContact() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: RestoreContactPayload) =>
      putContactRecover({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('恢复成功');
      void queryClient.invalidateQueries({ queryKey: [CONTACT_QUERY_KEY] });
    },
  });
}

// 下载联系人导入模板
export function useExportContactsTemplate() {
  return useMutation({
    mutationFn: () => getContactTemplate({ client: apiClient }),
    onSuccess: (res) => {
      const fileString = String(res.data?.data ?? '');
      downloadFromBase64(fileString, '联系人模板.xlsx');
      message.success('导出成功');
    },
  });
}

// 导出联系人列表
export function useExportContacts() {
  return useMutation({
    mutationFn: () => getContactExcel({ client: apiClient }),
    onSuccess: (res) => {
      const fileString = String(res.data?.data ?? '');
      downloadFromBase64(fileString, '联系人列表.xlsx');
      message.success('导出成功');
    },
  });
}

// 通过 Excel 导入联系人（入参 File）
export function useImportContacts() {
  const queryClient = useQueryClient();
  return useMutation({
      mutationFn: (file: File) => {
        return postContactList({ client: apiClient, body: { file } });
    },
    onSuccess: () => {
      message.success('导入成功');
      void queryClient.invalidateQueries({ queryKey: [CONTACT_QUERY_KEY] });
    },
  });
}

// 联系人生日提醒消息
export function useBirthdayMessage(contactId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => ['contact-birthday', contactId.value]),
    enabled: computed(() => !!contactId.value),
    queryFn: () => getContactBirthdayMessageById({ client: apiClient, path: { id: contactId.value! } }),
    select: (res) => res.data?.data,
  });
}
