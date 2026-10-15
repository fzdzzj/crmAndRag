import { ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import { message as antdMessage } from 'ant-design-vue';
import { getAiInsightCustomerByCompanyId } from '@/api/axios/sdk.gen.ts';

export interface CustomerInsightView {
  companyId?: number;
  companyName?: string;
  healthScore?: number;
  healthLevel?: 'EXCELLENT' | 'HEALTHY' | 'WARNING' | 'CRITICAL';
  summary?: string;
  riskAlerts?: Array<string>;
  actionSuggestions?: Array<string>;
  llmEnhanced?: boolean;
}

/**
 * Customer 360 AI insight fetch (add-ai-customer-360-insight).
 *
 * Load the diagnostic result for the "insight suggestion area" rendering health score,
 * risk points and action suggestions, and let useCustomerInsightToAction turn a single
 * suggestion into a pending draft.
 */
export function useCustomerInsight() {
  const loading = ref(false);
  const error = ref<string | null>(null);
  const data = ref<CustomerInsightView | null>(null);

  async function load(companyId: number): Promise<CustomerInsightView | null> {
    loading.value = true;
    error.value = null;
    try {
      const res = await getAiInsightCustomerByCompanyId({
        client: apiClient,
        path: { companyId },
      });
      data.value = (res.data?.data ?? null) as CustomerInsightView | null;
      return data.value;
    } catch (e: unknown) {
      const msg = (e as Error)?.message || 'insight load failed';
      error.value = msg;
      antdMessage.error(msg);
      return null;
    } finally {
      loading.value = false;
    }
  }

  return { loading, error, data, load };
}
