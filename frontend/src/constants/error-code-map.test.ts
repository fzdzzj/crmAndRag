import { describe, expect, it } from 'vitest';
import {
  API_SUCCESS_CODE,
  ApiErrorCode,
  GENERIC_ERROR_CODE,
  UNKNOWN_ERROR_CODE,
  errorCodeMap,
  formatErrorCodeMessage,
  getErrorCodeMeta,
  isApiSuccess,
  isRetryableErrorCode,
  requiresRelogin,
  resolveErrorMessage,
} from './error-code-map.ts';

// 后端 src/main/java/com/slz/crm/common/enumeration/ErrorCode.java 与
// platform/contract/PlatformErrorCode.java 的枚举全集；两处清单由
// src/test/java/com/slz/crm/contract/ErrorCodeMapParityTest.java 与 Java 反射对齐，
// 本文件只锁住前端映射层的行为契约。
const BACKEND_ERROR_CODE_NAMES = 97;

describe('ApiErrorCode 常量表', () => {
  it('枚举名到码值的映射覆盖后端两个枚举的全部条目', () => {
    expect(Object.keys(ApiErrorCode)).toHaveLength(BACKEND_ERROR_CODE_NAMES);
  });

  it('所有码值都是 5 位正整数', () => {
    for (const code of Object.values(ApiErrorCode)) {
      expect(code).toBeGreaterThan(9999);
      expect(code).toBeLessThan(100000);
      expect(Number.isInteger(code)).toBe(true);
    }
  });

  it('所有码值互不重复', () => {
    const codes = Object.values(ApiErrorCode);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it('业务域码值与后端 ErrorCode 一致', () => {
    expect(ApiErrorCode.AUTH_ERROR).toBe(10000);
    expect(ApiErrorCode.TOKEN_EXPIRED).toBe(10002);
    expect(ApiErrorCode.PERMISSION_DENIED).toBe(12002);
    expect(ApiErrorCode.USER_IS_QUIT).toBe(12007);
    expect(ApiErrorCode.COMPANY_NOT_EXISTS).toBe(20001);
    expect(ApiErrorCode.OPPORTUNITY_NOT_EXISTS).toBe(30001);
    expect(ApiErrorCode.CONTRACT_NOT_EXISTS).toBe(31001);
    expect(ApiErrorCode.TASK_NOT_EXISTS).toBe(40001);
    expect(ApiErrorCode.PARAM_EMPTY).toBe(50001);
    expect(ApiErrorCode.SYSTEM_BUSY).toBe(90001);
    expect(ApiErrorCode.PASSWORD_OR_EMAIL_ERROR).toBe(92025);
    expect(ApiErrorCode.AI_ACTION_PARAM_MISSING).toBe(93004);
  });

  it('平台域码值与后端 PlatformErrorCode 一致', () => {
    expect(ApiErrorCode.RATE_LIMITED).toBe(96001);
    expect(ApiErrorCode.QUOTA_EXCEEDED).toBe(96002);
    expect(ApiErrorCode.UNAUTHORIZED).toBe(96003);
    expect(ApiErrorCode.RESUME_UNAVAILABLE).toBe(96009);
    expect(ApiErrorCode.INTERNAL).toBe(96010);
  });

  it('成功码与通用失败码遵循后端 Result 约定', () => {
    expect(API_SUCCESS_CODE).toBe(1);
    expect(GENERIC_ERROR_CODE).toBe(0);
  });
});

describe('errorCodeMap 完整性', () => {
  it('为每个 ApiErrorCode 码值都提供了元数据', () => {
    for (const code of Object.values(ApiErrorCode)) {
      expect(errorCodeMap[code]).toBeDefined();
    }
  });

  it('不夹带 ApiErrorCode 之外的码值', () => {
    const known = new Set<number>(Object.values(ApiErrorCode));
    for (const key of Object.keys(errorCodeMap)) {
      expect(known.has(Number(key))).toBe(true);
    }
  });

  it('元数据里的 code 与 name 与自身键位一致', () => {
    for (const [code, meta] of Object.entries(errorCodeMap)) {
      expect(meta.code).toBe(Number(code));
      expect(ApiErrorCode[meta.name]).toBe(Number(code));
    }
  });

  it('每条元数据都有可直接展示给用户的中文提示', () => {
    for (const meta of Object.values(errorCodeMap)) {
      expect(meta.message.trim().length).toBeGreaterThan(0);
    }
  });
});

describe('错误码分类', () => {
  const cases: Array<[string, string]> = [
    [String(ApiErrorCode.AUTH_ERROR), 'auth'],
    [String(ApiErrorCode.PERMISSION_DENIED), 'permission'],
    [String(ApiErrorCode.ROLE_NOT_EXISTS), 'user'],
    [String(ApiErrorCode.COMPANY_NOT_EXISTS), 'company'],
    [String(ApiErrorCode.CONTACT_NOT_EXISTS), 'contact'],
    [String(ApiErrorCode.OPPORTUNITY_NOT_EXISTS), 'opportunity'],
    [String(ApiErrorCode.CONTRACT_NOT_EXISTS), 'contract'],
    [String(ApiErrorCode.TASK_NOT_EXISTS), 'task'],
    [String(ApiErrorCode.PARAM_EMPTY), 'param'],
    [String(ApiErrorCode.SYSTEM_BUSY), 'system'],
    [String(ApiErrorCode.DATA_NULL), 'data'],
    [String(ApiErrorCode.UPDATE_FAILED), 'business'],
    [String(ApiErrorCode.AI_ACTION_TIMEOUT), 'ai'],
    [String(ApiErrorCode.QUOTA_EXCEEDED), 'platform'],
  ];

  it.each(cases)('码 %s 归类为 %s', (code, category) => {
    expect(getErrorCodeMeta(Number(code))?.category).toBe(category);
  });

  it('分类只使用约定字面量', () => {
    const allowed = new Set([
      'auth',
      'permission',
      'user',
      'company',
      'contact',
      'opportunity',
      'contract',
      'task',
      'param',
      'system',
      'data',
      'business',
      'ai',
      'platform',
    ]);
    for (const meta of Object.values(errorCodeMap)) {
      expect(allowed.has(meta.category)).toBe(true);
    }
  });

  it('CRM 域保留后端 i18n messageKey，平台域没有', () => {
    expect(getErrorCodeMeta(ApiErrorCode.PERMISSION_DENIED)?.messageKey).toBe(
      'error.permission.denied',
    );
    expect(
      getErrorCodeMeta(ApiErrorCode.RATE_LIMITED)?.messageKey,
    ).toBeUndefined();
  });
});

describe('getErrorCodeMeta', () => {
  it('按数值码命中', () => {
    expect(getErrorCodeMeta(12002)?.name).toBe('PERMISSION_DENIED');
  });

  it('按后端 JSON 里的字符串码命中', () => {
    expect(getErrorCodeMeta('12002')?.name).toBe('PERMISSION_DENIED');
  });

  it('未知码返回 undefined', () => {
    expect(getErrorCodeMeta(999999)).toBeUndefined();
  });

  it('非码值输入返回 undefined 而不抛异常', () => {
    expect(getErrorCodeMeta(undefined as unknown as number)).toBeUndefined();
    expect(getErrorCodeMeta(null as unknown as number)).toBeUndefined();
    expect(getErrorCodeMeta('')).toBeUndefined();
    expect(getErrorCodeMeta('abc')).toBeUndefined();
    expect(getErrorCodeMeta(Number.NaN)).toBeUndefined();
  });
});

describe('resolveErrorMessage', () => {
  it('后端已给出可读文案时以后端为准', () => {
    expect(resolveErrorMessage(12002, '不能删除销售角色')).toBe(
      '不能删除销售角色',
    );
  });

  it('后端文案为空时回落到映射层的中文提示', () => {
    expect(resolveErrorMessage(12002, '')).toBe('权限不足');
    expect(resolveErrorMessage(12002, '   ')).toBe('权限不足');
    expect(resolveErrorMessage(12002, undefined)).toBe('权限不足');
  });

  it('未知码回落到系统级兜底文案', () => {
    expect(resolveErrorMessage(77777, '')).toBe('系统繁忙，请稍后再试');
  });

  it('带占位符的后端文案原样透出，不做二次替换', () => {
    expect(
      resolveErrorMessage(ApiErrorCode.ID_NOT_EXISTS, '【合同】不存在'),
    ).toBe('【合同】不存在');
  });

  it('带占位符且后端未填文案时用映射模板', () => {
    expect(resolveErrorMessage(ApiErrorCode.ID_NOT_EXISTS)).toBe(
      '【%s】不存在',
    );
  });

  it('接受字符串码', () => {
    expect(resolveErrorMessage('10002', '')).toBe('Token已过期');
  });
});

describe('formatErrorCodeMessage', () => {
  it('按顺序填充 %s 占位符', () => {
    expect(
      formatErrorCodeMessage(ApiErrorCode.EXCEL_FORMAT_ERROR, ['12']),
    ).toBe('第【12】行格式错误或必要信息缺失');
  });

  it('多占位符按出现顺序消耗参数', () => {
    expect(formatErrorCodeMessage(ApiErrorCode.ID_NOT_EXISTS, ['联系人'])).toBe(
      '【联系人】不存在',
    );
  });

  it('参数不足时保留未填充的占位符', () => {
    expect(formatErrorCodeMessage(ApiErrorCode.EXCEL_FORMAT_ERROR, [])).toBe(
      '第【%s】行格式错误或必要信息缺失',
    );
  });

  it('无占位符的文案不受参数影响', () => {
    expect(formatErrorCodeMessage(12002, ['x'])).toBe('权限不足');
  });

  it('未知码返回兜底文案', () => {
    expect(formatErrorCodeMessage(88888, ['x'])).toBe('系统繁忙，请稍后再试');
  });
});

describe('isApiSuccess', () => {
  it('只有成功码 1 判为成功', () => {
    expect(isApiSuccess(API_SUCCESS_CODE)).toBe(true);
    expect(isApiSuccess(GENERIC_ERROR_CODE)).toBe(false);
    expect(isApiSuccess(200)).toBe(false);
  });

  it('字符串形式的成功码同样判为成功', () => {
    expect(isApiSuccess('1')).toBe(true);
    expect(isApiSuccess('12002')).toBe(false);
  });

  it('缺失或非数字输入判为失败', () => {
    expect(isApiSuccess(undefined as unknown as number)).toBe(false);
    expect(isApiSuccess('ok')).toBe(false);
  });
});

describe('requiresRelogin', () => {
  const reloginCases: number[] = [
    ApiErrorCode.AUTH_ERROR,
    ApiErrorCode.TOKEN_ERROR,
    ApiErrorCode.TOKEN_EXPIRED,
    ApiErrorCode.TOKEN_INVALID,
    ApiErrorCode.TOKEN_PARSE_FAILED,
    ApiErrorCode.USER_NOT_LOGIN,
    ApiErrorCode.UNAUTHORIZED,
  ];

  it.each(reloginCases)('身份失效类错误 %i 需要重新登录', (code) => {
    expect(requiresRelogin(code)).toBe(true);
  });

  const notReloginCases: number[] = [
    ApiErrorCode.PERMISSION_DENIED,
    ApiErrorCode.FORBIDDEN,
    ApiErrorCode.USER_IS_LOCK,
    ApiErrorCode.PARAM_EMPTY,
    ApiErrorCode.SYSTEM_BUSY,
  ];

  it.each(notReloginCases)('错误 %i 不是重新登录能解决的', (code) => {
    expect(requiresRelogin(code)).toBe(false);
  });

  it('锁定/禁用类账号异常要求联系管理员而非重新登录', () => {
    expect(getErrorCodeMeta(ApiErrorCode.USER_IS_LOCK)?.action).toBe(
      'contact-admin',
    );
    expect(getErrorCodeMeta(ApiErrorCode.PERMISSION_DENIED)?.action).toBe(
      'none',
    );
  });
});

describe('isRetryableErrorCode', () => {
  const retryableCases: number[] = [
    ApiErrorCode.SYSTEM_BUSY,
    ApiErrorCode.NETWORK_ERROR,
    ApiErrorCode.SERVICE_UNAVAILABLE,
    ApiErrorCode.THIRD_PARTY_SERVICE_ERROR,
    ApiErrorCode.RATE_LIMITED,
    ApiErrorCode.DEPENDENCY_UNAVAILABLE,
  ];

  it.each(retryableCases)('瞬时性错误 %i 可以重试', (code) => {
    expect(isRetryableErrorCode(code)).toBe(true);
    expect(getErrorCodeMeta(code)?.action).toBe('retry');
  });

  const fatalCases: number[] = [
    ApiErrorCode.COMPANY_NOT_EXISTS,
    ApiErrorCode.EMAIL_EXISTS,
    ApiErrorCode.PARAM_REQUIRED,
    ApiErrorCode.QUOTA_EXCEEDED,
    ApiErrorCode.CONTENT_RISK,
  ];

  it.each(fatalCases)('确定性错误 %i 重试无意义', (code) => {
    expect(isRetryableErrorCode(code)).toBe(false);
  });

  it('未知码不参与重试判定', () => {
    expect(isRetryableErrorCode(654321)).toBe(false);
  });

  it('兜底错误码本身可重试', () => {
    expect(isRetryableErrorCode(UNKNOWN_ERROR_CODE)).toBe(true);
  });
});
