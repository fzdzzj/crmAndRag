package com.slz.crm.integration.schema;

/**
 * 库侧单列元数据（audit-entity-table-drift 任务 2.1，来自 information_schema.columns）。
 *
 * @param dataType MySQL data_type 原文（如 bigint / varchar / datetime），不含长度与精度
 * @param nullable 是否可空（is_nullable = YES）
 */
public record DbColumn(String dataType, boolean nullable) {}
