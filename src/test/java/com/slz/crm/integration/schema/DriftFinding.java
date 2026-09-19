package com.slz.crm.integration.schema;

/**
 * 单条 schema 漂移 finding（audit-entity-table-drift 任务 2.2，比对器纯函数输出）。
 *
 * @param severity 分级（CRITICAL / WARN / INFO）
 * @param table 目标表名（库侧不存在时指实体映射的表名）
 * @param column 目标列名（表级 finding 时为 {@code null}）
 * @param message 人类可读说明，含实体类/字段与库侧类型等证据
 */
public record DriftFinding(Severity severity, String table, String column, String message) {

  public static DriftFinding critical(String table, String column, String message) {
    return new DriftFinding(Severity.CRITICAL, table, column, message);
  }

  public static DriftFinding warn(String table, String column, String message) {
    return new DriftFinding(Severity.WARN, table, column, message);
  }

  public static DriftFinding info(String table, String column, String message) {
    return new DriftFinding(Severity.INFO, table, column, message);
  }
}
