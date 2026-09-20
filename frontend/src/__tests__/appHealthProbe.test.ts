// @vitest-environment jsdom
// health 探针契约：请求落在 /api 同源代理前缀下；拿到任意 HTTP 响应（含 401、非业务壳纯文本）即可达，只有网络层失败才报不可达
import { createApp } from 'vue';
import axios, {
  AxiosError,
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('ant-design-vue', async () => {
  const { defineComponent: dc, h: hyp } = await import('vue');
  const ConfigProvider = dc({
    name: 'ConfigProvider',
    setup(_props, { slots }) {
      return () => hyp('div', slots.default?.());
    },
  });
  return { ConfigProvider };
});

vi.mock('vue-page-review', async () => {
  const { defineComponent: dc } = await import('vue');
  const ReviewTool = dc({
    name: 'ReviewTool',
    props: {
      active: { type: Boolean, default: false },
      pagePath: { type: String, default: '' },
      pageName: { type: String, default: '' },
    },
    setup: () => () => null,
  });
  return { ReviewTool };
});

import App from '../App.vue';
import { axiosInstance } from '@/api/apiClient';

type Canned =
  | { kind: 'response'; status: number; data: unknown }
  | { kind: 'network-error' };

const requestedUrls: string[] = [];
let canned: Canned = { kind: 'response', status: 200, data: 'ok' };

/** axios 把 baseURL 与 url 分开存，真实网络层拼接后才发出去，这里还原最终请求地址 */
function resolveUrl(config: InternalAxiosRequestConfig): string {
  const url = config.url ?? '';
  if (/^https?:\/\//i.test(url)) {
    return url;
  }
  return `${config.baseURL ?? ''}${url}`;
}

const fakeAdapter: AxiosAdapter = (config) => {
  requestedUrls.push(resolveUrl(config));
  if (canned.kind === 'network-error') {
    return Promise.reject(
      new AxiosError('Network Error', AxiosError.ERR_NETWORK, config),
    );
  }
  const response: AxiosResponse = {
    data: canned.data,
    status: canned.status,
    statusText: '',
    headers: {} as AxiosResponse['headers'],
    config,
  };
  // 真实适配器由 settle() 按 validateStatus 决定 resolve / reject，这里保持同构
  const validate = config.validateStatus;
  if (!validate || validate(canned.status)) {
    return Promise.resolve(response);
  }
  return Promise.reject(
    new AxiosError(
      `Request failed with status code ${canned.status}`,
      AxiosError.ERR_BAD_REQUEST,
      config,
      undefined,
      response,
    ),
  );
};

/** fetch 首参可以是 string | URL | Request，只关心最终请求地址 */
function fetchTarget(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  if (input instanceof URL) {
    return input.toString();
  }
  return input.url;
}

const fetchStub = vi.fn((input: RequestInfo | URL) => {
  requestedUrls.push(fetchTarget(input));
  if (canned.kind === 'network-error') {
    return Promise.reject(new TypeError('Failed to fetch'));
  }
  // 原生 fetch 只在网络层失败时 reject，HTTP 4xx/5xx 一样 resolve
  return Promise.resolve({
    status: canned.status,
    ok: canned.status < 400,
  } as Response);
});

function mountApp() {
  const host = document.createElement('div');
  const app = createApp(App);
  // 不装 vue-router，给模板里的 <router-view> 一个空壳，避免解析告警干扰取证
  app.component('RouterView', { render: () => null });
  app.config.globalProperties.$route = { path: '/', name: 'index' };
  app.mount(host);
  return () => app.unmount();
}

const healthyLog = 'API server is healthy';

describe('App.vue health 探针', () => {
  let defaultAdapter: typeof axios.defaults.adapter;
  let instanceAdapter: typeof axiosInstance.defaults.adapter;
  let logSpy: ReturnType<typeof vi.spyOn>;
  let errorSpy: ReturnType<typeof vi.spyOn>;
  let unmount: (() => void) | undefined;

  beforeEach(() => {
    requestedUrls.length = 0;
    canned = { kind: 'response', status: 200, data: 'ok' };
    defaultAdapter = axios.defaults.adapter;
    instanceAdapter = axiosInstance.defaults.adapter;
    axios.defaults.adapter = fakeAdapter;
    axiosInstance.defaults.adapter = fakeAdapter;
    vi.stubGlobal('fetch', fetchStub);
    logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    unmount?.();
    unmount = undefined;
    axios.defaults.adapter = defaultAdapter;
    axiosInstance.defaults.adapter = instanceAdapter;
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('探针只发一次，且请求地址落在 /api 同源代理前缀下', async () => {
    unmount = mountApp();

    await vi.waitFor(() => expect(requestedUrls).toHaveLength(1));
    expect(requestedUrls[0]).toMatch(/^\/api\/health/);
  });

  it('后端 200 返回纯文本（非 Result 业务壳）也算健康', async () => {
    canned = { kind: 'response', status: 200, data: 'ok' };
    unmount = mountApp();

    await vi.waitFor(() =>
      expect(logSpy).toHaveBeenCalledWith(healthyLog),
    );
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('未登录时后端返回 401 仍视为可达', async () => {
    canned = {
      kind: 'response',
      status: 401,
      data: { code: 10001, msg: 'Token不能为空或格式错误' },
    };
    unmount = mountApp();

    await vi.waitFor(() =>
      expect(logSpy).toHaveBeenCalledWith(healthyLog),
    );
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('只有网络层失败才报不可达', async () => {
    canned = { kind: 'network-error' };
    unmount = mountApp();

    await vi.waitFor(() =>
      expect(errorSpy).toHaveBeenCalledWith(
        'API server is not reachable:',
        expect.anything(),
      ),
    );
    expect(logSpy).not.toHaveBeenCalledWith(healthyLog);
  });

  it('apiClient 的业务壳拦截器会拒掉 /health 的纯文本响应，故探针不能走 axiosInstance', async () => {
    canned = { kind: 'response', status: 200, data: 'ok' };

    await expect(axiosInstance.get('/health')).rejects.toThrow(
      '系统繁忙，请稍后再试',
    );
  });
});
