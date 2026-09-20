// 重试驱动契约：只重试可恢复错误、按退避等待、超限后抛出最后一个错误、abort 立即停止
import { describe, it, expect, vi, afterEach } from 'vitest';
import { isRetryableStatus, retryWithBackoff } from '../retryWithBackoff';

class NetError extends Error {
  constructor() {
    super('Failed to fetch');
    this.name = 'TypeError';
  }
}

const neverSleep = () => Promise.resolve();
const retryableNet = (e: unknown) => e instanceof Error && e.message.startsWith('Failed to fetch');

describe('retryWithBackoff', () => {
  it('在第 3 次尝试成功，前两次按 1s / 2s 退避重试', async () => {
    const delays: number[] = [];
    let calls = 0;
    const result = await retryWithBackoff(
      () => {
        calls += 1;
        if (calls < 3) throw new NetError();
        return 'ok';
      },
      {
        maxRetries: 5,
        isRetryable: retryableNet,
        sleep: neverSleep,
        onRetry: (_retry, delayMs) => {
          delays.push(delayMs);
        },
      },
    );

    expect(result).toBe('ok');
    expect(calls).toBe(3);
    expect(delays).toHaveLength(2);
    expect(delays[0]).toBeGreaterThanOrEqual(900);
    expect(delays[0]).toBeLessThanOrEqual(1100);
    expect(delays[1]).toBeGreaterThanOrEqual(1800);
    expect(delays[1]).toBeLessThanOrEqual(2200);
  });

  it('重试用尽后抛出最后一个错误，共尝试 1 + maxRetries 次', async () => {
    let calls = 0;
    await expect(
      retryWithBackoff(
        () => {
          calls += 1;
          throw new Error(`Failed to fetch #${calls}`);
        },
        { maxRetries: 5, isRetryable: retryableNet, sleep: neverSleep },
      ),
    ).rejects.toThrow('Failed to fetch #6');
    expect(calls).toBe(6);
  });

  it('不可重试错误只调用一次即抛出', async () => {
    let calls = 0;
    await expect(
      retryWithBackoff(
        () => {
          calls += 1;
          throw new Error('LLM_ERROR');
        },
        { maxRetries: 5, isRetryable: retryableNet, sleep: neverSleep },
      ),
    ).rejects.toThrow('LLM_ERROR');
    expect(calls).toBe(1);
  });

  it('signal 已中止则不再发起重试，抛出最后一个错误', async () => {
    const controller = new AbortController();
    controller.abort();
    let calls = 0;
    await expect(
      retryWithBackoff(
        () => {
          calls += 1;
          throw new NetError();
        },
        {
          maxRetries: 5,
          isRetryable: retryableNet,
          sleep: neverSleep,
          signal: controller.signal,
        },
      ),
    ).rejects.toThrow('Failed to fetch');
    expect(calls).toBe(1);
  });
});

describe('isRetryableStatus', () => {
  it('瞬时故障状态可重试', () => {
    for (const status of [408, 429, 500, 502, 503, 504]) {
      expect(isRetryableStatus(status)).toBe(true);
    }
  });

  it('业务类 4xx 不重试', () => {
    for (const status of [400, 401, 403, 404, 422]) {
      expect(isRetryableStatus(status)).toBe(false);
    }
  });
});

describe('默认 sleep（虚拟定时器）', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('等待期未满不重试，满 1s 后发起第 2 次尝试', async () => {
    vi.useFakeTimers();
    let calls = 0;
    const pending = retryWithBackoff(
      () => {
        calls += 1;
        if (calls < 2) throw new NetError();
        return 'ok';
      },
      { maxRetries: 5, isRetryable: retryableNet },
    );

    await vi.advanceTimersByTimeAsync(0);
    expect(calls).toBe(1);
    await vi.advanceTimersByTimeAsync(899);
    expect(calls).toBe(1);
    await vi.advanceTimersByTimeAsync(302);
    await expect(pending).resolves.toBe('ok');
    expect(calls).toBe(2);
  });

  it('等待期间 signal 中止则立即以 AbortError 结束，不再重试', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    let calls = 0;
    const pending = retryWithBackoff(
      () => {
        calls += 1;
        throw new NetError();
      },
      { maxRetries: 5, isRetryable: retryableNet, signal: controller.signal },
    );

    await vi.advanceTimersByTimeAsync(0);
    controller.abort();
    await expect(pending).rejects.toThrow(/Aborted/);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls).toBe(1);
  });
});
