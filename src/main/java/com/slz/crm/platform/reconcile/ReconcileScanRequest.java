package com.slz.crm.platform.reconcile;

/**
 * 对账扫描请求。
 *
 * @param scanType 扫描类型 MINIO/VECTOR/SNAPSHOT/FULL
 * @param dryRun true 只识别差异，不执行清理
 * @param operatorUserRef 操作人
 * @param retentionHours 差异报告保留小时数
 */
public record ReconcileScanRequest(
    String scanType, boolean dryRun, String operatorUserRef, long retentionHours) {}
