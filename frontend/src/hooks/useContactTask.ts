import apiClient from '@/api/apiClient';
import {
  deleteContactTaskBatch,
  deleteContactTaskById,
  getContactTaskById,
  getContactTaskByIdAttachments,
  getTaskCommentTaskByTaskId,
  postContactTaskCreate,
  postContactTaskQuery,
  postTaskCommentCreate,
  putContactTaskBatch,
  putContactTaskUpdate,
} from '@/api/axios';
import type {
  PostContactTaskCreateData,
  PostContactTaskQueryData,
  PutContactTaskBatchData,
  PutContactTaskUpdateData,
} from '@/api/axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref, watchEffect, type Ref } from 'vue';
import { createQueryWithURLSearchParamsAsync } from './common/useURLSearchParamsAsync';

const CONTACT_TASK_QUERY_KEY = 'contactTasks';

type ContactTaskPayload = NonNullable<PostContactTaskCreateData['body']>;
type ContactTaskQueryPayload = NonNullable<PostContactTaskQueryData['body']>;
type ContactTaskUpdatePayload = NonNullable<PutContactTaskUpdateData['body']>;
type ContactTaskBatchPayload = NonNullable<PutContactTaskBatchData['body']>;

export interface ContactTaskSearchFilters {
  taskTitle?: string;
  taskContent?: string;
  taskType?: string;
  taskStatus?: number;
  priority?: number;
  companyId?: number;
  contactId?: number;
  opportunityId?: number;
  minDeadline?: string;
  maxDeadline?: string;
}

export function useQueryContactTasks(params?: {
  all?: boolean;
  filters?: ContactTaskSearchFilters;
  current?: number;
  pageSize?: number;
}) {
  const { all = false } = params ?? {};
  const current = ref(params?.current || 1);
  const pageSize = ref(params?.pageSize || 10);
  const filters = ref<ContactTaskSearchFilters>({
    taskTitle: params?.filters?.taskTitle,
    taskContent: params?.filters?.taskContent,
    taskType: params?.filters?.taskType,
    taskStatus: params?.filters?.taskStatus,
    priority: params?.filters?.priority,
    companyId: params?.filters?.companyId,
    contactId: params?.filters?.contactId,
    opportunityId: params?.filters?.opportunityId,
    minDeadline: params?.filters?.minDeadline,
    maxDeadline: params?.filters?.maxDeadline,
  });

  const query = useQuery({
    queryKey: computed(() => [
      CONTACT_TASK_QUERY_KEY,
      current.value,
      pageSize.value,
      filters.value,
      all,
    ]),
    queryFn: () => {
      const payload: ContactTaskQueryPayload = {
        taskTitle: filters.value.taskTitle,
        taskContent: filters.value.taskContent,
        taskType: filters.value.taskType,
        companyId: filters.value.companyId,
        contactId: filters.value.contactId,
        opportunityId: filters.value.opportunityId,
        priority: filters.value.priority,
        status: filters.value.taskStatus,
      };

      return postContactTaskQuery({
        client: apiClient,
        query: {
          pageNum: current.value,
          pageSize: pageSize.value,
        },
        body: payload,
      });
    },
    select: (res) => res.data?.data,
  });

  watchEffect(() => {
    if (all) {
      pageSize.value = query.data.value?.total ?? pageSize.value;
    }
  });

  function setFilters(next: Partial<ContactTaskSearchFilters>) {
    filters.value = { ...filters.value, ...next };
  }

  function reset() {
    filters.value = {};
  }

  return {
    current,
    pageSize,
    filters,
    setFilters,
    reset,
    ...query,
  };
}

export const useQueryContactTasksWithURLSearchParamsAsync =
  createQueryWithURLSearchParamsAsync({
    queryHook: useQueryContactTasks,
    depGetter: (query) => ({
      current: query.current.value,
      pageSize: query.pageSize.value,
      filters: query.filters.value,
    }),
  });

export function useCreateContactTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: ContactTaskPayload) =>
      postContactTaskCreate({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [CONTACT_TASK_QUERY_KEY] });
    },
  });
}

export function useUpdateContactTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: ContactTaskUpdatePayload) =>
      putContactTaskUpdate({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [CONTACT_TASK_QUERY_KEY] });
    },
  });
}

export function useBatchUpdateContactTasks() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: ContactTaskBatchPayload) =>
      putContactTaskBatch({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [CONTACT_TASK_QUERY_KEY] });
    },
  });
}

export function useDeleteContactTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteContactTaskById({ client: apiClient, path: { id } }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [CONTACT_TASK_QUERY_KEY] });
    },
  });
}

export function useBatchDeleteContactTasks() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) => deleteContactTaskBatch({ client: apiClient, body: ids }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [CONTACT_TASK_QUERY_KEY] });
    },
  });
}

export function useQueryTaskComments(taskId: number) {
  return useQuery({
    queryKey: ['taskComments', taskId],
    queryFn: () => getTaskCommentTaskByTaskId({ client: apiClient, path: { taskId } }),
    select: (res) => res.data?.data,
    enabled: computed(() => !!taskId),
  });
}

export function useCreateTaskComment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: { taskId: number; content: string }) =>
      postTaskCommentCreate({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['taskComments'] });
    },
  });
}

export function useQueryContactTaskDetail(id: number | Ref<number | null>) {
  const idRef = typeof id === 'number' ? ref(id) : id;

  return useQuery({
    queryKey: computed(() => [CONTACT_TASK_QUERY_KEY, 'detail', idRef.value]),
    queryFn: async () => {
      const idValue = idRef.value;
      if (!idValue) {
        throw new Error('Invalid ID');
      }
      const res = await getContactTaskById({ client: apiClient, path: { id: idValue } });
      return res.data?.data;
    },
    enabled: computed(() => !!idRef.value),
  });
}

export function useQueryContactTaskAttachments(
  taskId: number | Ref<number | null>,
) {
  const idRef = typeof taskId === 'number' ? ref(taskId) : taskId;

  return useQuery({
    queryKey: computed(() => [CONTACT_TASK_QUERY_KEY, 'attachments', idRef.value]),
    queryFn: async () => {
      const idValue = idRef.value;
      if (!idValue) {
        throw new Error('Invalid task ID');
      }
      const res = await getContactTaskByIdAttachments({
        client: apiClient,
        path: { id: idValue },
      });
      return res.data?.data ?? [];
    },
    enabled: computed(() => !!idRef.value),
  });
}
