package com.slz.crm.integration.schema;

/**
 * schema 漂移分级（audit-entity-table-drift 任务 2.2）。
 *
 * <ul>
 *   <li>{@link #CRITICAL}：实体映射的表/列在真库缺失——运行期必炸类（V24 同类 90004），门禁 IT 非空即 fail。
 *   <li>{@link #WARN}：粗粒度类型不亲和（如实体 Long ↔ 表 varchar），只记录不处理。
 *   <li>{@link #INFO}：表列实体未映射（冗余列）、有表无实体（flyway_schema_history 豁免），只记录不处理。
 * </ul>
 */
public enum Severity {
  CRITICAL,
  WARN,
  INFO
}
