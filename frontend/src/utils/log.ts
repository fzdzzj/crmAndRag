import type {
  AxiosError,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios';
import type { ApiResponse } from '../api/types.ts';

export const logOnRequestSuccess = (
  config: InternalAxiosRequestConfig,
) => {
  console.log('请求发送：', {
    method: config.method?.toUpperCase(),
    url: config.url,
    data: config.data,
    params: config.params,
    token: config.headers?.token,
    headers: config.headers,
  });
  return config;
};
export const logOnRequestError = (error: unknown) => {
  console.error('请求配置错误：', JSON.stringify(error));
  return error;
};
export const logOnResponseSuccess = (response: AxiosResponse<ApiResponse>) => {
  console.log('响应接收：', {
    code: response.data?.code,
    msg: response.data?.msg,
    data: response.data?.data,
    url: response.config?.url,
    more: {
      status: response.status,
      headers: response.headers,
      params: response.config?.params,
      config: response.config,
    },
  });
  return response.data;
};
export const logOnResponseError = (error: AxiosError) => {
  console.error('请求失败：', JSON.stringify(error));
  return error;
};
