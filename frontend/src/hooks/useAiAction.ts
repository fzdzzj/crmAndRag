import { ref } from 'vue';
import { axiosInstance } from '@/api/apiClient';
import { message as antdMessage } from 'ant-design-vue';

export interface AiActionStatus {
  pendingId: string;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'FAILED';
  actionType?: string;
  payload?: unknown;
  result?: unknown;
  error?: string;
}

/** AI 待确认操作（confirm/cancel/edit）状态与动作封装 */
export function useAiAction() {
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function getStatus(pendingId: string): Promise<AiActionStatus | null> {
    try {
      const res = await axiosInstance.get(`/ai/actions/${pendingId}`);
      return (res.data?.data as AiActionStatus) || null;
    } catch (e: unknown) {
      error.value = (e as Error).message;
      return null;
    }
  }

  async function confirm(pendingId: string): Promise<unknown> {
    loading.value = true;
    error.value = null;
    try {
      const res = await axiosInstance.post(`/ai/actions/${pendingId}/confirm`);
      const data = res.data?.data;
      antdMessage.success('确认成功');
      return data;
    } catch (e: unknown) {
      error.value = (e as Error)?.message || '确认失败';
      antdMessage.error(error.value);
      throw e;
    } finally {
      loading.value = false;
    }
  }

  async function cancelAction(pendingId: string): Promise<boolean> {
    loading.value = true;
    try {
      await axiosInstance.post(`/ai/actions/${pendingId}/cancel`);
      antdMessage.success('已取消');
      return true;
    } catch (e: unknown) {
      error.value = (e as Error).message;
      antdMessage.error('取消失败');
      return false;
    } finally {
      loading.value = false;
    }
  }

  /**
   * 编辑 payload：在既有 payload 上合并字段（不整包替换）
   * 区分不同 actionType 的字段路径由后端处理，前端传完整 payload
   */
  async function edit(pendingId: string, partialPayload: unknown): Promise<unknown> {
    loading.value = true;
    try {
      // 后端 edit 接收 { payload: stringified or object }
      const body = { payload: typeof partialPayload === 'string' ? partialPayload : JSON.stringify(partialPayload) };
      const res = await axiosInstance.put(`/ai/actions/${pendingId}/edit`, body);
      antdMessage.success('编辑已提交');
      return res.data?.data;
    } catch (e: unknown) {
      error.value = (e as Error).message;
      antdMessage.error('编辑失败');
      throw e;
    } finally {
      loading.value = false;
    }
  }

  return {
    loading,
    error,
    getStatus,
    confirm,
    cancelAction,
    edit,
  };
}
