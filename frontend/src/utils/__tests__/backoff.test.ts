// 指数退避算法契约：initial 1000ms → max 30000ms，multiplier 2.0，jitter ±10%
import { describe, it, expect } from 'vitest';
import { calculateDelay, DEFAULT_BACKOFF_CONFIG } from '../retryWithBackoff';

const SAMPLES = 400;

function sampleDelays(attempt: number): number[] {
  return Array.from({ length: SAMPLES }, () => calculateDelay(attempt, DEFAULT_BACKOFF_CONFIG));
}

function baseDelayOf(attempt: number): number {
  return DEFAULT_BACKOFF_CONFIG.initialDelayMs * Math.pow(DEFAULT_BACKOFF_CONFIG.multiplier, attempt - 1);
}

describe('calculateDelay', () => {
  it('attempt 1 → 1000ms ±10%，且抖动确实生效', () => {
    const values = sampleDelays(1);
    for (const v of values) {
      expect(v).toBeGreaterThanOrEqual(900);
      expect(v).toBeLessThanOrEqual(1100);
    }
    expect(new Set(values).size).toBeGreaterThan(1);
    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    expect(Math.abs(mean - 1000)).toBeLessThan(50);
  });

  it('每次重试按 multiplier 2.0 倍增，抖动不越出 ±10% 区间', () => {
    for (const attempt of [2, 3, 4, 5]) {
      const base = baseDelayOf(attempt);
      for (const v of sampleDelays(attempt)) {
        expect(v).toBeGreaterThanOrEqual(base * 0.9);
        expect(v).toBeLessThanOrEqual(base * 1.1);
      }
    }
    // 区间互不重叠 => 单调倍增
    expect(Math.min(...sampleDelays(3))).toBeGreaterThan(Math.max(...sampleDelays(2)));
  });

  it('上限封顶 30000ms：attempt 5 不越界，attempt 15 恒等于 30000', () => {
    for (const v of sampleDelays(5)) {
      expect(v).toBeLessThanOrEqual(DEFAULT_BACKOFF_CONFIG.maxDelayMs);
    }
    for (const v of sampleDelays(15)) {
      expect(v).toBe(30000);
    }
  });
});
