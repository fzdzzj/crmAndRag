import { message as antdMessage } from 'ant-design-vue';
import {
  ApiErrorCode,
  UNKNOWN_ERROR_CODE,
  getErrorCodeMeta,
  resolveErrorMessage,
} from '@/constants/error-code-map';

/** 同 key 复用一条 toast，避免重试期间堆叠多条提示 */
export const AI_TOAST_KEY = 'ai-error';

/**
 * 仅 SSE 通道会出现的字符串码 → 用户可读文案（见 AiChatServiceImpl#sendError）。
 * 后端错误枚举里的数字码不抄在这里：文案单一真相源是 error-code-map，抄两份必然漂移。
 * NETWORK_ERROR 由前端合成（请求根本没到后端），刻意不走映射层的网络码。
 */
export const SSE_ERROR_MESSAGES: Record<string, string> = {
  PARAM_INVALID: '消息内容不合法，请调整后重新发送',
  SESSION_ARCHIVED: '该会话已归档，请新建会话后继续',
  DEPENDENCY_UNAVAILABLE: 'AI 依赖服务未就绪，请稍后重试或联系管理员',
  LLM_ERROR: '模型暂时不可用，请稍后重试',
  UNAUTHORIZED: '登录状态已失效，请重新登录',
  RESUME_UNAVAILABLE: '本次回答无法续传，请重新提问',
  NETWORK_ERROR: '网络连接中断，请检查网络设置后重试',
};

export const GENERIC_AI_ERROR_MESSAGE = 'AI 助手暂时不可用，请稍后重试';

/** 与后端撞名的码（如平台码名）→ 码值；不是码名则 undefined */
function codeValueOf(name: string): number | undefined {
  const value = (ApiErrorCode as Record<string, number | undefined>)[name];
  return typeof value === 'number' ? value : undefined;
}

/**
 * 已登记的技术码出可操作文案；未知码退化为服务端 msg，再退化为码本身，
 * 保证不出现空白提示，也不把后端数字码这类裸码直接丢给用户。
 */
export function friendlyMessage(code: string | number | null | undefined, serverMessage?: string): string {
  const key = code === null || code === undefined ? '' : String(code);
  const fromSse = SSE_ERROR_MESSAGES[key];
  if (fromSse) return fromSse;
  const numeric = /^\d+$/.test(key) ? Number(key) : codeValueOf(key);
  if (numeric !== undefined && numeric !== UNKNOWN_ERROR_CODE) {
    const meta = getErrorCodeMeta(numeric);
    // 带 %s 的文案要行号之类的实参，前端拿不到，此时后端 msg 才有信息量
    if (meta && !meta.message.includes('%s')) return resolveErrorMessage(numeric);
  }
  const raw = serverMessage?.trim() || key.trim();
  return raw || GENERIC_AI_ERROR_MESSAGE;
}

/** 从任意抛出错里取出技术码与服务端原文，供映射与埋点复用 */
export function describeError(err: unknown): { code: string | null; msg?: string } {
  if (typeof err === 'string' || typeof err === 'number') {
    return { code: String(err) };
  }
  if (err instanceof Error) {
    const { code, msg } = err as Partial<{ code: unknown; msg: unknown }>;
    const textOf = (v: unknown): string | undefined =>
      typeof v === 'string' || typeof v === 'number' ? String(v) : undefined;
    return { code: textOf(code) ?? null, msg: textOf(msg) ?? err.message };
  }
  return { code: null };
}

export function showErrorToast(err: unknown, serverMessage?: string): void {
  const { code, msg } = describeError(err);
  antdMessage.error({
    content: friendlyMessage(code, serverMessage ?? msg),
    key: AI_TOAST_KEY,
  });
}
