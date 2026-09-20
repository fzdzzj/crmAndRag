// @vitest-environment jsdom
// SSE 断线重连契约：正常流聚合、抖动续传、重试用尽出友好文案、error 事件按码映射、手动停止不重试
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createApp, defineComponent, h } from 'vue';

vi.mock('@/api/apiClient.ts', () => ({
  axiosInstance: { post: vi.fn() },
  default: { post: vi.fn() },
}));
vi.mock('@/utils/token.ts', () => ({
  TokenManager: { getAccessToken: () => 'fake-token' },
}));

const toast = vi.hoisted(() => ({
  error: vi.fn(),
  warning: vi.fn(),
  info: vi.fn(),
  success: vi.fn(),
  destroy: vi.fn(),
}));

vi.mock('ant-design-vue', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...(actual as object),
    message: toast,
  };
});

import { useAiChat } from '../useAiChat';
import { frame, httpErrorResponse, sseResponse, sseResponseThatDrops } from '@/utils/__tests__/mockSseStream';

const errorToast = toast.error;
const warnToast = toast.warning;
const destroyToast = toast.destroy;

function mountChat() {
  let chat!: ReturnType<typeof useAiChat>;
  const Host = defineComponent({
    setup() {
      chat = useAiChat();
      return () => h('div');
    },
  });
  const host = document.createElement('div');
  const app = createApp(Host);
  app.mount(host);
  return { chat, unmount: () => app.unmount() };
}

/** 启动发送并把虚拟时间推到退避上限之外 */
async function send(chat: ReturnType<typeof useAiChat>, text: string) {
  const pending = chat.sendMessage(text);
  await vi.advanceTimersByTimeAsync(120_000);
  await pending;
}

const startFrame = frame('start', { sessionId: '11', generationId: 'g1', assistantMessageId: 5 }, 'g1:1');

describe('useAiChat SSE 重连', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    global.fetch = vi.fn();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('正常流聚合为一条 assistant 消息，不触发任何重试提示', async () => {
    const { chat, unmount } = mountChat();
    vi.mocked(global.fetch).mockResolvedValue(
      sseResponse([startFrame, frame('delta', { content: '你' }, 'g1:2'), frame('delta', { content: '好' }, 'g1:3'), frame('done', { sessionId: '11' }, 'g1:4')]),
    );

    await send(chat, 'hi');

    expect(chat.messages.value).toHaveLength(2);
    expect(chat.messages.value[1].content).toBe('你好');
    expect(chat.error.value).toBeNull();
    expect(chat.isStreaming.value).toBe(false);
    expect(global.fetch).toHaveBeenCalledTimes(1);
    expect(warnToast).not.toHaveBeenCalled();
    unmount();
  });

  it('流中断后带 Last-Event-ID 续传，内容不重复且清掉重试提示', async () => {
    const { chat, unmount } = mountChat();
    const dropped = sseResponseThatDrops(
      [startFrame, frame('delta', { content: '你' }, 'g1:2'), frame('delta', { content: '好' }, 'g1:3')],
      2,
    );
    const resumed = sseResponse([
      frame('delta', { content: '好' }, 'g1:3'),
      frame('delta', { content: '！' }, 'g1:4'),
      frame('done', { sessionId: '11' }, 'g1:5'),
    ]);
    vi.mocked(global.fetch)
      .mockResolvedValueOnce(dropped)
      .mockResolvedValueOnce(resumed);

    await send(chat, 'hi');

    expect(global.fetch).toHaveBeenCalledTimes(2);
    const retryHeaders = vi.mocked(global.fetch).mock.calls[1][1] as RequestInit;
    expect((retryHeaders.headers as Record<string, string>)['Last-Event-ID']).toBe('g1:2');
    expect(chat.messages.value[1].content).toBe('你好！');
    expect(warnToast).toHaveBeenCalledWith({ content: '网络连接不稳定，正在重试... (1/5)', key: 'ai-retry' });
    expect(destroyToast).toHaveBeenCalledWith('ai-retry');
    expect(chat.error.value).toBeNull();
    unmount();
  });

  it('退避重试用尽后按技术码弹友好文案', async () => {
    const { chat, unmount } = mountChat();
    vi.mocked(global.fetch).mockImplementation(() =>
      Promise.resolve(httpErrorResponse(503, JSON.stringify({ code: 'LLM_ERROR', msg: '模型调用失败，请稍后重试' }))),
    );

    await send(chat, 'hi');

    expect(global.fetch).toHaveBeenCalledTimes(6);
    expect(chat.errorCode.value).toBe('LLM_ERROR');
    expect(chat.error.value).toBe('模型暂时不可用，请稍后重试');
    expect(errorToast).toHaveBeenCalledWith({ content: '模型暂时不可用，请稍后重试', key: 'ai-error' });
    unmount();
  });

  it('error 事件按码映射文案并保留原始码给反馈按钮，不重试', async () => {
    const { chat, unmount } = mountChat();
    vi.mocked(global.fetch).mockResolvedValue(
      sseResponse([startFrame, frame('error', { code: 'RATE_LIMITED', msg: '操作过于频繁，请稍后再试' }, 'g1:2')]),
    );

    await send(chat, 'hi');

    expect(global.fetch).toHaveBeenCalledTimes(1);
    expect(chat.errorCode.value).toBe('RATE_LIMITED');
    expect(chat.error.value).toBe('操作过于频繁，请稍后再试');
    unmount();
  });

  it('手动停止（AbortError）不重试、不弹错误提示', async () => {
    const { chat, unmount } = mountChat();
    vi.mocked(global.fetch).mockRejectedValue(new DOMException('Aborted', 'AbortError'));

    await send(chat, 'hi');

    expect(global.fetch).toHaveBeenCalledTimes(1);
    expect(chat.error.value).toBeNull();
    expect(errorToast).not.toHaveBeenCalled();
    expect(chat.messages.value[1].interrupted).toBe(true);
    unmount();
  });

  it('流被静默截断时同样重连，持续失败则提示网络中断', async () => {
    const { chat, unmount } = mountChat();
    vi.mocked(global.fetch).mockImplementation(() =>
      Promise.resolve(sseResponse([startFrame, frame('delta', { content: '半' }, 'g1:2')])),
    );

    await send(chat, 'hi');

    expect(global.fetch).toHaveBeenCalledTimes(6);
    expect(chat.errorCode.value).toBe('NETWORK_ERROR');
    expect(chat.error.value).toBe('网络连接中断，请检查网络设置后重试');
    unmount();
  });
});
