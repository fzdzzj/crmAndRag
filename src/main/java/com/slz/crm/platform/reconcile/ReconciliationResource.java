package com.slz.crm.platform.reconcile;

/**
 * 对账资源快照。
 *
 * @param resourceId 跨存储统一的业务资源 ID，例如文档 ID
 * @param fingerprint 内容/向量指纹；为空表示该存储只记录资源存在性
 */
public record ReconciliationResource(String resourceId, String fingerprint) {
}
