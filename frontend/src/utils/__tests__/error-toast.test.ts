// 技术码 -> 用户可读文案；未知码退化为服务端 msg，最后退化为码本身（不静默）
import { describe, it, expect, vi, beforeEach } from 'vitest';

const toast = vi.hoisted(() => ({ error: vi.fn() }));

vi.mock('ant-design-vue', () => ({
  message: toast,
}));

import { ApiError } from '@/api/types';
import { AI_ERROR_MESSAGES, friendlyMessage, showErrorToast } from '../error-toast';

const errorToast = toast.error;

describe('friendlyMessage', () => {
  it('SSE 字符串码映射为可操作文案', () => {
    expect(friendlyMessage('LLM_ERROR')).toBe('模型暂时不可用，请稍后重试');
    expect(friendlyMessage('RATE_LIMITED')).toBe('操作过于频繁，请稍后再试');
    expect(friendlyMessage('SESSION_ARCHIVED')).toBe('该会话已归档，请新建会话后继续');
  });

  it('数字技术码 93001 与后端 ErrorCode 语义一致', () => {
    expect(friendlyMessage('93001')).toContain('超时');
    expect(friendlyMessage(93002)).toContain('已被处理');
  });

  it('未知码退化为服务端 msg', () => {
    expect(friendlyMessage('BRAND_NEW_CODE', '服务端给出的原始提示')).toBe('服务端给出的原始提示');
  });

  it('未知码且无 msg 时保留原始码，不静默吞掉', () => {
    expect(friendlyMessage('BRAND_NEW_CODE')).toBe('BRAND_NEW_CODE');
    expect(friendlyMessage(null)).toBe('AI 助手暂时不可用，请稍后重试');
  });

  it('覆盖全部已登记的 AI 错误码', () => {
    expect(Object.keys(AI_ERROR_MESSAGES).length).toBeGreaterThanOrEqual(12);
    for (const text of Object.values(AI_ERROR_MESSAGES)) {
      expect(text.length).toBeGreaterThan(6);
    }
  });
});

describe('showErrorToast', () => {
  beforeEach(() => {
    errorToast.mockClear();
  });

  it('ApiError 的 code 参与映射', () => {
    showErrorToast(new ApiError('93003', '执行失败'));
    expect(errorToast).toHaveBeenCalledWith({ content: 'AI 操作执行失败，请检查参数后重试', key: 'ai-error' });
  });

  it('无 code 的 Error 原样展示 message，不做二次包装', () => {
    showErrorToast(new Error('请求超时'));
    expect(errorToast).toHaveBeenCalledWith({ content: '请求超时', key: 'ai-error' });
  });

  it('字符串按技术码处理', () => {
    showErrorToast('DEPENDENCY_UNAVAILABLE');
    expect(errorToast.mock.calls[0][0].content).toBe(
      'AI 依赖服务未就绪，请稍后重试或联系管理员',
    );
  });
});
