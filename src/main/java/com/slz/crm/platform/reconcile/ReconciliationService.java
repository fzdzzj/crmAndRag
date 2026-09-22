package com.slz.crm.platform.reconcile;

import com.slz.crm.platform.audit.GovernanceAuditEvent;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.audit.GovernanceAuditResult;
import com.slz.crm.platform.mapper.PlatformReconcileItemMapper;
import com.slz.crm.platform.mapper.PlatformReconcileReportMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 跨存储对账服务。
 *
 * <p>服务不直接访问 MinIO/Qdrant，而是聚合 {@link ReconciliationSource} 实现； 这样 Lane B 只提供资源快照，Lane D
 * 保留差异判定、报告和审计语义。
 */
@Service
public class ReconciliationService {

  private static final String MISSING = "MISSING";
  private static final String ORPHAN = "ORPHAN";
  private static final String STALE = "STALE";

  private final PlatformReconcileReportMapper reportMapper;
  private final PlatformReconcileItemMapper itemMapper;
  private final GovernanceAuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;
  private final List<ReconciliationSource> sources;

  /**
   * 构造对账服务。
   *
   * @param reportMapper 报告 Mapper
   * @param itemMapper 差异 Mapper
   * @param auditRecorder 审计记录器
   * @param meterRegistry Micrometer 注册表
   * @param sources 所有对账数据源；可为空列表
   */
  public ReconciliationService(
      PlatformReconcileReportMapper reportMapper,
      PlatformReconcileItemMapper itemMapper,
      GovernanceAuditRecorder auditRecorder,
      MeterRegistry meterRegistry,
      List<ReconciliationSource> sources) {
    this.reportMapper = reportMapper;
    this.itemMapper = itemMapper;
    this.auditRecorder = auditRecorder;
    this.meterRegistry = meterRegistry;
    this.sources = sources == null ? List.of() : List.copyOf(sources);
  }

  /**
   * 执行一次对账。
   *
   * @param request 扫描请求
   * @return 报告和差异
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 对账边界：源比对+ORM+审计多源，失败标记FAILED后上抛
  public ReconcileScanResult scan(ReconcileScanRequest request) {
    Objects.requireNonNull(request, "ReconcileScanRequest 不能为空");
    LocalDateTime now = LocalDateTime.now();
    PlatformReconcileReportEntity report = new PlatformReconcileReportEntity();
    report.setReportId(UUID.randomUUID().toString().replace("-", ""));
    report.setScanType(request.scanType());
    report.setDryRun(request.dryRun());
    report.setStatus("RUNNING");
    report.setTotalDifferences(0);
    report.setStartedTime(now);
    report.setRetainedUntil(now.plusHours(Math.max(1, request.retentionHours())));
    report.setOperatorUserRef(request.operatorUserRef());
    report.setCreateTime(now);
    report.setUpdateTime(now);
    reportMapper.insert(report);

    try {
      List<PlatformReconcileItemEntity> items = compareSources(report);
      report.setStatus("COMPLETED");
      report.setTotalDifferences(items.size());
      report.setCompletedTime(LocalDateTime.now());
      report.setUpdateTime(report.getCompletedTime());
      reportMapper.updateById(report);
      meterRegistry
          .counter(
              "platform.reconcile.scan",
              "scanType",
              request.scanType(),
              "dryRun",
              String.valueOf(request.dryRun()))
          .increment();
      meterRegistry
          .counter("platform.reconcile.differences", "scanType", request.scanType())
          .increment(items.size());
      auditRecorder.record(
          new GovernanceAuditEvent(
              "RECONCILE_TRIGGERED",
              request.operatorUserRef(),
              "REPORT",
              report.getReportId(),
              request.dryRun() ? "DRY_RUN_SCAN" : "SCAN",
              GovernanceAuditResult.SUCCESS,
              "{\"differences\":" + items.size() + "}"));
      return new ReconcileScanResult(report, items);
    } catch (Exception exception) {
      report.setStatus("FAILED");
      report.setCompletedTime(LocalDateTime.now());
      report.setUpdateTime(report.getCompletedTime());
      reportMapper.updateById(report);
      meterRegistry
          .counter("platform.reconcile.failed", "scanType", request.scanType())
          .increment();
      throw exception;
    }
  }

  private List<PlatformReconcileItemEntity> compareSources(PlatformReconcileReportEntity report) {
    Map<String, Map<String, ReconciliationResource>> byStorage = new LinkedHashMap<>();
    for (ReconciliationSource source : sources) {
      Map<String, ReconciliationResource> resources = new LinkedHashMap<>();
      for (ReconciliationResource resource : source.listResources()) {
        resources.put(resource.resourceId(), resource);
      }
      byStorage.put(source.storageType(), resources);
    }

    ReconciliationSource authoritative =
        sources.stream().filter(ReconciliationSource::authoritative).findFirst().orElse(null);
    Set<String> allResourceIds = new LinkedHashSet<>();
    byStorage.values().forEach(resources -> allResourceIds.addAll(resources.keySet()));

    List<PlatformReconcileItemEntity> items = new ArrayList<>();
    for (String resourceId : allResourceIds) {
      ReconciliationResource authoritativeResource =
          authoritative == null ? null : byStorage.get(authoritative.storageType()).get(resourceId);
      for (ReconciliationSource source : sources) {
        ReconciliationResource current = byStorage.get(source.storageType()).get(resourceId);
        if (current == null) {
          if (authoritative != null
              && authoritativeResource != null
              && !source.storageType().equals(authoritative.storageType())) {
            items.add(
                item(report, source.storageType(), resourceId, MISSING, "主数据存在但当前存储缺失", "REPAIR"));
          }
          continue;
        }
        if (authoritative != null
            && authoritativeResource == null
            && !source.storageType().equals(authoritative.storageType())) {
          items.add(
              item(report, source.storageType(), resourceId, ORPHAN, "主数据缺失但当前存储存在", "CLEANUP"));
          continue;
        }
        if (authoritativeResource != null
            && !source.storageType().equals(authoritative.storageType())
            && !Objects.equals(current.fingerprint(), authoritativeResource.fingerprint())) {
          items.add(
              item(report, source.storageType(), resourceId, STALE, "资源指纹与主数据不一致", "REFRESH"));
        }
      }
    }
    return items;
  }

  private PlatformReconcileItemEntity item(
      PlatformReconcileReportEntity report,
      String storageType,
      String resourceId,
      String diffType,
      String detail,
      String action) {
    PlatformReconcileItemEntity entity = new PlatformReconcileItemEntity();
    entity.setReportId(report.getReportId());
    entity.setStorageType(storageType);
    entity.setResourceId(resourceId);
    entity.setDiffType(diffType);
    entity.setDetail(detail);
    entity.setAction(report.isDryRun() ? action + "_DRY_RUN" : action);
    entity.setResolved(false);
    entity.setCreateTime(LocalDateTime.now());
    entity.setUpdateTime(entity.getCreateTime());
    itemMapper.insert(entity);
    return entity;
  }
}
