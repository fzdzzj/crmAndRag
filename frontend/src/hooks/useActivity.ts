import apiClient from '@/api/apiClient';
import {
  deleteBusinessActivity,
  deleteBusinessActivityAttachments,
  deleteBusinessActivityByActivityIdAssociations,
  getBusinessActivityById,
  getBusinessActivityByIdAttachments,
  postBusinessActivity,
  postBusinessActivityByActivityIdContacts,
  postBusinessActivityByActivityIdUsers,
  postBusinessActivityByIdAttachments,
  postBusinessActivityQuery,
  putBusinessActivity,
} from '@/api/axios';
import type {
  DeleteBusinessActivityByActivityIdAssociationsData,
  GetBusinessActivityByIdAttachmentsResponse,
  GetBusinessActivityByIdResponse,
  PostBusinessActivityByActivityIdContactsData,
  PostBusinessActivityByActivityIdUsersData,
  PostBusinessActivityByIdAttachmentsData,
  PostBusinessActivityData,
  PostBusinessActivityQueryData,
  PostBusinessActivityQueryResponse,
  PutBusinessActivityData,
} from '@/api/axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref, watchEffect, type ComputedRef } from 'vue';

import { createQueryWithURLSearchParamsAsync } from './common/useURLSearchParamsAsync';

const ACTIVITY_QUERY_KEY = 'activities';
const ACTIVITY_ATTACHMENTS_QUERY_KEY = 'activity-attachments';

type ActivitySearchFilters = NonNullable<PostBusinessActivityQueryData['body']>;
export type ActivityRecord = NonNullable<
  NonNullable<PostBusinessActivityQueryResponse['data']>['records']
>[number];
export type ActivityDetail = NonNullable<GetBusinessActivityByIdResponse['data']>;
export type ActivityAttachment = NonNullable<GetBusinessActivityByIdAttachmentsResponse['data']>[number];
export type ActivityPayload = NonNullable<PostBusinessActivityData['body']>;
type UpdateActivityPayload = NonNullable<PutBusinessActivityData['body']>;
type ActivityContactPayload = NonNullable<PostBusinessActivityByActivityIdContactsData['body']>;
type ActivityUserPayload = NonNullable<PostBusinessActivityByActivityIdUsersData['body']>;
type DeleteActivityAssociationPayload = NonNullable<
  DeleteBusinessActivityByActivityIdAssociationsData['body']
>;

export type { ActivitySearchFilters };

export function useQueryActivities(params?: {
  all?: boolean;
  filters?: ActivitySearchFilters;
  current?: number;
  pageSize?: number;
}) {
  const { all = false } = params ?? {};
  const current = ref(params?.current || 1);
  const pageSize = ref(params?.pageSize || 10);
  const filters = ref<ActivitySearchFilters>({
    activityTitle: params?.filters?.activityTitle,
    activityContent: params?.filters?.activityContent,
    companyName: params?.filters?.companyName,
    activityType: params?.filters?.activityType,
    activityDuration: params?.filters?.activityDuration,
    opportunityName: params?.filters?.opportunityName,
    remark: params?.filters?.remark,
    creatorName: params?.filters?.creatorName,
    minCreateTime: params?.filters?.minCreateTime,
    maxCreateTime: params?.filters?.maxCreateTime,
    minActivityTime: params?.filters?.minActivityTime,
    maxActivityTime: params?.filters?.maxActivityTime,
  });

  const query = useQuery({
    queryKey: computed(() => [
      ACTIVITY_QUERY_KEY,
      current.value,
      pageSize.value,
      filters.value,
      all,
    ]),
    queryFn: () =>
      postBusinessActivityQuery({
        client: apiClient,
        query: {
          pageSize: pageSize.value,
          pageNum: current.value,
        },
        body: {
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

  function setFilters(next: Partial<ActivitySearchFilters>) {
    filters.value = { ...filters.value, ...next };
  }

  function reset() {
    for (const key in filters.value) {
      if (Object.prototype.hasOwnProperty.call(filters.value, key)) {
        filters.value[key as keyof ActivitySearchFilters] = undefined;
      }
    }
    filters.value = { ...filters.value };
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

export const useQueryActivitiesWithURLSearchParamsAsync =
  createQueryWithURLSearchParamsAsync({
    queryHook: useQueryActivities,
    depGetter: (query) => ({
      current: query.current.value,
      pageSize: query.pageSize.value,
      filters: query.filters.value,
    }),
  });

export function useQueryActivityDetail() {
  return useMutation({
    mutationFn: async (id: number) =>
      (await getBusinessActivityById({ client: apiClient, path: { id } })).data?.data,
  });
}

export function useCreateActivity() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: ActivityPayload) =>
      postBusinessActivity({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ACTIVITY_QUERY_KEY] });
    },
  });
}

export function useUpdateActivity() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: UpdateActivityPayload) =>
      putBusinessActivity({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ACTIVITY_QUERY_KEY] });
    },
  });
}

