import apiClient from '@/api/apiClient';
import { deleteDeptById, getDeptAll, getDeptList, postDept, putDept } from '@/api/axios';
import type { GetDeptListResponse, PutDeptData } from '@/api/axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';

const DEPT_QUERY_KEY = 'depts';

export type DeptRecord = NonNullable<NonNullable<GetDeptListResponse['data']>>[number];
export type DeptPayload = {
  id?: number;
  deptName?: string;
  status?: number;
  sort?: number;
};

export function useQueryDepts() {
  return useQuery({
    queryKey: [DEPT_QUERY_KEY],
    queryFn: () => getDeptList({ client: apiClient }),
    select: (res) => res.data?.data,
  });
}

/**
 * 查询全部部门（含停用，供部门管理弹窗维护）
 */
export function useQueryAllDepts() {
  return useQuery({
    queryKey: [DEPT_QUERY_KEY, 'all'],
    queryFn: () => getDeptAll({ client: apiClient }),
    select: (res) => res.data?.data,
  });
}

export function useCreateDept() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DeptPayload) => postDept({ client: apiClient, body }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [DEPT_QUERY_KEY] });
    },
  });
}

export function useUpdateDept() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: NonNullable<PutDeptData['body']>) =>
      putDept({ client: apiClient, body }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [DEPT_QUERY_KEY] });
    },
  });
}

export function useDeleteDept() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      deleteDeptById({ client: apiClient, path: { id } }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [DEPT_QUERY_KEY] });
    },
  });
}
