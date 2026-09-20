// 文案单一真相源：数字码与平台码文案一律取自 error-code-map，
// error-toast 只保留 SSE 通道专用码；三级退化顺序 = 登记码 → 服务端 msg → 码本身 → 通用文案。
import { readFileSync } from 'node:fs';
import { describe, it, expect, vi, beforeEach } from 'vitest';

const toast = vi.hoisted(() => ({ error: vi.fn() }));

vi.mock('ant-design-vue', () => ({
  message: toast,
}));

import { ApiError } from '@/api/types';
import { ApiErrorCode, errorCodeMap, resolveErrorMessage } from '@/constants/error-code-map';
import { friendlyMessage, showErrorToast } from '../error-toast';

const errorToast = toast.error;
const sourceText = readFileSync(new URL('../error-toast.ts', import.meta.url), 'utf8');

/** 映射层里 category 为 ai 的码，是 AI 场景数字码的真相源 */
const aiCodes = Object.values(errorCodeMap)
  .filter((meta) => meta.category === 'ai')
  .map((meta) => meta.code);

describe('文案单一真相源', () => {
  it('error-toast.ts 不再自带任何数字错误码字面量', () => {
    expect(sourceText).not.toMatch(/['"]\d{4,6}['"]/);
  });

  it('AI 段数字码文案与 resolveErrorMessage 逐字一致', () => {
    expect(aiCodes).toEqual(expect.arrayContaining([93001, 93002, 93003, 93004]));
    for (const code of aiCodes) {
      expect(friendlyMessage(code)).toBe(resolveErrorMessage(code));
      expect(friendlyMessage(String(code))).toBe(resolveErrorMessage(code));
    }
  });

  it('RATE_LIMITED 与平台码 96001 撞名，以映射层文案为准', () => {
    expect(friendlyMessage('RATE_LIMITED')).toBe(resolveErrorMessage(ApiErrorCode.RATE_LIMITED));
    expect(friendlyMessage('RATE_LIMITED', '操作过于频繁，请稍后再试')).toBe(
      resolveErrorMessage(ApiErrorCode.RATE_LIMITED),
    );
  });

  it('本地表只登记 SSE 通道专用码，且名为 SSE_ERROR_MESSAGES', async () => {
    const mod = await import('../error-toast');
    expect(Object.keys(mod.SSE_ERROR_MESSAGES).sort()).toEqual([
      'DEPENDENCY_UNAVAILABLE',
      'LLM_ERROR',
      'NETWORK_ERROR',
      'PARAM_INVALID',
      'RESUME_UNAVAILABLE',
      'SESSION_ARCHIVED',
      'UNAUTHORIZED',
    ]);
    for (const text of Object.values(mod.SSE_ERROR_MESSAGES)) {
      expect(text.length).toBeGreaterThan(6);
    }
  });
});

describe('friendlyMessage', () => {
  it('SSE 字符串码映射为可操作文案', () => {
    expect(friendlyMessage('LLM_ERROR')).toBe('模型暂时不可用，请稍后重试');
    expect(friendlyMessage('SESSION_ARCHIVED')).toBe('该会话已归档，请新建会话后继续');
  });

  it('NETWORK_ERROR 是前端合成码，不走映射层的 90003', () => {
    expect(friendlyMessage('NETWORK_ERROR')).toBe('网络连接中断，请检查网络设置后重试');
    expect(friendlyMessage('NETWORK_ERROR')).not.toBe(resolveErrorMessage(ApiErrorCode.NETWORK_ERROR));
  });

  it('已登记码优先于服务端 msg', () => {
    expect(friendlyMessage('93001', '后端塞的原始提示')).toBe(resolveErrorMessage(93001));
    expect(friendlyMessage('LLM_ERROR', '后端塞的原始提示')).toBe('模型暂时不可用，请稍后重试');
  });

  it('未登记码退化为服务端 msg', () => {
    expect(friendlyMessage('BRAND_NEW_CODE', '服务端给出的原始提示')).toBe('服务端给出的原始提示');
  });

  it('无码兜底值 0 不算登记码，服务端 msg 仍然优先', () => {
    expect(friendlyMessage(0, '后端 Result.error 的原始提示')).toBe('后端 Result.error 的原始提示');
  });

  it('映射层文案带 %s 占位符时不裸出占位符，退化到服务端 msg', () => {
    expect(friendlyMessage('92005', '第【3】行格式错误')).toBe('第【3】行格式错误');
    expect(friendlyMessage('92005')).toBe('92005');
  });

  it('未登记码且无 msg 时保留原始码，不静默吞掉', () => {
    expect(friendlyMessage('BRAND_NEW_CODE')).toBe('BRAND_NEW_CODE');
    expect(friendlyMessage(null)).toBe('AI 助手暂时不可用，请稍后重试');
  });
});

describe('showErrorToast', () => {
  beforeEach(() => {
    errorToast.mockClear();
  });

  it('ApiError 的 code 参与映射', () => {
    showErrorToast(new ApiError('93003', '执行失败'));
    expect(errorToast).toHaveBeenCalledWith({
      content: resolveErrorMessage(93003),
      key: 'ai-error',
    });
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

  it('冒烟：93001 的 toast 输出与 resolveErrorMessage 逐字相等', () => {
    showErrorToast(new ApiError('93001', '确认已超时，请重新发起'));
    expect(errorToast.mock.calls[0][0].content).toBe(resolveErrorMessage(93001));
    expect(errorToast.mock.calls[0][0].content).toBe('确认已超时，请重新发起');
  });
});
