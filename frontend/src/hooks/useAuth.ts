import { useMutation, useQueryClient } from '@tanstack/vue-query';
import apiClient from '@/api/apiClient';
import { postUserLogin } from '@/api/axios';
import { useRouter } from 'vue-router';
import { TokenManager } from '../utils/token.ts';
import type { MutationOptions } from '@tanstack/vue-query';
import { ref } from 'vue';

export function useLogin(
  options?: MutationOptions<string, Error, { email: string; password: string }>,
) {
  const email = ref('');
  const password = ref('');
  return {
    email,
    password,
    ...useMutation({
      ...options,
      mutationFn: async () => {
        if (!email.value.trim() || !password.value.trim()) {
          throw new Error('邮箱和密码不能为空');
        }
        const res = await postUserLogin({
          client: apiClient,
          body: {
            email: email.value,
            password: window.btoa(password.value),
          },
        });
        const token = res.data?.data;
        if (!token) {
          throw new Error('登录失败');
        }
        return token;
      },
      onSuccess: (...args) => {
        const data = args[0];
        TokenManager.setAccessToken(data);
        TokenManager.setPayload(TokenManager.parseAccessToken(data));
        options?.onSuccess?.(...args);
      },
    }),
  };
}
export function useLogout() {
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: () => {
      TokenManager.removeAccessToken();
      TokenManager.removePayload();
      return Promise.resolve();
    },
    onSuccess: () => {
      void router.push({ path: '/login' });
      queryClient.clear();
    },
    onError: (error) => {
      console.error('退出登录失败', error);
    },
  });
}
