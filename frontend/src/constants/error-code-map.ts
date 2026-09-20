/**
 * 统一错误码映射层。
 *
 * 真相源是后端两个枚举：
 * - `src/main/java/com/slz/crm/common/enumeration/ErrorCode.java`（业务域，含 i18n messageKey）
 * - `src/main/java/com/slz/crm/platform/contract/PlatformErrorCode.java`（平台域，96xxx）
 *
 * 两侧漂移由 `src/test/java/com/slz/crm/contract/ErrorCodeMapParityTest.java` 在 CI 阶段 1 拦截；
 * 本文件内 `Record<ApiErrorCodeValue, ErrorCodeMeta>` 的标注让"漏码/多码/码写错"在编译期即报错。
 */

/** 后端 `Result.success()` 固定回填的成功码 */
export const API_SUCCESS_CODE = 1;

/** 后端 `Result.error(msg)`（无错误码）回填的通用失败码 */
export const GENERIC_ERROR_CODE = 0;

/** 映射层兜底码：后端返回了未知码值时按此码取兜底文案 */
export const UNKNOWN_ERROR_CODE = GENERIC_ERROR_CODE;

export const ApiErrorCode = {
  AUTH_ERROR: 10000,
  TOKEN_ERROR: 10001,
  TOKEN_EXPIRED: 10002,
  TOKEN_INVALID: 10003,
  TOKEN_PARSE_FAILED: 10004,
  PERMISSION_ERROR: 12000,
  USER_NOT_LOGIN: 12001,
  PERMISSION_DENIED: 12002,
  USER_STATUS_EXCEPTION: 12003,
  USER_IS_LOCK: 12004,
  USER_IS_DISABLE: 12005,
  USER_IS_FROZEN: 12006,
  USER_IS_QUIT: 12007,
  ROLE_NOT_EXISTS: 13001,
  ADMIN_EXISTS_ONLY_ONE: 13002,
  COMPANY_ERROR: 20000,
  COMPANY_NOT_EXISTS: 20001,
  COMPANY_DATA_EMPTY: 20002,
  CONTACT_ERROR: 21000,
  CONTACT_NOT_EXISTS: 21001,
  CONTACT_ALREADY_EXISTS: 21002,
  OPPORTUNITY_NOT_EXISTS: 30001,
  OPPORTUNITY_ALREADY_EXISTS: 30002,
  OPPORTUNITY_MUST_BE_CLOSED: 30003,
  SALES_OPPORTUNITY_ALREADY_NULL: 30004,
  SALES_STAGE_APPROVAL_NOT_EXISTS: 30005,
  APPROVAL_FILE_EMPTY: 30006,
  CONTRACT_ERROR: 31000,
  CONTRACT_NOT_EXISTS: 31001,
  ORDER_NOT_EXISTS: 31002,
  CONTRACT_STATUS_ERROR: 31003,
  AMOUNT_FORMAT_ERROR: 31004,
  TASK_ERROR: 40000,
  TASK_NOT_EXISTS: 40001,
  TASK_OWNER_NOT_EXISTS: 40002,
  TASK_DEADLINE_INVALID: 40003,
  PARAM_EMPTY: 50001,
  PARAM_FORMAT_ERROR: 50002,
  FILE_FORMAT_ERROR: 50003,
  EMAIL_FORMAT_ERROR: 50004,
  PHONE_FORMAT_ERROR: 50005,
  PARAM_LENGTH_EXCEEDED: 50006,
  PARAM_OUT_OF_RANGE: 50007,
  PARAM_REQUIRED: 50008,
  SYSTEM_ERROR: 90000,
  SYSTEM_BUSY: 90001,
  DATABASE_ERROR: 90002,
  NETWORK_ERROR: 90003,
  INTERNAL_SERVER_ERROR: 90004,
  SERVICE_UNAVAILABLE: 90005,
  RATE_LIMIT_EXCEEDED: 90006,
  THIRD_PARTY_SERVICE_ERROR: 90007,
  DATA_NULL: 91001,
  PAGE_DATA_NULL: 91002,
  DATA_DELETE_FAILED: 91003,
  BUSINESS_ERROR: 92000,
  UPDATE_FAILED: 92001,
  FILE_CREATE_FAILED: 92002,
  FILE_DELETE_FAILED: 92003,
  FILE_READ_FAILED: 92004,
  EXCEL_FORMAT_ERROR: 92005,
  EXCEL_COMPANY_NOT_EXISTS: 92006,
  EXCEL_CONTACT_NOT_EXISTS: 92007,
  ID_NOT_EXISTS: 92008,
  EMAIL_EXISTS: 92009,
  COMPANY_OR_CONTACT_NULL: 92010,
  COMPANY_OR_CONTACT_NOT_MATCH: 92011,
  APPROVAL_ATTACHMENT_NULL: 92012,
  BUSINESS_ACTIVITY_NOT_EXISTS: 92013,
  BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS: 92014,
  BUSINESS_ACTIVITY_USER_NOT_EXISTS: 92015,
  COMPANY_HAS_CONTACTS: 92016,
  CONTRACT_MUST_BE_ABANDONED: 92017,
  PAYMENT_NOT_EXISTS: 92018,
  PAYMENT_DATA_NULL: 92019,
  TASK_ASSIGNEE_NOT_EXISTS: 92020,
  TASK_DEADLINE_ILLEGAL: 92021,
  TASK_STATUS_ERROR: 92022,
  TASK_PRIORITY_ERROR: 92023,
  PRIVACY_ERROR: 92024,
  PASSWORD_OR_EMAIL_ERROR: 92025,
  USER_NOT_EXIST_PERMISSION: 92026,
  NOT_ENABLED: 92027,
  AI_ACTION_TIMEOUT: 93001,
  AI_ACTION_ALREADY_HANDLED: 93002,
  AI_ACTION_EXECUTE_FAILED: 93003,
  AI_ACTION_PARAM_MISSING: 93004,
  RATE_LIMITED: 96001,
  QUOTA_EXCEEDED: 96002,
  UNAUTHORIZED: 96003,
  CONTENT_RISK: 96004,
  FORBIDDEN: 96005,
  TOKEN_BUDGET_EXCEEDED: 96006,
  VALIDATION: 96007,
  DEPENDENCY_UNAVAILABLE: 96008,
  RESUME_UNAVAILABLE: 96009,
  INTERNAL: 96010,
} as const;

