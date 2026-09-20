// 埋点：错误反馈要留下可检索的结构化事件
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { logErrorFeedback, trackEvent } from '../log';

describe('trackEvent', () => {
  let info: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    info = vi.spyOn(console, 'info').mockImplementation(() => {});
  });
  afterEach(() => {
    info.mockRestore();
  });

  it('输出带事件名的结构化日志', () => {
    trackEvent('ai_error_feedback', { code: 'LLM_ERROR', helpful: false });
    expect(info).toHaveBeenCalledWith('[track] ai_error_feedback', { code: 'LLM_ERROR', helpful: false });
  });
});

describe('logErrorFeedback', () => {
  let info: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    info = vi.spyOn(console, 'info').mockImplementation(() => {});
  });
  afterEach(() => {
    info.mockRestore();
  });

  it('上报 ai_error_feedback 事件并携带技术码', () => {
    logErrorFeedback('93001', true);
    const [head, payload] = info.mock.calls[0];
    expect(head).toBe('[track] ai_error_feedback');
    expect(payload).toEqual({ code: '93001', helpful: true });
  });

  it('“无用”同样上报，便于统计错误处理满意度', () => {
    logErrorFeedback('NETWORK_ERROR', false);
    const [, payload] = info.mock.calls[0];
    expect(payload.helpful).toBe(false);
  });
});
