import { message as antdMessage } from 'ant-design-vue';

/** 同 key 复用一条 toast，避免重试期间堆叠多条提示 */
export const AI_TOAST_KEY = 'ai-error';

/**
 * 技术码 → 用户可读文案。
 * 字符串码来自 SSE error 事件（AiChatServiceImpl#sendError），
 * 数字码来自 com.slz.crm.common.enumeration.ErrorCode 的 AI 段（93001-93004）。
 */
export const AI_ERROR_MESSAGES: Record<string, string> = {
  PARAM_INVALID: '消息内容不合法，请调整后重新发送',
  RATE_LIMITED: '操作过于频繁，请稍后再试',
  SESSION_ARCHIVED: '该会话已归档，请新建会话后继续',
  DEPENDENCY_UNAVAILABLE: 'AI 依赖服务未就绪，请稍后重试或联系管理员',
  LLM_ERROR: '模型暂时不可用，请稍后重试',
  UNAUTHORIZED: '登录状态已失效，请重新登录',
  RESUME_UNAVAILABLE: '本次回答无法续传，请重新提问',
  NETWORK_ERROR: '网络连接中断，请检查网络设置后重试',
  '93001': 'AI 操作确认已超时，请重新发起确认',
  '93002': '该操作已被处理，无需重复提交',
  '93003': 'AI 操作执行失败，请检查参数后重试',
  '93004': 'AI 操作参数不完整，请补齐后重试',
};

export const GENERIC_AI_ERROR_MESSAGE = 'AI 助手暂时不可用，请稍后重试';

/**
 * 已登记的技术码出可操作文案；未知码退化为服务端 msg，再退化为码本身，
 * 保证不出现空白提示，也不把 93001 这类裸码直接丢给用户。
 */
export function friendlyMessage(code: string | number | null | undefined, serverMessage?: string): string {
  const key = code === null || code === undefined ? '' : String(code);
  const mapped = AI_ERROR_MESSAGES[key];
  if (mapped) return mapped;
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
