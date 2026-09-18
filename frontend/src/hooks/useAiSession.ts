import { ref, computed, type Ref } from 'vue';
import { axiosInstance } from '@/api/apiClient';
import { useQuery, useMutation, useQueryClient } from '@tanstack/vue-query';
import type { ChatMessage } from './useAiChat';

export interface AiSession {
  id: string;
  title?: string;
  createdTime?: string;
  updatedTime?: string;
  archived?: boolean;
}

const SESSION_QUERY_KEY = 'ai-sessions';

export function useAiSessions() {
  const query = useQuery<AiSession[]>({
    queryKey: [SESSION_QUERY_KEY],
    queryFn: async () => {
      const res = await axiosInstance.get('/ai/sessions');
      return (res.data?.data || []) as AiSession[];
    },
  });

  return {
    sessions: computed(() => query.data.value || []),
    isLoading: query.isLoading,
    refetch: query.refetch,
  };
}

export function useCreateAiSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (title?: string) => {
      const res = await axiosInstance.post('/ai/sessions', { title: title || '新对话' });
      return res.data?.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [SESSION_QUERY_KEY] });
    },
  });
}

export function useDeleteAiSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await axiosInstance.delete(`/ai/sessions/${id}`);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [SESSION_QUERY_KEY] });
    },
  });
}

interface RawAiMessage {
  id?: string | number;
  role?: string;
  content?: string;
  msgType?: string;
  payload?: string | Record<string, unknown>;
  createdTime?: string;
}

export function useAiSessionMessages(sessionId: string | null | Ref<string | null>) {
  const idRef = typeof sessionId === 'string' || sessionId === null ? ref<string | null>(sessionId) : sessionId;
  const q = useQuery<ChatMessage[]>({
    queryKey: computed(() => [SESSION_QUERY_KEY, 'messages', idRef.value]),
    queryFn: async () => {
      if (!idRef.value) return [];
      const res = await axiosInstance.get(`/ai/sessions/${idRef.value}/messages`);
      const raw = (res.data?.data || []) as RawAiMessage[];
      return raw.map((m) => ({
        id: m.id,
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content || '',
        msgType: m.msgType || 'text',
        payload: m.payload ? (typeof m.payload === 'string' ? JSON.parse(m.payload) : m.payload) : null,
        createdTime: m.createdTime,
      })) as ChatMessage[];
    },
    enabled: computed(() => !!idRef.value),
  });
  return { ...q, refetch: q.refetch };
}
