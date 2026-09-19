// #region api响应与错误类型定义
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

  constructor(code: string, msg: string) {
    super(msg);
    this.name = 'ApiError';
    this.code = code;
    this.msg = msg;
  }
}

// #endregion api响应与错误类型定义

// #region 认证相关类型定义
export interface AuthResponse {
  token: string;
}
// #endregion 认证相关类型定义
