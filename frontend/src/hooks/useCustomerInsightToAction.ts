import { ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import { message as antdMessage } from 'ant-design-vue';
import { postAiInsightCustomerByCompanyIdToAction } from '@/api/axios/sdk.gen.ts';

export type InsightActionType = 'CREATE_CONTACT_TASK' | 'CREATE_BUSINESS_ACTIVITY';

export interface InsightToActionOptions {
  companyId: number;
  suggestionIndex: number;
  actionType?: InsightActionType;
}

/** 转动作结果（与后端 InsightToActionResult 对齐，收窄为页面消费形状）。 */
export interface CustomerInsightToActionResult {
  pendingId?: string;
  status?: string;
  actionType?: string;
  suggestion?: string;
}

/**
 * AI diagnosis suggestion to action conversion (add-ai-insight-to-action-conversion).
 *
 * Turn the index-th action suggestion into a pending draft (default CREATE_CONTACT_TASK,
 * or CREATE_BUSINESS_ACTIVITY), returning the pendingId for the existing confirm entry.
 */
export function useCustomerInsightToAction() {
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function convert(
    options: InsightToActionOptions,
  ): Promise<CustomerInsightToActionResult | null> {
    loading.value = true;
    error.value = null;
    try {
      const res = await postAiInsightCustomerByCompanyIdToAction({
        client: apiClient,
        path: { companyId: options.companyId },
        body: {
          suggestionIndex: options.suggestionIndex,
          ...(options.actionType != null ? { actionType: options.actionType } : {}),
        },
      });
      const raw = res.data?.data ?? null;
      const data = raw as CustomerInsightToActionResult | null;
      if (data?.pendingId) {
        antdMessage.success(`Draft generated: ${data.pendingId}`);
      }
      return data;
    } catch (e: unknown) {
      const msg = (e as Error)?.message || 'toAction failed';
      error.value = msg;
      antdMessage.error(msg);
      return null;
    } finally {
      loading.value = false;
    }
  }

  return { loading, error, convert };
}
