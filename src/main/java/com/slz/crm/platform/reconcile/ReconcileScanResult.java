package com.slz.crm.platform.reconcile;

import java.util.List;

/**
 * 对账扫描结果。
 *
 * @param report 对账报告
 * @param items 差异明细
 */
public record ReconcileScanResult(PlatformReconcileReportEntity report,
                                  List<PlatformReconcileItemEntity> items) {
}
