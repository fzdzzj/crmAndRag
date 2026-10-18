package com.slz.crm.server.ai;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import java.util.List;

/**
 * AI 查询工具横切协作类（拆自 {@link AiQueryToolExecutors} 以守 PMD NcssCount）：登录身份守卫、入参
 * 解析、审批状态兼容归一与分页结果裁剪。纯静态无状态。
 */
final class AiQueryToolSupport {

  private AiQueryToolSupport() {}

  /**
   * AI 工具上下文守卫（harden-consistency-datascope-and-gate 模块 B）：详情类与结构化查询类工具 执行前必须存在有效登录身份；身份缺失时
   * fail-closed 拦截（12002），杜绝无主数据查询。
   */
  static void requireLoggedInRole() {
    RoleAO role = BaseUnit.getCurrentRole();
    if (role == null || role.getId() == null) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
  }

  /** approvalStatus 兼容数组与单值两种入参形态；其余形态视为未提供（返回 null）。 */
  static List<Integer> approvalStatusList(Object statusObj) {
    List<Integer> result = null;
    if (statusObj instanceof List<?> list) {
      result = list.stream().map(v -> ((Number) v).intValue()).toList();
    } else if (statusObj instanceof Number num) {
      result = List.of(num.intValue());
    }
    return result;
  }

  static <T> List<T> trimPageResult(List<T> records) {
    List<T> result = records;
    if (records != null && records.size() > 10) {
      result = records.subList(0, 10);
    }
    return result;
  }

  static Integer toInt(Object value, int defaultValue) {
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  static Long toLong(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }
}
