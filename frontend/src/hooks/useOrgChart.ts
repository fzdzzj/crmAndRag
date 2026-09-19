import apiClient from '@/api/apiClient.ts';
import { message } from 'ant-design-vue';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, type Ref } from 'vue';
import {
  getCompanyGroupList,
  postCompanyGroup,
  putCompanyGroup,
  putCompanyGroupByIdStatus,
  deleteCompanyGroupById,
  getCompanyDeptList,
  postCompanyDept,
  putCompanyDept,
  putCompanyDeptByIdStatus,
  deleteCompanyDeptById,
} from '@/api/axios';
import type {
  GetCompanyGroupListResponse,
  GetCompanyDeptListResponse,
  PostCompanyGroupData,
  PutCompanyGroupData,
  PostCompanyDeptData,
  PutCompanyDeptData,
} from '@/api/axios';

export type OrgGroup = NonNullable<GetCompanyGroupListResponse['data']>[number];
export type OrgDept = NonNullable<GetCompanyDeptListResponse['data']>[number];

const ORG_GROUP_KEY = 'org-groups';
const ORG_DEPT_KEY = 'org-depts';

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 集团列表（管理页显示全部状态） */
export function useOrgGroups(keyword?: Ref<string>) {
  const query = useQuery({
    queryKey: computed(() => [ORG_GROUP_KEY, keyword?.value ?? '']),
    queryFn: () =>
      getCompanyGroupList({
        client: apiClient,
        query: { keyword: keyword?.value.trim() || undefined },
      }),
    select: (res) => res.data?.data,
  });
  return query;
}

export type OrgGroupParams = NonNullable<PostCompanyGroupData['body']>;
export function useCreateOrgGroup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: OrgGroupParams) =>
      postCompanyGroup({ client: apiClient, body: payload }),
    onSuccess: () => {
      message.success('创建成功');
      void queryClient.invalidateQueries({ queryKey: [ORG_GROUP_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '创建失败，请稍后重试'));
    },
  });
}

export type OrgGroupUpdateParams = NonNullable<PutCompanyGroupData['body']>;
export function useUpdateOrgGroup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: OrgGroupUpdateParams) =>
      putCompanyGroup({ client: apiClient, body: payload }),
    onSuccess: () => {
      message.success('更新成功');
      void queryClient.invalidateQueries({ queryKey: [ORG_GROUP_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '更新失败，请稍后重试'));
    },
  });
}

export function useToggleOrgGroupStatus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: number; status: number }) =>
      putCompanyGroupByIdStatus({ client: apiClient, path: { id }, query: { status } }),
    onSuccess: () => {
      message.success('状态已更新');
      void queryClient.invalidateQueries({ queryKey: [ORG_GROUP_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '状态更新失败'));
    },
  });
}

export function useDeleteOrgGroup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      deleteCompanyGroupById({ client: apiClient, path: { id } }),
    onSuccess: () => {
      message.success('删除成功');
      void queryClient.invalidateQueries({ queryKey: [ORG_GROUP_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '删除失败'));
    },
  });
}

/** 部门列表（按集团过滤，管理页显示全部状态） */
export function useOrgDepts(groupId: Ref<number | undefined>, keyword?: Ref<string>) {
  const query = useQuery({
    queryKey: computed(() => [ORG_DEPT_KEY, groupId.value, keyword?.value ?? '']),
    enabled: computed(() => typeof groupId.value === 'number'),
    queryFn: () =>
      getCompanyDeptList({
        client: apiClient,
        query: {
          groupId: groupId.value,
          keyword: keyword?.value.trim() || undefined,
        },
      }),
    select: (res) => res.data?.data,
  });
  return query;
}

/** 全部部门（单表按集团分组折叠用，前端按 groupId 分组） */
export function useAllOrgDepts() {
  const query = useQuery({
    queryKey: computed(() => [ORG_DEPT_KEY, 'all']),
    queryFn: () => getCompanyDeptList({ client: apiClient }),
    select: (res) => res.data?.data,
  });
  return query;
}

export type OrgDeptParams = NonNullable<PostCompanyDeptData['body']>;
export function useCreateOrgDept() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: OrgDeptParams) =>
      postCompanyDept({ client: apiClient, body: payload }),
    onSuccess: () => {
      message.success('创建成功');
      void queryClient.invalidateQueries({ queryKey: [ORG_DEPT_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '创建失败，请稍后重试'));
    },
  });
}

export type OrgDeptUpdateParams = NonNullable<PutCompanyDeptData['body']>;
export function useUpdateOrgDept() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: OrgDeptUpdateParams) =>
      putCompanyDept({ client: apiClient, body: payload }),
    onSuccess: () => {
      message.success('更新成功');
      void queryClient.invalidateQueries({ queryKey: [ORG_DEPT_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '更新失败，请稍后重试'));
    },
  });
}

export function useToggleOrgDeptStatus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: number; status: number }) =>
      putCompanyDeptByIdStatus({ client: apiClient, path: { id }, query: { status } }),
    onSuccess: () => {
      message.success('状态已更新');
      void queryClient.invalidateQueries({ queryKey: [ORG_DEPT_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '状态更新失败'));
    },
  });
}

export function useDeleteOrgDept() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      deleteCompanyDeptById({ client: apiClient, path: { id } }),
    onSuccess: () => {
      message.success('删除成功');
      void queryClient.invalidateQueries({ queryKey: [ORG_DEPT_KEY] });
    },
    onError: (error: Error) => {
      message.error(errorMessage(error, '删除失败'));
    },
  });
}
