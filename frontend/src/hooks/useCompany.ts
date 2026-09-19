import {
  useMutation,
  useQuery,
  useQueryClient,
  type MutationOptions,
} from '@tanstack/vue-query';
import { message } from 'ant-design-vue';
import { computed, ref, watchEffect, type Ref } from 'vue';
import { downloadFromBase64 } from '@/utils/download.ts';
import apiClient from '@/api/apiClient.ts';
import { getContactGetContactByCompanyId, getCompanyCustom, postCompany, getCompanyTemplate, postCompanyList, getCompanyExcel, deleteCompanyById, putCompanyRecover, deleteCompany, putCompany, getCompanyGroupFind, getCompanyGroupList, postCompanyGroup, getCompanyDeptList, postCompanyDept } from '@/api/axios';
import type { PostCompanyData } from '@/api/axios';
import { createQueryWithURLSearchParamsAsync } from './common/useURLSearchParamsAsync';

export type CompanyFilterType = {
  id?: number;
  companyName?: string;
  industry?: string;
  customerType?: string;
  belongGroup?: string;
  dept?: string;
  address?: string;
  phone?: string;
  website?: string;
  description?: string;
  grade?: number;
  ownerId?: number;
};
export function useCompanyContacts(companyId: Ref<number | undefined>) {
  const query = useQuery({
    queryKey: computed(() => ['company-contacts', companyId.value]),
    enabled: computed(() => typeof companyId.value === 'number'),
    queryFn: () =>
      getContactGetContactByCompanyId({
        client: apiClient,
        query: { id: companyId.value! },
      }),
    select: (res) => res.data?.data,
  });
  return query;
}

