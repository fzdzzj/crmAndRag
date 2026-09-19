import apiClient from '@/api/apiClient';
import {
  deleteUser,
  getRole,
  getRoleList,
  getUserMy,
  getUserOptions,
  postUserAdd,
  postUserFind,
  postUserPassword,
  postUserPasswordReset,
  postUserUpdate,
} from '@/api/axios';
import type {
  DeleteUserData,
  GetRoleListResponse,
  GetRoleResponse,
  GetUserOptionsResponse,
  GetUserHandoverStatisticsByUserIdResponse,
  GetUserMyResponse,
  PostUserAddData,
  PostUserFindResponse,
  PostUserHandoverExecuteData,
  PostUserHandoverExecuteResponse,
  PostUserHandoverQueryData,
  PostUserHandoverQueryResponse,
  PostUserPasswordResetData,
  PostUserUpdateData,
} from '@/api/axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref, watchEffect } from 'vue';
import { createQueryWithURLSearchParamsAsync } from './common/useURLSearchParamsAsync';
import {
  useExecuteHandover as _useExecuteHandover,
  useHandoverStatistics as _useHandoverStatistics,
  useUserHandoverRecords as _useUserHandoverRecords,
} from './useHandover';

const USER_QUERY_KEY = 'users';

export interface UserSearchFilters {
  realName?: string;
  phone?: string;
  email?: string;
  roleId?: number[];
  status?: number[];
  deptId?: number[];
  createTime?: string[];
  updateTime?: string[];
}

export type UserPayload = NonNullable<PostUserUpdateData['body']>;
export type CreateUserPayload = NonNullable<PostUserAddData['body']>;
export type CurrentUser = NonNullable<GetUserMyResponse['data']>;
export type CurrentUserRole = NonNullable<GetRoleResponse['data']>;
export type UserRecord = NonNullable<NonNullable<PostUserFindResponse['data']>['records']>[number];
export type RoleRecord = NonNullable<NonNullable<GetRoleListResponse['data']>['records']>[number];
export type UserOptionRecord = NonNullable<GetUserOptionsResponse['data']>[number];
export type HandoverStatisticsVO = NonNullable<GetUserHandoverStatisticsByUserIdResponse['data']>[number];
export type UserHandoverDTO = NonNullable<PostUserHandoverExecuteData['body']>;
export type UserHandoverQueryDTO = NonNullable<PostUserHandoverQueryData['body']>;
export type UserHandoverVO = NonNullable<PostUserHandoverExecuteResponse['data']>;

export interface PageUserHandoverVO {
  records?: NonNullable<NonNullable<PostUserHandoverQueryResponse['data']>['records']>;
  total?: NonNullable<PostUserHandoverQueryResponse['data']>['total'];
  size?: NonNullable<PostUserHandoverQueryResponse['data']>['size'];
  current?: NonNullable<PostUserHandoverQueryResponse['data']>['current'];
}

export function useQueryUsers(params?: {
  all?: boolean;
  filters?: UserSearchFilters;
  current?: number;
  pageSize?: number;
}) {
  const { all = false } = params ?? {};
  const current = ref(params?.current || 1);
  const pageSize = ref(params?.pageSize || 10);
  const filters = ref<UserSearchFilters>({
    realName: params?.filters?.realName,
    phone: params?.filters?.phone,
    email: params?.filters?.email,
    roleId: params?.filters?.roleId,
    status: params?.filters?.status,
    deptId: params?.filters?.deptId,
    createTime: params?.filters?.createTime,
    updateTime: params?.filters?.updateTime,
  });

  const query = useQuery({
    queryKey: computed(() => [
      USER_QUERY_KEY,
      current.value,
      pageSize.value,
      filters.value,
      all,
    ]),
    queryFn: () =>
      postUserFind({
        client: apiClient,
        query: {
          pageNum: current.value,
          pageSize: pageSize.value,
          realName: filters.value.realName,
          phone: filters.value.phone,
          email: filters.value.email,
          roleId: filters.value.roleId,
          status: filters.value.status,
        },
      }),
    select: (res) => res.data?.data,
  });

  watchEffect(() => {
    if (all) {
      pageSize.value = query.data.value?.total ?? pageSize.value;
    }
  });

  function setFilters(next: Partial<UserSearchFilters>) {
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

export const useQueryUsersWithURLSearchParamsAsync =
  createQueryWithURLSearchParamsAsync({
    queryHook: useQueryUsers,
    depGetter: (query) => ({
      current: query.current.value,
      pageSize: query.pageSize.value,
      filters: query.filters.value,
    }),
  });

export function useQueryRoles(params?: { all?: boolean }) {
  const all = params?.all ?? false;
  const current = ref(1);
  const pageSize = ref(10);
  const query = useQuery({
    queryKey: computed(() => ['roles', current.value, pageSize.value]),
    queryFn: () =>
      getRoleList({
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
      pageSize.value = query.data.value?.total ?? pageSize.value;
    }
  });

  return query;
}

export function useUpdateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: UserPayload) => postUserUpdate({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [USER_QUERY_KEY] });
    },
  });
}

export function useCreateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateUserPayload) => postUserAdd({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [USER_QUERY_KEY] });
    },
  });
}

export function useResetPassword() {
  return useMutation({
    mutationFn: (data: string) =>
      postUserPassword({
        client: apiClient,
        body: window.btoa(data),
        // 后端 @RequestBody String 会把 JSON 字符串的引号一起读进来，
        // 必须用 text/plain 发送原始 base64 才能正确解码
        headers: { 'Content-Type': 'text/plain' },
      }),
  });
}

export function useAdminResetPassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: { userId: number; newPassword: string }) =>
      postUserPasswordReset({
        client: apiClient,
        body: {
          ...data,
          newPassword: window.btoa(data.newPassword),
        } satisfies NonNullable<PostUserPasswordResetData['body']>,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [USER_QUERY_KEY] });
    },
  });
}

export const useUserHandoverStatistics = _useHandoverStatistics;
export const useUserHandoverRecords = _useUserHandoverRecords;
export const useExecuteUserHandover = _useExecuteHandover;

const CURRENT_USER_ROLE_KEY = 'currentUserRole';

export function useQueryCurrentUserRole() {
  return useQuery({
    queryKey: [CURRENT_USER_ROLE_KEY],
    queryFn: () => getRole({ client: apiClient }),
    select: (res) => res.data?.data,
  });
}

export function useQueryCurrentUser() {
  return useQuery({
    queryKey: ['currentUser'],
    queryFn: () => getUserMy({ client: apiClient }),
    select: (res) => res.data?.data,
  });
}

/**
 * 查询全部在职用户选择项（协助人选择器等轻量场景，仅返回最少字段）
 */
export function useQueryUserOptions() {
  return useQuery({
    queryKey: ['userOptions'],
    queryFn: () => getUserOptions({ client: apiClient }),
    select: (res) => res.data?.data,
  });
}

export function useDeleteUsers() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) =>
      deleteUser({
        client: apiClient,
        query: { ids } satisfies DeleteUserData['query'],
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [USER_QUERY_KEY] });
    },
  });
}
