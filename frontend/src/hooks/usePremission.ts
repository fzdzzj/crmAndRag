import apiClient from '@/api/apiClient';
import {
  getPermissionAuditor,
  getPermissionGetByRole,
  getPermissionGetMyPermission,
  getPermissionList,
  postPermissionAddOrDeletePermissionsToRole,
} from '@/api/axios';
import type {
  GetPermissionAuditorResponse,
  GetPermissionGetByRoleResponse,
  GetPermissionListResponse,
  PostPermissionAddOrDeletePermissionsToRoleData,
} from '@/api/axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, type Ref } from 'vue';
import type { PermissionGrouped } from '@/types/permission';

const PERMISSION_QUERY_KEY = 'permissions';
const MY_PERMISSION_QUERY_KEY = 'myPermissions';
const AUDITOR_QUERY_KEY = 'auditors';

export type RoleANDPermissionDTO = NonNullable<PostPermissionAddOrDeletePermissionsToRoleData['body']>;
export type AuditorRecord = NonNullable<GetPermissionAuditorResponse['data']>[number];
export type PermissionGroupRecord = NonNullable<GetPermissionGetByRoleResponse['data']>;

function toPermissionGrouped(
  data: GetPermissionGetByRoleResponse['data'],
): PermissionGrouped | undefined {
  return data as PermissionGrouped | undefined;
}

export function useQueryMyPermissions() {
  return useQuery({
    queryKey: [MY_PERMISSION_QUERY_KEY],
    queryFn: () => getPermissionGetMyPermission({ client: apiClient }),
    select: (res) => toPermissionGrouped(res.data?.data),
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

export function useQueryAllPermissions() {
  return useQuery({
    queryKey: [PERMISSION_QUERY_KEY, 'all'],
    queryFn: () => getPermissionList({ client: apiClient }),
    select: (res) => (res.data?.data ?? {}) as GetPermissionListResponse['data'],
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

export function useQueryPermissionsByRole(roleId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [PERMISSION_QUERY_KEY, 'role', roleId.value]),
    queryFn: () =>
      getPermissionGetByRole({
        client: apiClient,
        query: { roleId: roleId.value! },
      }),
    select: (res) => toPermissionGrouped(res.data?.data),
    enabled: computed(() => !!roleId.value),
  });
}

export function useAddPermissionsToRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Omit<RoleANDPermissionDTO, 'isAdd'>) =>
      postPermissionAddOrDeletePermissionsToRole({
        client: apiClient,
        body: { ...data, isAdd: true },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [PERMISSION_QUERY_KEY] });
    },
  });
}

export function useDeletePermissionsFromRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Omit<RoleANDPermissionDTO, 'isAdd'>) =>
      postPermissionAddOrDeletePermissionsToRole({
        client: apiClient,
        body: { ...data, isAdd: false },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [PERMISSION_QUERY_KEY] });
    },
  });
}

export function useManagePermissionsForRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: RoleANDPermissionDTO) =>
      postPermissionAddOrDeletePermissionsToRole({ client: apiClient, body: data }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [PERMISSION_QUERY_KEY] });
    },
  });
}

export function useQueryAuditors() {
  return useQuery({
    queryKey: [AUDITOR_QUERY_KEY],
    queryFn: () => getPermissionAuditor({ client: apiClient }),
    select: (res) => res.data?.data ?? [],
  });
}
