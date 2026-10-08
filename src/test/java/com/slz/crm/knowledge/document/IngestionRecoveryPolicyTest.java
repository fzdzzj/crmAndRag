package com.slz.crm.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.resilience.DependencyFailureType;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 摄取恢复策略单元测试（wire-ingestion-recovery-replay 任务 2.2）。
 *
 * <p>测试恢复策略实现：
 *
 * <ul>
 *   <li>可恢复失败（熔断开闸）标 PENDING，清理向量与切片
 *   <li>不可恢复失败（业务异常）标 FAILED，清理向量与切片
 *   <li>深扫防环：循环 cause 链不陷入死循环
 *   <li>非熔断异常不产生误分类
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IngestionRecoveryPolicyTest {

  @Mock private CrmVectorStore vectorStore;
  @Mock private DocumentVectorChunkMapper chunkMapper;
  @Mock private UploadedFileMapper uploadedFileMapper;

  private IngestionRecoveryPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new IngestionRecoveryPolicy(vectorStore, chunkMapper, uploadedFileMapper);
  }

  @Test
  @DisplayName("可恢复失败应标记 PENDING 并执行清理")
  void onRecoverableFailureSetsPendingAndCleans() {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setId(10L);
    file.setDocumentId("doc-10");
    file.setStatus("PROCESSING");

    DependencyUnavailableException error =
        DependencyUnavailableException.circuitOpen("embedding-service");

    UploadedFileEntity result = policy.onRecoverableFailure(file, error);

    assertThat(result.getStatus()).isEqualTo("PENDING");
    assertThat(result.getErrorMessage()).contains("依赖熔断中");
    verify(vectorStore).deleteByDocumentId("doc-10");
    verify(chunkMapper).deletePhysicallyByDocumentId("doc-10");
    verify(uploadedFileMapper).updateById(file);
  }

  @Test
  @DisplayName("不可恢复失败应标记 FAILED 并执行清理")
  void onPermanentFailureSetsFailedAndCleans() {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setId(20L);
    file.setDocumentId("doc-20");
    file.setStatus("PROCESSING");

    IllegalArgumentException error = new IllegalArgumentException("文本内容为空");

    UploadedFileEntity result = policy.onPermanentFailure(file, error);

    assertThat(result.getStatus()).isEqualTo("FAILED");
    assertThat(result.getErrorMessage()).isEqualTo("文本内容为空");
    verify(vectorStore).deleteByDocumentId("doc-20");
    verify(chunkMapper).deletePhysicallyByDocumentId("doc-20");
    verify(uploadedFileMapper).updateById(file);
  }

  @Test
  @DisplayName("深扫甄别：直接 CircuitOpenException 应成功提取")
  void extractCircuitOpenDirect() {
    DependencyUnavailableException.CircuitOpenException coe =
        new DependencyUnavailableException.CircuitOpenException("开闸");

    DependencyUnavailableException extracted = IngestionRecoveryPolicy.extractCircuitOpen(coe);

    assertThat(extracted).isNotNull();
    assertThat(extracted.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);
    assertThat(IngestionRecoveryPolicy.isCircuitOpen(coe)).isTrue();
  }

  @Test
  @DisplayName("深扫甄别：深层包裹 CircuitOpenException 应成功提取")
  void extractCircuitOpenDeeplyNested() {
    DependencyUnavailableException due = DependencyUnavailableException.circuitOpen("vector-store");
    Exception level1 = new IllegalStateException("level1", due);
    Exception level2 = new RuntimeException("level2", level1);

    DependencyUnavailableException extracted = IngestionRecoveryPolicy.extractCircuitOpen(level2);

    assertThat(extracted).isNotNull();
    assertThat(extracted.failureType()).isEqualTo(DependencyFailureType.CIRCUIT_OPEN);
    assertThat(IngestionRecoveryPolicy.isCircuitOpen(level2)).isTrue();
  }

  @Test
  @DisplayName("深扫甄别：普通异常和非熔断异常返回 null")
  void extractCircuitOpenNonCircuit() {
    Exception ordinary = new IllegalArgumentException("普通参数异常");
    assertThat(IngestionRecoveryPolicy.extractCircuitOpen(ordinary)).isNull();
    assertThat(IngestionRecoveryPolicy.isCircuitOpen(ordinary)).isFalse();

    DependencyUnavailableException callFailed =
        new DependencyUnavailableException("调用失败", DependencyFailureType.CALL_FAILED);
    assertThat(IngestionRecoveryPolicy.extractCircuitOpen(callFailed)).isNull();
    assertThat(IngestionRecoveryPolicy.isCircuitOpen(callFailed)).isFalse();

    assertThat(IngestionRecoveryPolicy.extractCircuitOpen(null)).isNull();
  }

  @Test
  @DisplayName("深扫甄别：循环 cause 链防环测试不陷入死循环")
  void extractCircuitOpenCycleProtection() {
    CyclicException e1 = new CyclicException("e1");
    CyclicException e2 = new CyclicException("e2");
    e1.setNextCause(e2);
    e2.setNextCause(e1);

    DependencyUnavailableException result = IngestionRecoveryPolicy.extractCircuitOpen(e1);
    assertThat(result).isNull();
    assertThat(IngestionRecoveryPolicy.isCircuitOpen(e1)).isFalse();
  }

  private static class CyclicException extends RuntimeException {
    private Throwable nextCause;

    CyclicException(String message) {
      super(message);
    }

    void setNextCause(Throwable cause) {
      this.nextCause = cause;
    }

    @Override
    public synchronized Throwable getCause() {
      return nextCause != null ? nextCause : super.getCause();
    }
  }
}