const COMPANY_QUERY_KEY = 'companies';
export function useQueryCompanies(options?: {
  all?: boolean;
  filters?: CompanyFilterType;
  current?: number;
  pageSize?: number;
}) {
  const { all = false } = options ?? {};
  const current = ref(options?.current || 1);
  const pageSize = ref(options?.pageSize || 10);
  const isDeleted = ref(false);
  const filters = ref<CompanyFilterType>({
    id: options?.filters?.id,
    companyName: options?.filters?.companyName,
    industry: options?.filters?.industry,
    customerType: options?.filters?.customerType,
    belongGroup: options?.filters?.belongGroup,
    dept: options?.filters?.dept,
    address: options?.filters?.address,
    phone: options?.filters?.phone,
    website: options?.filters?.website,
    description: options?.filters?.description,
    grade: options?.filters?.grade,
    ownerId: options?.filters?.ownerId,
  });
  const query = useQuery({
    queryKey: computed(() => [
      COMPANY_QUERY_KEY,
      current.value,
      pageSize.value,
      isDeleted.value,
      filters.value,
      all,
    ]),
    queryFn: () =>
      getCompanyCustom({
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
  function setFilters(next: Partial<CompanyFilterType>) {
    filters.value = { ...filters.value, ...next };
  }
  function reset() {
    for (const key in filters.value) {
      if (Object.prototype.hasOwnProperty.call(filters.value, key)) {
        filters.value[key as keyof CompanyFilterType] = undefined;
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
export const useQueryCompaniesWithURLSearchParamsAsync =
  createQueryWithURLSearchParamsAsync({
    queryHook: useQueryCompanies,
    depGetter: (query) => ({
      pageSize: query.pageSize.value,
      pageNum: query.current.value,
      filters: query.filters.value,
    }),
  });
export type addCompanyParams = NonNullable<PostCompanyData['body']>;
type AddCompanyResponse = Awaited<ReturnType<typeof postCompany>>;
export function useAddCompany(
  options?: MutationOptions<AddCompanyResponse, Error, addCompanyParams>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: addCompanyParams) =>
      postCompany({ client: apiClient, body: payload }),
    ...options,
    onSuccess: (...args) => {
      message.success('创建成功');
      void queryClient.invalidateQueries({ queryKey: [COMPANY_QUERY_KEY] });
      options?.onSuccess?.(...args);
    },
    onError: (...args) => {
      const [error] = args;
      message.error(error instanceof Error && error.message ? error.message : '创建失败，请稍后重试');
      options?.onError?.(...args);
    },
  });
}

export function useExportCompaniesTemplate() {
  return useMutation({
    mutationFn: () => getCompanyTemplate({ client: apiClient }),
    onSuccess: (res) => {
      const fileString = String(res.data?.data ?? '');
      downloadFromBase64(fileString, 'companies-template.xlsx');
      message.success('导出成功');
    },
  });
}
export function useImportCompanies() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => postCompanyList({ client: apiClient, body: { file } }),
    onSuccess: (res) => {
      const count = Number(res.data?.data ?? 0);
      if (count > 0) {
        message.success(`导入成功 ${count} 条`);
      } else {
        message.warning('文件中没有可导入的新公司（可能均已存在）');
      }
      void queryClient.invalidateQueries({ queryKey: [COMPANY_QUERY_KEY] });
    },
  });
}
export function useExportCompanies() {
  return useMutation({
    mutationFn: () => getCompanyExcel({ client: apiClient }),
    onSuccess: (res) => {
      const fileString = String(res.data?.data ?? '');
      downloadFromBase64(fileString, '客户列表.xlsx');
      message.success('导出成功');
    },
  });
}
export function useDeleteCompanies() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteCompanyById({ client: apiClient, path: { id } }),
    onSuccess: () => {
      message.success('删除成功');
      void queryClient.invalidateQueries({ queryKey: [COMPANY_QUERY_KEY] });
    },
  });
}
export function useRestoreCompanies() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) => putCompanyRecover({ client: apiClient, body: ids }),
    onSuccess: () => {
      message.success('恢复成功');
      void queryClient.invalidateQueries({ queryKey: [COMPANY_QUERY_KEY] });
    },
  });
}
export function useHardDeleteCompanies() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) => deleteCompany({ client: apiClient, body: ids }),
    onSuccess: () => {
      message.success('批量删除成功');
      void queryClient.invalidateQueries({ queryKey: [COMPANY_QUERY_KEY] });
    },
  });
}
export function useBatchDeleteCompanies() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: number[]) =>
      Promise.all(ids.map((id) => deleteCompanyById({ client: apiClient, path: { id } }))),
    onSuccess: () => {
      message.success('批量删除成功，已移动到回收站');
      void queryClient.invalidateQueries({ queryKey: [COMPANY_QUERY_KEY] });
    },
  });
}
interface UpdateCompanyParams {
  companyName: string;
  industry: string;
  customerType?: string;
  belongGroup?: string;
  dept: string;
  address: string;
  phone: string;
  website: string;
  description: string;
  grade: number;
  ownerId: number;
}
export function useUpdateCompany() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: UpdateCompanyParams[]) =>
      putCompany({ client: apiClient, body: payload }),
    onSuccess: () => {
      message.success('更新成功');
      void queryClient.invalidateQueries({ queryKey: [COMPANY_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(error instanceof Error && error.message ? error.message : '更新失败，请稍后重试');
    },
  });
}

export function useQueryCompaniesByGroup() {
  return useMutation({
    mutationFn: (params: { belongGroup: string; pageNum: number; pageSize: number }) =>
      getCompanyGroupFind({ client: apiClient, query: params }).then((res) => res.data?.data),
  });
}

export type CompanyGroupOption = { id: number; groupName: string };
export type CompanyDeptOption = { id: number; deptName: string };

/**
 * 集团/部门主数据联动（客户新增/编辑表单用）
 *
 * - 集团：从 company_group 主数据搜索/选择，输入新名称回车自动创建并选中；
 * - 部门：按所选集团从 company_dept 加载，输入新部门回车自动创建到该集团下并选中。
 */
