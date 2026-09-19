package com.slz.crm.platform.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.mapper.PlatformReconcileItemMapper;
import com.slz.crm.platform.mapper.PlatformReconcileReportMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 跨存储对账差异判定测试。 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

  @Mock private PlatformReconcileReportMapper reportMapper;

  @Mock private PlatformReconcileItemMapper itemMapper;

  @Mock private GovernanceAuditRecorder auditRecorder;

  private ReconciliationService service;

  @BeforeEach
  void setUp() {
    service =
        new ReconciliationService(
            reportMapper,
            itemMapper,
            auditRecorder,
            new SimpleMeterRegistry(),
            List.of(
                new ReconciliationSource() {
                  @Override
                  public String storageType() {
                    return "MYSQL";
                  }

                  @Override
                  public boolean authoritative() {
                    return true;
                  }

                  @Override
                  public List<ReconciliationResource> listResources() {
                    return List.of(
                        new ReconciliationResource("doc-1", "v1"),
                        new ReconciliationResource("doc-2", "v1"));
                  }
                },
                new ReconciliationSource() {
                  @Override
                  public String storageType() {
                    return "QDRANT";
                  }

                  @Override
                  public boolean authoritative() {
                    return false;
                  }

                  @Override
                  public List<ReconciliationResource> listResources() {
                    return List.of(
                        new ReconciliationResource("doc-1", "v1"),
                        new ReconciliationResource("doc-3", "v1"));
                  }
                },
                new ReconciliationSource() {
                  @Override
                  public String storageType() {
                    return "MINIO";
                  }

                  @Override
                  public boolean authoritative() {
                    return false;
                  }

                  @Override
                  public List<ReconciliationResource> listResources() {
                    return List.of(new ReconciliationResource("doc-1", "v2"));
                  }
                }));
  }

  @Test
  void shouldIdentifyMissingOrphanAndStaleResources() {
    when(reportMapper.insert(any(PlatformReconcileReportEntity.class))).thenReturn(1);
    when(reportMapper.updateById(any(PlatformReconcileReportEntity.class))).thenReturn(1);
    when(itemMapper.insert(any(PlatformReconcileItemEntity.class))).thenReturn(1);
    ReconcileScanRequest request = new ReconcileScanRequest("FULL", true, "user:1", 24);

    ReconcileScanResult result = service.scan(request);

    assertThat(result.report().getStatus()).isEqualTo("COMPLETED");
    assertThat(result.report().getTotalDifferences()).isEqualTo(4);
    assertThat(result.report().isDryRun()).isTrue();
    assertThat(result.items())
        .extracting(PlatformReconcileItemEntity::getDiffType)
        .containsExactlyInAnyOrder("MISSING", "MISSING", "ORPHAN", "STALE");
  }
}
