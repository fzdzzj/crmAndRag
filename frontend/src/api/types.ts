// #region api响应与错误类型定义
import type { ErrorCodeMeta } from '@/constants/error-code-map.ts';

/**
 * 统一 API 响应结构
 */
export interface ApiResponse<T = unknown> {
  code: string;
  msg: string;
  data: T;
}
/**
 * API 错误类
 */
export class ApiError extends Error {
  code: string;
  msg: string;
  /** 错误码元数据（分类/建议动作）；后端返回了映射层不认识的码值时为 undefined */
  meta?: ErrorCodeMeta;

  constructor(code: string, msg: string, meta?: ErrorCodeMeta) {
    super(msg);
    this.name = 'ApiError';
    this.code = code;
    this.msg = msg;
    this.meta = meta;
  }
}

// #endregion api响应与错误类型定义

// #region 认证相关类型定义
export interface AuthResponse {
  token: string;
}
// #endregion 认证相关类型定义