export function useGroupDeptMaster() {
  const groupOptions = ref<CompanyGroupOption[]>([]);
  const deptOptions = ref<CompanyDeptOption[]>([]);
  const selectedGroupId = ref<number | undefined>(undefined);
  const isSearchingGroup = ref(false);
  const isSearchingDept = ref(false);
  const isCreatingGroup = ref(false);
  const isCreatingDept = ref(false);
  const groupSearchText = ref('');
  const deptSearchText = ref('');
  const pendingGroupName = ref<string | undefined>(undefined);
  const pendingDeptName = ref<string | undefined>(undefined);
  let groupTimer: ReturnType<typeof setTimeout> | undefined;
  let deptTimer: ReturnType<typeof setTimeout> | undefined;

  async function searchGroups(keyword?: string) {
    groupSearchText.value = keyword ?? '';
    if (groupTimer) clearTimeout(groupTimer);
    await new Promise<void>((resolve) => {
      groupTimer = setTimeout(resolve, 300);
    });
    isSearchingGroup.value = true;
    try {
      const res = await getCompanyGroupList({
        client: apiClient,
        query: {
          keyword: keyword?.trim() || undefined,
          status: 1,
        },
      });
      groupOptions.value = (res.data?.data ?? []).map((g) => ({
        id: g.id!,
        groupName: g.groupName ?? '',
      }));
    } catch {
      groupOptions.value = [];
    } finally {
      isSearchingGroup.value = false;
    }
  }

  async function searchDepts(keyword?: string) {
    deptSearchText.value = keyword ?? '';
    if (!selectedGroupId.value) {
      deptOptions.value = [];
      return;
    }
    if (deptTimer) clearTimeout(deptTimer);
    await new Promise<void>((resolve) => {
      deptTimer = setTimeout(resolve, 300);
    });
    isSearchingDept.value = true;
    try {
      const res = await getCompanyDeptList({
        client: apiClient,
        query: {
          groupId: selectedGroupId.value,
          keyword: keyword?.trim() || undefined,
          status: 1,
        },
      });
      deptOptions.value = (res.data?.data ?? []).map((d) => ({
        id: d.id!,
        deptName: d.deptName ?? '',
      }));
    } catch {
      deptOptions.value = [];
    } finally {
      isSearchingDept.value = false;
    }
  }

  /** 选中集团：记录 id 并加载该集团下的启用部门 */
  function selectGroup(groupName?: string) {
    groupSearchText.value = groupName ?? '';
    pendingGroupName.value = undefined;
    pendingDeptName.value = undefined;
    const matched = groupOptions.value.find((g) => g.groupName === groupName);
    selectedGroupId.value = matched?.id;
    deptOptions.value = [];
    if (matched) {
      void searchDepts('');
    }
  }

  /** 回车新建集团：创建主数据后选中并加载部门 */
  async function createGroup(name: string): Promise<CompanyGroupOption | null> {
    const trimmed = name.trim();
    if (!trimmed || isCreatingGroup.value) return null;
    isCreatingGroup.value = true;
    try {
      const res = await postCompanyGroup({
        client: apiClient,
        body: { groupName: trimmed },
      });
      const created = res.data?.data;
      if (!created?.id) return null;
      const option: CompanyGroupOption = { id: created.id, groupName: created.groupName ?? trimmed };
      if (!groupOptions.value.some((g) => g.id === option.id)) {
        groupOptions.value = [...groupOptions.value, option];
      }
      selectedGroupId.value = option.id;
      pendingGroupName.value = undefined;
      void searchDepts('');
      return option;
    } catch (error: unknown) {
      message.error(
        error instanceof Error && error.message ? error.message : '新建集团失败，请稍后重试',
      );
      return null;
    } finally {
      isCreatingGroup.value = false;
    }
  }

  /** 新建部门：归属到指定集团（默认当前选中集团）下 */
  async function createDept(name: string, groupIdOverride?: number): Promise<CompanyDeptOption | null> {
    const trimmed = name.trim();
    const groupId = groupIdOverride ?? selectedGroupId.value;
    if (!trimmed || !groupId || isCreatingDept.value) return null;
    isCreatingDept.value = true;
    try {
      const res = await postCompanyDept({
        client: apiClient,
        body: { groupId, deptName: trimmed },
      });
      const created = res.data?.data;
      if (!created?.id) return null;
      const option: CompanyDeptOption = { id: created.id, deptName: created.deptName ?? trimmed };
      if (selectedGroupId.value === groupId && !deptOptions.value.some((d) => d.id === option.id)) {
        deptOptions.value = [...deptOptions.value, option];
      }
      pendingDeptName.value = undefined;
      return option;
    } catch (error: unknown) {
      message.error(
        error instanceof Error && error.message ? error.message : '新建部门失败，请稍后重试',
      );
      return null;
    } finally {
      isCreatingDept.value = false;
    }
  }

  /** 编辑回填：按集团名匹配主数据 id 并加载其部门（兼容旧数据无主数据的情况） */
  async function syncGroupByName(groupName?: string) {
    if (!groupName || !groupName.trim()) {
      selectedGroupId.value = undefined;
      pendingGroupName.value = undefined;
      pendingDeptName.value = undefined;
      groupOptions.value = [];
      deptOptions.value = [];
      return;
    }
    pendingGroupName.value = undefined;
    pendingDeptName.value = undefined;
    isSearchingGroup.value = true;
    try {
      const res = await getCompanyGroupList({
        client: apiClient,
        // 不过滤状态：兼容客户已引用但已停用的集团回填
        query: { keyword: groupName.trim() },
      });
      const records = res.data?.data ?? [];
      groupOptions.value = records.map((g) => ({ id: g.id!, groupName: g.groupName ?? '' }));
      const exact = records.find((g) => g.groupName === groupName.trim());
      selectedGroupId.value = exact?.id ?? records[0]?.id;
      if (selectedGroupId.value) {
        void searchDepts('');
      } else {
        deptOptions.value = [];
        message.warning(
          `集团"${groupName.trim()}"不在组织架构主数据中，可保留原文本保存，或在组织架构中重新创建`,
        );
      }
    } catch {
      selectedGroupId.value = undefined;
      groupOptions.value = [];
      deptOptions.value = [];
    } finally {
      isSearchingGroup.value = false;
    }
  }

  /** 下拉"创建新集团"选项（输入内容不在主数据中时出现，移动端友好） */
  const groupCreateOption = computed(() => {
    const text = groupSearchText.value.trim();
    if (!text) return undefined;
    if (groupOptions.value.some((g) => g.groupName === text)) return undefined;
    return { value: '__create_group__', label: `创建"${text}"` };
  });

  /** 下拉"创建新部门"选项（输入内容不在当前集团部门中时出现，移动端友好） */
  const deptCreateOption = computed(() => {
    const text = deptSearchText.value.trim();
    if (!text) return undefined;
    if (deptOptions.value.some((d) => d.deptName === text)) return undefined;
    return { value: '__create_dept__', label: `创建"${text}"` };
  });

  /** 选中"创建新集团"选项：只暂存名称，提交客户时才真正创建（避免误触） */
  function markPendingGroup(name?: string) {
    pendingGroupName.value = name?.trim() || undefined;
    selectedGroupId.value = undefined;
    deptOptions.value = [];
    pendingDeptName.value = undefined;
  }

  /** 选中"创建新部门"选项：只暂存名称，提交客户时才真正创建（避免误触） */
  function markPendingDept(name?: string) {
    pendingDeptName.value = name?.trim() || undefined;
  }

  function clearPending() {
    pendingGroupName.value = undefined;
    pendingDeptName.value = undefined;
  }

  /**
   * 提交客户前统一创建待建主数据（先集团后部门）。
   *
   * @return true 表示全部就绪（无待建或创建成功）；false 表示创建失败（错误已提示）
   */
  async function commitPending(): Promise<boolean> {
    let groupId = selectedGroupId.value;
    if (pendingGroupName.value) {
      const created = await createGroup(pendingGroupName.value);
      if (!created) return false;
      groupId = created.id;
    }
    if (pendingDeptName.value) {
      if (!groupId) return false;
      const created = await createDept(pendingDeptName.value, groupId);
      if (!created) return false;
    }
    return true;
  }

  return {
    groupOptions,
    deptOptions,
    selectedGroupId,
    pendingGroupName,
    pendingDeptName,
    groupSearchText,
    deptSearchText,
    groupCreateOption,
    deptCreateOption,
    isSearchingGroup,
    isSearchingDept,
    isCreatingGroup,
    isCreatingDept,
    searchGroups,
    searchDepts,
    selectGroup,
    createGroup,
    createDept,
    syncGroupByName,
    markPendingGroup,
    markPendingDept,
    clearPending,
    commitPending,
  };
}