export type ApiErrorCodeName = keyof typeof ApiErrorCode;
export type ApiErrorCodeValue = (typeof ApiErrorCode)[ApiErrorCodeName];

/** 与后端错误码号段一一对应 */
export type ErrorCodeCategory =
  | 'auth'
  | 'permission'
  | 'user'
  | 'company'
  | 'contact'
  | 'opportunity'
  | 'contract'
  | 'task'
  | 'param'
  | 'system'
  | 'data'
  | 'business'
  | 'ai'
  | 'platform';

/** 前端拿到该错误后应当引导用户做什么 */
export type ErrorCodeAction =
  | 'relogin'
  | 'retry'
  | 'fix-input'
  | 'contact-admin'
  | 'none';

export interface ErrorCodeMeta {
  name: ApiErrorCodeName | 'UNKNOWN';
  code: ApiErrorCodeValue | typeof UNKNOWN_ERROR_CODE;
  /** 后端枚举里的中文文案，可能含 `%s` 占位符 */
  message: string;
  /** 仅业务域枚举有；平台域后端不回填 i18n 键 */
  messageKey?: string;
  category: ErrorCodeCategory;
  action: ErrorCodeAction;
}

export const errorCodeMap: Record<ApiErrorCodeValue, ErrorCodeMeta> = {
  10000: {
    name: 'AUTH_ERROR',
    code: 10000,
    message: '认证授权错误',
    messageKey: 'error.auth.category',
    category: 'auth',
    action: 'relogin',
  },
  10001: {
    name: 'TOKEN_ERROR',
    code: 10001,
    message: 'Token不能为空或格式错误',
    messageKey: 'error.token.empty',
    category: 'auth',
    action: 'relogin',
  },
  10002: {
    name: 'TOKEN_EXPIRED',
    code: 10002,
    message: 'Token已过期',
    messageKey: 'error.token.expired',
    category: 'auth',
    action: 'relogin',
  },
  10003: {
    name: 'TOKEN_INVALID',
    code: 10003,
    message: 'Token无效',
    messageKey: 'error.token.invalid',
    category: 'auth',
    action: 'relogin',
  },
  10004: {
    name: 'TOKEN_PARSE_FAILED',
    code: 10004,
    message: 'JWT解析失败',
    messageKey: 'error.token.parse.failed',
    category: 'auth',
    action: 'relogin',
  },
  12000: {
    name: 'PERMISSION_ERROR',
    code: 12000,
    message: '权限管理错误',
    messageKey: 'error.permission.category',
    category: 'permission',
    action: 'none',
  },
  12001: {
    name: 'USER_NOT_LOGIN',
    code: 12001,
    message: '用户未登录',
    messageKey: 'error.user.not.login',
    category: 'permission',
    action: 'relogin',
  },
  12002: {
    name: 'PERMISSION_DENIED',
    code: 12002,
    message: '权限不足',
    messageKey: 'error.permission.denied',
    category: 'permission',
    action: 'none',
  },
  12003: {
    name: 'USER_STATUS_EXCEPTION',
    code: 12003,
    message: '用户状态异常',
    messageKey: 'error.user.status.exception',
    category: 'permission',
    action: 'contact-admin',
  },
  12004: {
    name: 'USER_IS_LOCK',
    code: 12004,
    message: '用户已被锁定',
    messageKey: 'error.user.is.lock',
    category: 'permission',
    action: 'contact-admin',
  },
  12005: {
    name: 'USER_IS_DISABLE',
    code: 12005,
    message: '用户已被禁用',
    messageKey: 'error.user.is.disable',
    category: 'permission',
    action: 'contact-admin',
  },
  12006: {
    name: 'USER_IS_FROZEN',
    code: 12006,
    message: '用户已被冻结',
    messageKey: 'error.user.is.frozen',
    category: 'permission',
    action: 'contact-admin',
  },
  12007: {
    name: 'USER_IS_QUIT',
    code: 12007,
    message: '用户已离职',
    messageKey: 'error.user.is.quit',
    category: 'permission',
    action: 'contact-admin',
  },
  13001: {
    name: 'ROLE_NOT_EXISTS',
    code: 13001,
    message: '角色不存在',
    messageKey: 'error.role.not.exists',
    category: 'user',
    action: 'none',
  },
  13002: {
    name: 'ADMIN_EXISTS_ONLY_ONE',
    code: 13002,
    message: '终极管理员只能存在一位',
    messageKey: 'error.admin.exists.only.one',
    category: 'user',
    action: 'none',
  },
  20000: {
    name: 'COMPANY_ERROR',
    code: 20000,
    message: '客户公司管理错误',
    messageKey: 'error.company.category',
    category: 'company',
    action: 'none',
  },
  20001: {
    name: 'COMPANY_NOT_EXISTS',
    code: 20001,
    message: '公司不存在',
    messageKey: 'error.company.not.exists',
    category: 'company',
    action: 'none',
  },
  20002: {
    name: 'COMPANY_DATA_EMPTY',
    code: 20002,
    message: '公司数据为空',
    messageKey: 'error.company.data.empty',
    category: 'company',
    action: 'none',
  },
  21000: {
    name: 'CONTACT_ERROR',
    code: 21000,
    message: '联系人管理错误',
    messageKey: 'error.contact.category',
    category: 'contact',
    action: 'none',
  },
  21001: {
    name: 'CONTACT_NOT_EXISTS',
    code: 21001,
    message: '联系人不存在',
    messageKey: 'error.contact.not.exists',
    category: 'contact',
    action: 'none',
  },
  21002: {
    name: 'CONTACT_ALREADY_EXISTS',
    code: 21002,
    message: '联系人已存在',
    messageKey: 'error.contact.already.exists',
    category: 'contact',
    action: 'none',
  },
  30001: {
    name: 'OPPORTUNITY_NOT_EXISTS',
    code: 30001,
    message: '商机不存在',
    messageKey: 'error.opportunity.not.exists',
    category: 'opportunity',
    action: 'none',
  },
  30002: {
    name: 'OPPORTUNITY_ALREADY_EXISTS',
    code: 30002,
    message: '商机已存在',
    messageKey: 'error.opportunity.already.exists',
    category: 'opportunity',
    action: 'none',
  },
  30003: {
    name: 'OPPORTUNITY_MUST_BE_CLOSED',
    code: 30003,
    message: '商机必须已关闭才能删除',
    messageKey: 'error.opportunity.must.be.closed',
    category: 'opportunity',
    action: 'none',
  },
  30004: {
    name: 'SALES_OPPORTUNITY_ALREADY_NULL',
    code: 30004,
    message: '销售机会已经空',
    messageKey: 'error.sales.opportunity.already.null',
    category: 'opportunity',
    action: 'none',
  },
  30005: {
    name: 'SALES_STAGE_APPROVAL_NOT_EXISTS',
    code: 30005,
    message: '销售阶段审批ID不存在',
    messageKey: 'error.sales.stage.approval.not.exists',
    category: 'opportunity',
    action: 'none',
  },
  30006: {
    name: 'APPROVAL_FILE_EMPTY',
    code: 30006,
    message: '审批文件不能为空',
    messageKey: 'error.approval.file.empty',
    category: 'opportunity',
    action: 'none',
  },
  31000: {
    name: 'CONTRACT_ERROR',
    code: 31000,
    message: '合同订单错误',
    messageKey: 'error.contract.category',
    category: 'contract',
    action: 'none',
  },
  31001: {
    name: 'CONTRACT_NOT_EXISTS',
    code: 31001,
    message: '合同不存在',
    messageKey: 'error.contract.not.exists',
    category: 'contract',
    action: 'none',
  },
  31002: {
    name: 'ORDER_NOT_EXISTS',
    code: 31002,
    message: '订单不存在',
    messageKey: 'error.order.not.exists',
    category: 'contract',
    action: 'none',
  },
  31003: {
    name: 'CONTRACT_STATUS_ERROR',
    code: 31003,
    message: '合同状态错误',
    messageKey: 'error.contract.status.error',
    category: 'contract',
    action: 'none',
  },
  31004: {
    name: 'AMOUNT_FORMAT_ERROR',
    code: 31004,
    message: '金额格式错误',
    messageKey: 'error.amount.format.error',
    category: 'contract',
    action: 'none',
  },
  40000: {
    name: 'TASK_ERROR',
    code: 40000,
    message: '任务管理错误',
    messageKey: 'error.task.category',
    category: 'task',
    action: 'none',
  },
  40001: {
    name: 'TASK_NOT_EXISTS',
    code: 40001,
    message: '任务不存在',
    messageKey: 'error.task.not.exists',
    category: 'task',
    action: 'none',
  },
  40002: {
    name: 'TASK_OWNER_NOT_EXISTS',
    code: 40002,
    message: '任务负责人不存在',
    messageKey: 'error.task.owner.not.exists',
    category: 'task',
    action: 'none',
  },
  40003: {
    name: 'TASK_DEADLINE_INVALID',
    code: 40003,
    message: '任务截止日期不合法',
    messageKey: 'error.task.deadline.invalid',
    category: 'task',
    action: 'none',
  },
  50001: {
    name: 'PARAM_EMPTY',
    code: 50001,
    message: '参数为空',
    messageKey: 'error.param.empty',
    category: 'param',
    action: 'fix-input',
  },
  50002: {
    name: 'PARAM_FORMAT_ERROR',
    code: 50002,
    message: '参数格式错误',
    messageKey: 'error.param.format.error',
    category: 'param',
    action: 'fix-input',
  },
  50003: {
    name: 'FILE_FORMAT_ERROR',
    code: 50003,
    message: '文件格式错误',
    messageKey: 'error.file.format.error',
    category: 'param',
    action: 'fix-input',
  },
  50004: {
    name: 'EMAIL_FORMAT_ERROR',
    code: 50004,
    message: '邮箱格式错误',
    messageKey: 'error.email.format.error',
    category: 'param',
    action: 'fix-input',
  },
  50005: {
    name: 'PHONE_FORMAT_ERROR',
    code: 50005,
    message: '手机号格式错误',
    messageKey: 'error.phone.format.error',
    category: 'param',
    action: 'fix-input',
  },
  50006: {
    name: 'PARAM_LENGTH_EXCEEDED',
    code: 50006,
    message: '参数长度超限',
    messageKey: 'error.param.length.exceeded',
    category: 'param',
    action: 'fix-input',
  },
  50007: {
    name: 'PARAM_OUT_OF_RANGE',
    code: 50007,
    message: '参数超出范围',
    messageKey: 'error.param.out.of.range',
    category: 'param',
    action: 'fix-input',
  },
  50008: {
    name: 'PARAM_REQUIRED',
    code: 50008,
    message: '必填参数缺失',
    messageKey: 'error.param.required',
    category: 'param',
    action: 'fix-input',
  },
  90000: {
    name: 'SYSTEM_ERROR',
    code: 90000,
    message: '系统级错误',
    messageKey: 'error.system.category',
    category: 'system',
    action: 'none',
  },
  90001: {
    name: 'SYSTEM_BUSY',
    code: 90001,
    message: '系统繁忙，请稍后再试',
    messageKey: 'error.system.busy',
    category: 'system',
    action: 'retry',
  },
  90002: {
    name: 'DATABASE_ERROR',
    code: 90002,
    message: '数据库错误',
    messageKey: 'error.database.error',
    category: 'system',
    action: 'none',
  },
  90003: {
    name: 'NETWORK_ERROR',
    code: 90003,
    message: '网络错误',
    messageKey: 'error.network.error',
    category: 'system',
    action: 'retry',
  },
  90004: {
    name: 'INTERNAL_SERVER_ERROR',
    code: 90004,
    message: '服务器内部错误',
    messageKey: 'error.internal.server.error',
    category: 'system',
    action: 'none',
  },
  90005: {
    name: 'SERVICE_UNAVAILABLE',
    code: 90005,
    message: '服务不可用',
    messageKey: 'error.service.unavailable',
    category: 'system',
    action: 'retry',
  },
  90006: {
    name: 'RATE_LIMIT_EXCEEDED',
    code: 90006,
    message: '请求频率超限',
    messageKey: 'error.rate.limit.exceeded',
    category: 'system',
    action: 'retry',
  },
  90007: {
    name: 'THIRD_PARTY_SERVICE_ERROR',
    code: 90007,
    message: '第三方服务错误',
    messageKey: 'error.third.party.service.error',
    category: 'system',
    action: 'retry',
  },
  91001: {
    name: 'DATA_NULL',
    code: 91001,
    message: '数据为空',
    messageKey: 'error.data.null',
    category: 'data',
    action: 'none',
  },
  91002: {
    name: 'PAGE_DATA_NULL',
    code: 91002,
    message: '分页数据为空',
    messageKey: 'error.page.data.null',
    category: 'data',
    action: 'none',
  },
  91003: {
    name: 'DATA_DELETE_FAILED',
    code: 91003,
    message: '数据删除失败',
    messageKey: 'error.data.delete.failed',
    category: 'data',
    action: 'none',
  },
  92000: {
    name: 'BUSINESS_ERROR',
    code: 92000,
    message: '业务逻辑错误',
    messageKey: 'error.business.category',
    category: 'business',
    action: 'none',
  },
  92001: {
    name: 'UPDATE_FAILED',
    code: 92001,
    message: '更新失败',
    messageKey: 'error.update.failed',
    category: 'business',
    action: 'none',
  },
  92002: {
    name: 'FILE_CREATE_FAILED',
    code: 92002,
    message: '文件创建失败',
    messageKey: 'error.file.create.failed',
    category: 'business',
    action: 'none',
  },
  92003: {
    name: 'FILE_DELETE_FAILED',
    code: 92003,
    message: '文件删除失败',
    messageKey: 'error.file.delete.failed',
    category: 'business',
    action: 'none',
  },
  92004: {
    name: 'FILE_READ_FAILED',
    code: 92004,
    message: '文件读取失败',
    messageKey: 'error.file.read.failed',
    category: 'business',
    action: 'none',
  },
  92005: {
    name: 'EXCEL_FORMAT_ERROR',
    code: 92005,
    message: '第【%s】行格式错误或必要信息缺失',
    messageKey: 'error.excel.format.error',
    category: 'business',
    action: 'none',
  },
  92006: {
    name: 'EXCEL_COMPANY_NOT_EXISTS',
    code: 92006,
    message: '第【%s】行公司不存在',
    messageKey: 'error.excel.company.not.exists',
    category: 'business',
    action: 'none',
  },
  92007: {
    name: 'EXCEL_CONTACT_NOT_EXISTS',
    code: 92007,
    message: '第【%s】行联系人不存在',
    messageKey: 'error.excel.contact.not.exists',
    category: 'business',
    action: 'none',
  },
  92008: {
    name: 'ID_NOT_EXISTS',
    code: 92008,
    message: '【%s】不存在',
    messageKey: 'error.id.not.exists',
    category: 'business',
    action: 'none',
  },
  92009: {
    name: 'EMAIL_EXISTS',
    code: 92009,
    message: '邮箱已存在',
    messageKey: 'error.email.exists',
    category: 'business',
    action: 'none',
  },
  92010: {
    name: 'COMPANY_OR_CONTACT_NULL',
    code: 92010,
    message: '客户和联系人不能为空',
    messageKey: 'error.company.or.contact.null',
    category: 'business',
    action: 'none',
  },
  92011: {
    name: 'COMPANY_OR_CONTACT_NOT_MATCH',
    code: 92011,
    message: '客户和联系人不匹配',
    messageKey: 'error.company.or.contact.not.match',
    category: 'business',
    action: 'none',
  },
  92012: {
    name: 'APPROVAL_ATTACHMENT_NULL',
    code: 92012,
    message: '审批文件不能为空',
    messageKey: 'error.approval.attachment.null',
    category: 'business',
    action: 'none',
  },
  92013: {
    name: 'BUSINESS_ACTIVITY_NOT_EXISTS',
    code: 92013,
    message: '商业活动不存在',
    messageKey: 'error.business.activity.not.exists',
    category: 'business',
    action: 'none',
  },
  92014: {
    name: 'BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS',
    code: 92014,
    message: '商业活动联系人不存在',
    messageKey: 'error.business.activity.contact.not.exists',
    category: 'business',
    action: 'none',
  },
  92015: {
    name: 'BUSINESS_ACTIVITY_USER_NOT_EXISTS',
    code: 92015,
    message: '商业活动用户不存在',
    messageKey: 'error.business.activity.user.not.exists',
    category: 'business',
    action: 'none',
  },
  92016: {
    name: 'COMPANY_HAS_CONTACTS',
    code: 92016,
    message: '公司存在关联的联系人，无法彻底删除',
    messageKey: 'error.company.has.contacts',
    category: 'business',
    action: 'none',
  },
  92017: {
    name: 'CONTRACT_MUST_BE_ABANDONED',
    code: 92017,
    message: '合同必须处于废弃状态才能删除',
    messageKey: 'error.contract.must.be.abandoned',
    category: 'business',
    action: 'none',
  },
  92018: {
    name: 'PAYMENT_NOT_EXISTS',
    code: 92018,
    message: '付款记录不存在',
    messageKey: 'error.payment.not.exists',
    category: 'business',
    action: 'none',
  },
  92019: {
    name: 'PAYMENT_DATA_NULL',
    code: 92019,
    message: '付款数据不能为空',
    messageKey: 'error.payment.data.null',
    category: 'business',
    action: 'none',
  },
  92020: {
    name: 'TASK_ASSIGNEE_NOT_EXISTS',
    code: 92020,
    message: '任务负责人不存在',
    messageKey: 'error.task.assignee.not.exists',
    category: 'business',
    action: 'none',
  },
  92021: {
    name: 'TASK_DEADLINE_ILLEGAL',
    code: 92021,
    message: '任务截止日期不合法',
    messageKey: 'error.task.deadline.illegal',
    category: 'business',
    action: 'none',
  },
  92022: {
    name: 'TASK_STATUS_ERROR',
    code: 92022,
    message: '任务状态错误',
    messageKey: 'error.task.status.error',
    category: 'business',
    action: 'none',
  },
  92023: {
    name: 'TASK_PRIORITY_ERROR',
    code: 92023,
    message: '任务优先级错误',
    messageKey: 'error.task.priority.error',
    category: 'business',
    action: 'none',
  },
  92024: {
    name: 'PRIVACY_ERROR',
    code: 92024,
    message: '隐私数据错误',
    messageKey: 'error.privacy.error',
    category: 'business',
    action: 'none',
  },
  92025: {
    name: 'PASSWORD_OR_EMAIL_ERROR',
    code: 92025,
    message: '密码或邮箱错误',
    messageKey: 'error.password.or.email.error',
    category: 'business',
    action: 'none',
  },
  92026: {
    name: 'USER_NOT_EXIST_PERMISSION',
    code: 92026,
    message: '用户不存在权限',
    messageKey: 'error.user.not.exist.permission',
    category: 'business',
    action: 'none',
  },
  92027: {
    name: 'NOT_ENABLED',
    code: 92027,
    message: '功能未启用',
    messageKey: 'error.not.enabled',
    category: 'business',
    action: 'none',
  },
  93001: {
    name: 'AI_ACTION_TIMEOUT',
    code: 93001,
    message: '确认已超时，请重新发起',
    messageKey: 'error.ai.action.timeout',
    category: 'ai',
    action: 'none',
  },
  93002: {
    name: 'AI_ACTION_ALREADY_HANDLED',
    code: 93002,
    message: '操作已被处理',
    messageKey: 'error.ai.action.already.handled',
    category: 'ai',
    action: 'none',
  },
  93003: {
    name: 'AI_ACTION_EXECUTE_FAILED',
    code: 93003,
    message: '执行失败',
    messageKey: 'error.ai.action.execute.failed',
    category: 'ai',
    action: 'none',
  },
  93004: {
    name: 'AI_ACTION_PARAM_MISSING',
    code: 93004,
    message: '参数不完整，请先补齐参数',
    messageKey: 'error.ai.action.param.missing',
    category: 'ai',
    action: 'none',
  },
  96001: {
    name: 'RATE_LIMITED',
    code: 96001,
    message: '请求过于频繁，请稍后再试',
    category: 'platform',
    action: 'retry',
  },
  96002: {
    name: 'QUOTA_EXCEEDED',
    code: 96002,
    message: '配额已用尽，请联系管理员',
    category: 'platform',
    action: 'contact-admin',
  },
  96003: {
    name: 'UNAUTHORIZED',
    code: 96003,
    message: '请先登录',
    category: 'platform',
    action: 'relogin',
  },
  96004: {
    name: 'CONTENT_RISK',
    code: 96004,
    message: '内容存在风险，已阻止本次处理',
    category: 'platform',
    action: 'none',
  },
  96005: {
    name: 'FORBIDDEN',
    code: 96005,
    message: '无权执行该操作',
    category: 'platform',
    action: 'none',
  },
  96006: {
    name: 'TOKEN_BUDGET_EXCEEDED',
    code: 96006,
    message: '本次会话 Token 预算已用尽',
    category: 'platform',
    action: 'contact-admin',
  },
  96007: {
    name: 'VALIDATION',
    code: 96007,
    message: '请求参数不合法',
    category: 'platform',
    action: 'fix-input',
  },
  96008: {
    name: 'DEPENDENCY_UNAVAILABLE',
    code: 96008,
    message: '依赖服务暂不可用，请稍后再试',
    category: 'platform',
    action: 'retry',
  },
  96009: {
    name: 'RESUME_UNAVAILABLE',
    code: 96009,
    message: '会话输出已不在缓冲区，请查看已生成的回答',
    category: 'platform',
    action: 'none',
  },
  96010: {
    name: 'INTERNAL',
    code: 96010,
    message: '服务开小差了，请稍后再试',
    category: 'platform',
    action: 'none',
  },
};

