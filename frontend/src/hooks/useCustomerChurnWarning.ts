import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { message } from 'ant-design-vue';
import apiClient from '@/api/apiClient.ts';
import { getCustomerChurnWarningList, postCustomerChurnWarningScan } from '@/api/axios';
import type { CustomerChurnWarningVo } from '@/api/axios';

/**
 * 客户流失预警数据层（add-customer-churn-warning-and-reactivation 任务 3.1）。
 *
 * 流失风险清单为即时计算（不落库），与客户列表同锚既有客户权限（117 查看 / 113 触发变更）； 触发扫描成功后 invalidate 风险清单缓存，与合同预警 hook 同型。
 */
const CHURN_QUERY_KEY = 'customer-churn-warning';

/** 查询当前流失风险客户清单（即时计算）。 */
export function useQueryCustomerChurnWarning(options?: { limit?: number }) {
  const limit = ref(options?.limit);
  const query = useQuery<CustomerChurnWarningVo[]>({
    queryKey: computed(() => [CHURN_QUERY_KEY, limit.value]),
    queryFn: async () => {
      const res = await getCustomerChurnWarningList({
        client: apiClient,
        query: { limit: limit.value },
      });
      return res.data?.data ?? [];
    },
    gcTime: 1000 * 60 * 5,
    staleTime: 1000 * 60 * 2,
  });
  return { limit, ...query };
}

/** 一键触发流失风险扫描与待办生成（复用客户变更权限），返回本次新生成数量。 */
export function useTriggerCustomerChurnWarning() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      postCustomerChurnWarningScan({ client: apiClient }).then((res) => res.data?.data),
    onSuccess: (count) => {
      message.success(count ? `已生成 ${count} 条流失预警待办` : '扫描完成，无新增待办');
      void queryClient.invalidateQueries({ queryKey: [CHURN_QUERY_KEY] });
    },
    onError: (error: Error) => {
      message.error(
        error instanceof Error && error.message ? error.message : '扫描失败，请稍后重试',
      );
    },
  });
}
