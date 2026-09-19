import apiClient from '@/api/apiClient';
import { deleteRole, getRoleList, postRoleAdd } from '@/api/axios';
import type { DeleteRoleData, PostRoleAddData } from '@/api/axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref, watchEffect } from 'vue';
import { createQueryWithURLSearchParamsAsync } from './common/useURLSearchParamsAsync';

const ROLE_QUERY_KEY = 'roles';

export interface RoleSearchFilters {
  roleName?: string;
  roleDesc?: string;
}

export function useQueryRoles(params?: {
  all?: boolean;
  filters?: RoleSearchFilters;
  current?: number;
  pageSize?: number;
}) {
  const { all = false } = params ?? {};
  const current = ref(params?.current || 1);
  const pageSize = ref(params?.pageSize || 10);
  const filters = ref<RoleSearchFilters>({
    roleName: params?.filters?.roleName,
    roleDesc: params?.filters?.roleDesc,
  });
  const query = useQuery({
    queryKey: computed(() => [
      ROLE_QUERY_KEY,
      current.value,
      pageSize.value,
      filters.value,
      all,
    ]),
    queryFn: () =>
      getRoleList({
        client: apiClient,
        query: {
          pageNum: current.value,
          pageSize: pageSize.value,
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

  function setFilters(next: Partial<RoleSearchFilters>) {
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

export const useQueryRolesWithURLSearchParamsAsync =
  createQueryWithURLSearchParamsAsync({
    queryHook: useQueryRoles,
    depGetter: (query) => ({
      current: query.current.value,
      pageSize: query.pageSize.value,
      filters: query.filters.value,
    }),
  });

export function useCreateRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: NonNullable<PostRoleAddData['query']>) =>
      postRoleAdd({ client: apiClient, query: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ROLE_QUERY_KEY] });
    },
  });
}

export function useDeleteRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: NonNullable<DeleteRoleData['query']>) =>
      deleteRole({ client: apiClient, query: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [ROLE_QUERY_KEY] });
    },
  });
}
