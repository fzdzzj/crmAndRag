import { QueryClient } from '@tanstack/vue-query';
import { type ApiError } from './types';
import { message } from 'ant-design-vue';
import { TokenManager } from '@/utils/token';
import router from '@/router/index-pages.ts';

function isApiError(error: unknown): error is ApiError {
  return error instanceof Error && error.name === 'ApiError';
}

/**
 * 创建 QueryClient 实例
 * 配置全局重试策略：apiError不重试
 */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // 全局重试策略：权限错误不重试，其他错误最多重试 3 次
        retry: (failureCount, error: unknown) => {
          // 检查是否是 ApiError
          if (isApiError(error)) {
            message.error(error.msg);
            if (error.code.startsWith('10')) {
              // token 相关错误，不重试，直接登出
              TokenManager.removeAccessToken();
              TokenManager.removePayload();
              void router.push({ name: 'login' });
            }
            return false;
          }

          return false;
        },
        // 默认的 gcTime 5 分钟
        gcTime: 5 * 60 * 1000,
        // 默认的 staleTime 1 分钟
        staleTime: 1 * 60 * 1000,
      },
      mutations: {
        // mutation 重试策略：权限错误不重试，其他错误最多重试 1 次
        retry: (failureCount, error: unknown) => {
          // 检查是否是 ApiError
          if (isApiError(error)) {
            message.error(error.msg);
            if (error.code.startsWith('10')) {
              // token 相关错误，不重试，直接登出
              TokenManager.removeAccessToken();
              TokenManager.removePayload();
              void router.push({ name: 'login' });
            }
            return false;
          }

          // 非 ApiError，最多重试 1 次
          return false;
        },
      },
    },
  });
}

/**
 * 导出单例实例
 */
export const queryClient = createQueryClient();
