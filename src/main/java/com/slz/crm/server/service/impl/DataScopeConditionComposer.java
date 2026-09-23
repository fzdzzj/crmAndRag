package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 数据范围 SQL 条件拼装协作类（tighten-pmd-residual-325 任务 6.4 批C：拆自 {@link DataScopeServiceImpl}，行为等价）。
 *
 * <p>纯静态、零依赖：只把“归属字段 / 标签资源 / 显式共享”三个 OR 集合拼进 wrapper， 所有数据查询（下属、标签、共享）仍由主服务完成后以参数传入。两个 Wrapper 重载
 * （字符串列名 / Lambda）保持同一语义：部门归属 OR 标签资源 OR 显式共享，保证 DEPT 权限不收窄原有 TAGE/data_share 可见范围。
 */
final class DataScopeConditionComposer {

  private DataScopeConditionComposer() {}

  /** 一级权限（SELF）：本人字段 OR 共享资源（字符串列名版）。 */
  static void appendSelfCondition(
      QueryWrapper<?> wrapper, List<String> userFields, Long userId, List<Long> sharedResourceIds) {
    wrapper.and(
        w -> {
          // 添加用户字段条件(查看自己的)
          if (userFields.isEmpty()) {
            w.eq("creator_id", userId);
          } else {
            for (int i = 0; i < userFields.size(); i++) {
              if (i == 0) {
                w.eq(userFields.get(i), userId);
              } else {
                w.or().eq(userFields.get(i), userId);
              }
            }
          }

          // 添加共享资源条件
          if (!sharedResourceIds.isEmpty()) {
            w.or().in("id", sharedResourceIds);
          }
        });
  }

  /** 一级权限（SELF）：本人字段 OR 共享资源（Lambda 版）。 */
  static void appendSelfCondition(
      LambdaQueryWrapper<?> wrapper,
      List<String> userFields,
      Long userId,
      List<Long> sharedResourceIds) {
    wrapper.and(
        w -> {
          boolean hasCondition = false;

          if (userFields.isEmpty()) {
            appendEqCondition(w, "creator_id", userId, hasCondition);
            hasCondition = true;
          } else {
            for (String userField : userFields) {
              appendEqCondition(w, userField, userId, hasCondition);
              hasCondition = true;
            }
          }

          if (!sharedResourceIds.isEmpty()) {
            appendInCondition(w, "id", sharedResourceIds, hasCondition);
          }
        });
  }

  /** 二级权限（TAGE）：标签资源 OR 本人字段 OR 共享资源（字符串列名版）。 */
  static void appendTageCondition(
      QueryWrapper<?> wrapper,
      List<String> userFields,
      Long userId,
      List<Long> tageResourceIds,
      List<Long> sharedResourceIds) {
    wrapper.and(
        w -> {
          // 添加标签资源条件
          if (!tageResourceIds.isEmpty()) {
            w.in("id", tageResourceIds);
          }

          // 添加用户字段条件(查看自己的)
          if (userFields.isEmpty()) {
            w.or().eq("creator_id", userId);
          } else {
            for (String userField : userFields) {
              w.or().eq(userField, userId);
            }
          }

          // 添加共享资源条件
          if (!sharedResourceIds.isEmpty()) {
            w.or().in("id", sharedResourceIds);
          }
        });
  }

  /** 二级权限（TAGE）：标签资源 OR 本人字段 OR 共享资源（Lambda 版）。 */
  static void appendTageCondition(
      LambdaQueryWrapper<?> wrapper,
      List<String> userFields,
      Long userId,
      List<Long> tageResourceIds,
      List<Long> sharedResourceIds) {
    wrapper.and(
        w -> {
          boolean hasCondition = false;

          if (!tageResourceIds.isEmpty()) {
            appendInCondition(w, "id", tageResourceIds, hasCondition);
            hasCondition = true;
          }

          if (userFields.isEmpty()) {
            appendEqCondition(w, "creator_id", userId, hasCondition);
            hasCondition = true;
          } else {
            for (String userField : userFields) {
              appendEqCondition(w, userField, userId, hasCondition);
              hasCondition = true;
            }
          }

          if (!sharedResourceIds.isEmpty()) {
            appendInCondition(w, "id", sharedResourceIds, hasCondition);
          }
        });
  }

  /** 部门范围（DEPT / DEPT_AND_CHILD）：下属用户 OR 标签 OR 共享；空集合时显式 1=0（字符串列名版）。 */
  static void appendDeptCondition(
      QueryWrapper<?> wrapper,
      List<String> userFields,
      List<Long> subordinateUserIds,
      List<Long> tageResourceIds,
      List<Long> sharedResourceIds) {
    wrapper.and(
        w -> {
          boolean hasCondition = false;
          for (String userField : userFields) {
            if (hasCondition) {
              w.or();
            }
            if (subordinateUserIds.isEmpty()) {
              w.apply(userField + " = {0}", -1L);
            } else {
              w.in(userField, subordinateUserIds);
            }
            hasCondition = true;
          }

          if (!tageResourceIds.isEmpty()) {
            if (hasCondition) {
              w.or();
            }
            w.in("id", tageResourceIds);
            hasCondition = true;
          }
          if (!sharedResourceIds.isEmpty()) {
            if (hasCondition) {
              w.or();
            }
            w.in("id", sharedResourceIds);
            hasCondition = true;
          }
          if (!hasCondition) {
            w.apply("1 = 0");
          }
        });
  }

  /** 部门范围（DEPT / DEPT_AND_CHILD）：下属用户 OR 标签 OR 共享；空集合时显式 1=0（Lambda 版）。 */
  static void appendDeptCondition(
      LambdaQueryWrapper<?> wrapper,
      List<String> userFields,
      List<Long> subordinateUserIds,
      List<Long> tageResourceIds,
      List<Long> sharedResourceIds) {
    wrapper.and(
        w -> {
          boolean hasCondition = false;
          for (String userField : userFields) {
            if (subordinateUserIds.isEmpty()) {
              appendEqCondition(w, userField, -1L, hasCondition);
            } else {
              appendInCondition(w, userField, subordinateUserIds, hasCondition);
            }
            hasCondition = true;
          }

          if (!tageResourceIds.isEmpty()) {
            appendInCondition(w, "id", tageResourceIds, hasCondition);
            hasCondition = true;
          }
          if (!sharedResourceIds.isEmpty()) {
            appendInCondition(w, "id", sharedResourceIds, hasCondition);
            hasCondition = true;
          }
          // 空部门且无附加授权时显式生成不可满足条件，避免空 AND 导致 SQL 异常或全表放行。
          if (!hasCondition) {
            w.apply("1 = 0");
          }
        });
  }

  private static void appendEqCondition(
      LambdaQueryWrapper<?> wrapper, String columnName, Long value, boolean appendOr) {
    if (appendOr) {
      wrapper.or();
    }
    wrapper.apply(columnName + " = {0}", value);
  }

  private static void appendInCondition(
      LambdaQueryWrapper<?> wrapper, String columnName, List<Long> values, boolean appendOr) {
    if (values == null || values.isEmpty()) {
      return;
    }
    if (appendOr) {
      wrapper.or();
    }
    wrapper.apply(columnName + " IN (" + joinIds(values) + ")");
  }

  private static String joinIds(List<Long> ids) {
    return ids.stream()
        .filter(Objects::nonNull)
        .map(String::valueOf)
        .collect(Collectors.joining(","));
  }
}