const UNKNOWN_ERROR_META: ErrorCodeMeta = {
  name: 'UNKNOWN',
  code: UNKNOWN_ERROR_CODE,
  message: '系统繁忙，请稍后再试',
  category: 'system',
  action: 'retry',
};

/** 后端 JSON 里的 code 既可能是数值也可能是字符串，非法输入一律归为"无法识别" */
function toErrorCode(
  raw: number | string | null | undefined,
): number | undefined {
  if (typeof raw === 'number') {
    return Number.isInteger(raw) ? raw : undefined;
  }
  if (typeof raw === 'string') {
    const trimmed = raw.trim();
    return /^\d+$/.test(trimmed) ? Number(trimmed) : undefined;
  }
  return undefined;
}

export function getErrorCodeMeta(
  raw: number | string | null | undefined,
): ErrorCodeMeta | undefined {
  const code = toErrorCode(raw);
  if (code === undefined) {
    return undefined;
  }
  if (code === UNKNOWN_ERROR_CODE) {
    return UNKNOWN_ERROR_META;
  }
  return errorCodeMap[code as ApiErrorCodeValue];
}

export function isApiSuccess(raw: number | string | null | undefined): boolean {
  return toErrorCode(raw) === API_SUCCESS_CODE;
}

/** 身份失效：只有重新登录才可能恢复，用于跳转登录页 */
export function requiresRelogin(
  raw: number | string | null | undefined,
): boolean {
  return getErrorCodeMeta(raw)?.action === 'relogin';
}

/** 瞬时性故障：同样的请求稍后再发一次有意义 */
export function isRetryableErrorCode(
  raw: number | string | null | undefined,
): boolean {
  return getErrorCodeMeta(raw)?.action === 'retry';
}

/** 按出现顺序把 `%s` 换成实参，实参不够时保留占位符原文 */
export function formatErrorCodeMessage(
  raw: number | string | null | undefined,
  args: string[] = [],
): string {
  let next = 0;
  const message = getErrorCodeMeta(raw)?.message ?? UNKNOWN_ERROR_META.message;
  return message.replace(/%s/g, () =>
    next < args.length ? args[next++] : '%s',
  );
}

/**
 * 取展示给用户的错误文案：后端 msg 非空时以后端为准（它带业务上下文），
 * 否则回落到映射层文案，映射层也不认识时给系统级兜底。
 */
export function resolveErrorMessage(
  raw: number | string | null | undefined,
  serverMessage?: string | null,
): string {
  const fromServer = serverMessage?.trim();
  if (fromServer) {
    return fromServer;
  }
  return formatErrorCodeMessage(raw);
}
