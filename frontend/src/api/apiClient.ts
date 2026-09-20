import { TokenManager } from '@/utils/token.ts';
import {
  getErrorCodeMeta,
  isApiSuccess,
  resolveErrorMessage,
} from '@/constants/error-code-map.ts';
import { apiConfig } from './config.ts';
import { ApiError } from './types.ts';
import axios from 'axios';
import { createClient } from './axios/client/client.gen.ts';

/** 后端统一响应外壳 `Result`，业务码在 code 上 */
interface ResultEnvelope {
  code?: number | string;
  msg?: string;
}

const apiClient = axios.create(apiConfig)
apiClient.interceptors.response.use((response) => {
  // 文件下载等二进制响应不按 JSON 业务码校验
  if (
    response.config.responseType === 'blob' ||
    response.config.responseType === 'arraybuffer'
  ) {
    return response;
  }
  const { code, msg } = (response.data ?? {}) as ResultEnvelope;
  if (!isApiSuccess(code)) {
    const rawCode = code === undefined ? '' : String(code);
    throw new ApiError(
      rawCode,
      resolveErrorMessage(code, msg),
      getErrorCodeMeta(code),
    );
  }
  return response;
});
apiClient.interceptors.request.use((config) => {
  config.headers.token = TokenManager.getAccessToken();
  return config;
});

const client = createClient({
  axios: apiClient,
  // 业务错误（code !== 1）由响应拦截器抛 ApiError，这里强制 throw，
  // 否则 hey-api 默认 throwOnError=false 会把错误吞掉并当作成功返回值，
  // 导致前端误判成功（如新增重复客户仍提示"创建成功"）。
  throwOnError: true,
});
export default client;
/** 原始 axios 实例（用于 blob/文件下载等需要直接控制响应类型的场景） */
export const axiosInstance = apiClient;
