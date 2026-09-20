// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import type { AxiosResponse } from 'axios';
import { ApiError } from '../types.ts';
import { axiosInstance } from '../apiClient.ts';

function responseHandler() {
  const handlers = axiosInstance.interceptors.response.handlers;
  const fulfilled = handlers?.[0]?.fulfilled;
  if (!fulfilled) {
    throw new Error('响应拦截器未注册，apiClient 结构变了要同步本用例');
  }
  return fulfilled;
}

function serverResponse(code: unknown, msg?: string, responseType?: string): AxiosResponse {
  return {
    data: { code, msg },
    config: { responseType },
    status: 200,
    statusText: 'OK',
    headers: {},
  } as unknown as AxiosResponse;
}

/** 走真实响应拦截器：返回值即透传的响应，抛出的即 ApiError */
function handle(response: AxiosResponse): unknown {
  try {
    return responseHandler()(response);
  } catch (error) {
    return error;
  }
}

function handleError(code: unknown, msg?: string): ApiError {
  const error = handle(serverResponse(code, msg));
  expect(error).toBeInstanceOf(ApiError);
  return error as ApiError;
}

describe('响应拦截器接入错误码映射层', () => {
  it('成功响应原样透传', () => {
    const response = serverResponse(1, 'ok');
    expect(handle(response)).toBe(response);
  });

  it('字符串形式的成功码同样放行', () => {
    const response = serverResponse('1', 'ok');
    expect(handle(response)).toBe(response);
  });

  it('二进制响应不做业务码校验', () => {
    const response = serverResponse(12002, '', 'blob');
    expect(handle(response)).toBe(response);
  });

  it('业务错误码抛出 ApiError 并保留后端码值', () => {
    expect(handleError(12002, '权限不足：缺少分配权限').code).toBe('12002');
  });

  it('后端文案非空时以它为准', () => {
    const error = handleError(12002, '角色已被引用，不能删除');
    expect(error.message).toBe('角色已被引用，不能删除');
    expect(error.msg).toBe('角色已被引用，不能删除');
  });

  it('后端漏传文案时回落到映射层中文提示', () => {
    expect(handleError(12002, '').message).toBe('权限不足');
  });

  it('未知码值回落到系统级兜底文案', () => {
    expect(handleError(77777).message).toBe('系统繁忙，请稍后再试');
  });

  it('把错误码元数据挂在 ApiError 上供页面分支', () => {
    const error = handleError(96003, '');
    expect(error.meta?.category).toBe('platform');
    expect(error.meta?.action).toBe('relogin');
    expect(error.meta?.name).toBe('UNAUTHORIZED');
  });

  it('后端未定义的错误码没有元数据，但仍有码值可上报', () => {
    const error = handleError(424242, '自定义文案');
    expect(error.code).toBe('424242');
    expect(error.meta).toBeUndefined();
  });

  it('响应体没有 code 字段时按失败处理且不抛类型错误', () => {
    const error = handle({ data: null, config: {}, status: 200 } as unknown as AxiosResponse);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('系统繁忙，请稍后再试');
  });
});
