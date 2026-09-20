export interface BackoffConfig {
  initialDelayMs: number;
  maxDelayMs: number;
  multiplier: number;
  /** 抖动幅度，0.1 表示 ±10% */
  randomFactor: number;
}

export const DEFAULT_BACKOFF_CONFIG: BackoffConfig = {
  initialDelayMs: 1000,
  maxDelayMs: 30000,
  multiplier: 2.0,
  randomFactor: 0.1,
};

/**
 * attempt 从 1 开始；返回带抖动的等待毫秒数，上限 maxDelayMs。
 */
export function calculateDelay(attempt: number, cfg: BackoffConfig = DEFAULT_BACKOFF_CONFIG): number {
  const base = cfg.initialDelayMs * Math.pow(cfg.multiplier, attempt - 1);
  const jitter = base * cfg.randomFactor * (Math.random() * 2 - 1);
  return Math.min(base + jitter, cfg.maxDelayMs);
}

/** 瞬时故障状态可重试；其余 4xx 多为业务错误，重试只会重复失败 */
export const RETRYABLE_HTTP_STATUS: readonly number[] = [408, 425, 429, 500, 502, 503, 504];

export function isRetryableStatus(status: number): boolean {
  return RETRYABLE_HTTP_STATUS.includes(status);
}

export interface RetryOptions {
  /** 首次之外的最大重试次数 */
  maxRetries?: number;
  backoff?: BackoffConfig;
  isRetryable?: (error: unknown) => boolean;
  /** retry 从 1 开始，用于展示 "(2/5)" 这类进度 */
  onRetry?: (retry: number, delayMs: number, error: unknown) => void;
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
  signal?: AbortSignal;
}

function abortableSleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * 以指数退避执行 task。task 收到 1 起的尝试序号；
 * 重试用尽 / 错误不可重试 / signal 已中止时，原样抛出最后一个错误。
 */
export async function retryWithBackoff<T>(
  task: (attempt: number) => T | Promise<T>,
  options: RetryOptions = {},
): Promise<T> {
  const {
    maxRetries = 5,
    backoff = DEFAULT_BACKOFF_CONFIG,
    isRetryable = () => true,
    onRetry,
    sleep = abortableSleep,
    signal,
  } = options;

  let attempt = 0;
  for (;;) {
    attempt += 1;
    try {
      return await task(attempt);
    } catch (error) {
      const shouldStop = attempt > maxRetries || !isRetryable(error) || signal?.aborted === true;
      if (shouldStop) throw error;
      const delayMs = calculateDelay(attempt, backoff);
      onRetry?.(attempt, delayMs, error);
      await sleep(delayMs, signal);
    }
  }
}
