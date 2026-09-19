package com.slz.crm.platform.reconcile;

import java.util.List;

/**
 * 对账数据源。
 *
 * <p>MySQL、MinIO、Qdrant、快照各提供一个实现；MySQL/文档主数据建议 authoritative=true。
 */
public interface ReconciliationSource {

  /**
   * @return 存储类型 MYSQL/MINIO/QDRANT/SNAPSHOT
   */
  String storageType();

  /**
   * @return true 表示资源归属主数据源
   */
  boolean authoritative();

  /**
   * @return 当前存储中的资源快照
   */
  List<ReconciliationResource> listResources();
}