export function useDeleteActivities() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) => deleteBusinessActivity({ client: apiClient, body: ids }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ACTIVITY_QUERY_KEY] });
    },
  });
}

export function useQueryActivityAttachments(
  activityId: number | undefined | ComputedRef<number | undefined>,
) {
  const id = computed<number | undefined>(() => {
    if (activityId && typeof activityId === 'object' && 'value' in activityId) {
      return activityId.value;
    }
    return activityId;
  });

  return useQuery({
    queryKey: computed(() => [ACTIVITY_ATTACHMENTS_QUERY_KEY, id.value]),
    queryFn: async () => {
      if (!id.value) {
        throw new Error('Activity ID is required');
      }
      const res = await getBusinessActivityByIdAttachments({
        client: apiClient,
        path: { id: id.value },
      });
      return res.data?.data ?? [];
    },
    enabled: computed(() => !!id.value),
  });
}

export function useUploadActivityAttachment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ activityId, files }: { activityId: number; files: File[] }) => {
      const body: Record<string, unknown> = {};
      files.forEach((file, index) => {
        body[`attachments[${index}].fileName`] = file.name;
        body[`attachments[${index}].fileType`] =
          file.type || 'application/octet-stream';
        body[`attachments[${index}].fileData`] = file;
      });
      return postBusinessActivityByIdAttachments({
        client: apiClient,
        path: { id: activityId },
        body: body as NonNullable<PostBusinessActivityByIdAttachmentsData['body']>,
      });
    },
    onSuccess: (_, variables) => {
      void queryClient.invalidateQueries({
        queryKey: [ACTIVITY_ATTACHMENTS_QUERY_KEY, variables.activityId],
      });
    },
  });
}

export function useDeleteActivityAttachment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (attachmentId: number) => {
      const res = await deleteBusinessActivityAttachments({
        client: apiClient,
        query: { attachmentIds: [attachmentId] },
      });
      return res.data?.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [ACTIVITY_ATTACHMENTS_QUERY_KEY],
      });
    },
  });
}

export function useAddActivityContacts() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      activityId,
      contacts,
    }: {
      activityId: number;
      contacts: ActivityContactPayload;
    }) =>
      postBusinessActivityByActivityIdContacts({
        client: apiClient,
        path: { activityId },
        body: contacts,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ACTIVITY_QUERY_KEY] });
    },
  });
}

export function useAddActivityUsers() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      activityId,
      users,
    }: {
      activityId: number;
      users: ActivityUserPayload;
    }) =>
      postBusinessActivityByActivityIdUsers({
        client: apiClient,
        path: { activityId },
        body: users,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ACTIVITY_QUERY_KEY] });
    },
  });
}

export function useDeleteActivityAssociations() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      activityId,
      data,
    }: {
      activityId: number;
      data: DeleteActivityAssociationPayload;
    }) =>
      deleteBusinessActivityByActivityIdAssociations({
        client: apiClient,
        path: { activityId },
        body: data,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ACTIVITY_QUERY_KEY] });
    },
  });
}
