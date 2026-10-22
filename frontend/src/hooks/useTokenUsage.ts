import { useQuery } from '@tanstack/vue-query';
import { computed, type Ref } from 'vue';
import { axiosInstance } from '@/api/apiClient';

/** 后端统一响应外壳（业务码在 code 上，非 1 由响应拦截器抛错）。 */
interface ApiResult<T> {
  code: number;
  msg?: string;
  data: T;
}

/** 单个用户的 token 用量行（与后端 TokenUsageByUserVO 同构）。 */
export interface TokenUsageByUserRow {
  userRef: string;
  userId: number | null;
  userName: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  calls: number;
  failedCalls: number;
  lastCallTime: string | null;
  sharePct: number;
}

/** by-user 分页结果（total = 去重用户数）。 */
export interface TokenUsageByUserPage {
  total: number;
  records: TokenUsageByUserRow[];
}

/** 单个 usage_type 的用量与占比。 */
export interface TokenUsageTypeShare {
  usageType: string;
  totalTokens: number;
  calls: number;
  failedCalls: number;
  sharePct: number;
}

/** 区间总览（与后端 TokenUsageSummaryVO 同构）。 */
export interface TokenUsageSummary {
  startTime: string;
  endTime: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  calls: number;
  failedCalls: number;
  userCount: number;
  usageTypes: TokenUsageTypeShare[];
}

/** 查询条件（时间用 ISO-8601；usageType 省略 = 不过滤）。 */
export interface TokenUsageQueryParams {
  startTime: string;
  endTime: string;
  usageType?: string;
  pageNum: number;
  pageSize: number;
}

/** 可选计量类型（与后端 TokenUsageType wire 名一一对应）。 */
export const TOKEN_USAGE_TYPES = [
  { value: 'chat', label: 'chat（对话）' },
  { value: 'embedding', label: 'embedding（向量化）' },
  { value: 'ocr', label: 'ocr（文字抽取）' },
  { value: 'vision', label: 'vision（视觉理解）' },
  { value: 'summary', label: 'summary（摘要）' },
  { value: 'intent', label: 'intent（意图识别）' },
] as const;

const TOKEN_USAGE_QUERY_KEY = 'tokenUsage';

function getJson<T>(
  url: string,
  params?: Record<string, string | number | undefined>,
): Promise<T> {
  return axiosInstance
    .get<ApiResult<T>>(url, { params })
    .then((response) => response.data.data);
}

/** 按用户查询 token 用量（分页，总 token 降序）。 */
export function useQueryTokenUsageByUser(params: Ref<TokenUsageQueryParams>) {
  return useQuery({
    queryKey: computed(() => [TOKEN_USAGE_QUERY_KEY, 'byUser', params.value]),
    queryFn: () =>
      getJson<TokenUsageByUserPage>('/ai/token-usage/by-user', {
        startTime: params.value.startTime,
        endTime: params.value.endTime,
        usageType: params.value.usageType,
        pageNum: params.value.pageNum,
        pageSize: params.value.pageSize,
      }),
  });
}

/** 查询区间总览（token 三口径 + 调用/失败次数 + 用户数 + 按类型占比）。 */
export function useQueryTokenUsageSummary(
  params: Ref<
    Pick<TokenUsageQueryParams, 'startTime' | 'endTime' | 'usageType'>
  >,
) {
  return useQuery({
    queryKey: computed(() => [TOKEN_USAGE_QUERY_KEY, 'summary', params.value]),
    queryFn: () =>
      getJson<TokenUsageSummary>('/ai/token-usage/summary', {
        startTime: params.value.startTime,
        endTime: params.value.endTime,
        usageType: params.value.usageType,
      }),
  });
}
