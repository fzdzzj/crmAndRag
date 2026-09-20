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

/**
 * 前端埋点出口。当前后端没有接收端点，先以结构化日志落地，
 * 后续接入采集接口时只需替换这里，调用方不变。
 */
export const trackEvent = (name: string, payload: Record<string, unknown> = {}) => {
  console.info(`[track] ${name}`, payload);
};

/** 用户对错误处理的满意度投票（有用/无用） */
export const logErrorFeedback = (errorCode: string, helpful: boolean) => {
  trackEvent('ai_error_feedback', { code: errorCode, helpful });
};
