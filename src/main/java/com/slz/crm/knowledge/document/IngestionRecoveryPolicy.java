package com.slz.crm.knowledge.document;

import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.resilience.DependencyFailureType;
import com.slz.crm.platform.resilience.DependencyRecoveryPolicy;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 知识库摄取链路依赖恢复策略（wire-ingestion-recovery-replay 任务 2）。
 *
 * <p>实现 {@link DependencyRecoveryPolicy} 接口：
 *
 * <ul>
 *   <li>可恢复失败（熔断开闸）：标 PENDING，复用 markFailed 清理语义（物理清切片+清向量+留原始文件与 DB 记录）；
 *   <li>不可恢复失败（业务异常等）：标 FAILED，逐字等价既有 markFailed 行为。
 * </ul>
 */
@Component
public class IngestionRecoveryPolicy
    implements DependencyRecoveryPolicy<UploadedFileEntity, UploadedFileEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(IngestionRecoveryPolicy.class);

  private final CrmVectorStore vectorStore;
  private final DocumentVectorChunkMapper chunkMapper;
  private final UploadedFileMapper uploadedFileMapper;

  @Autowired
  public IngestionRecoveryPolicy(
      CrmVectorStore vectorStore,
      DocumentVectorChunkMapper chunkMapper,
      UploadedFileMapper uploadedFileMapper) {
    this.vectorStore = vectorStore;
    this.chunkMapper = chunkMapper;
    this.uploadedFileMapper = uploadedFileMapper;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 清理与回写边界，单点异常不阻断状态更新
  public UploadedFileEntity onRecoverableFailure(
      UploadedFileEntity target, DependencyUnavailableException error) {
    Objects.requireNonNull(target, "target 不能为空");
    cleanup(target.getDocumentId());
    target.setStatus("PENDING");
    target.setErrorMessage(shortMessage(error));
    if (uploadedFileMapper != null) {
      uploadedFileMapper.updateById(target);
    }
    return target;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 清理与回写边界，单点异常不阻断状态更新
  public UploadedFileEntity onPermanentFailure(UploadedFileEntity target, Throwable error) {
    Objects.requireNonNull(target, "target 不能为空");
    cleanup(target.getDocumentId());
    target.setStatus("FAILED");
    target.setErrorMessage(shortMessage(error));
    if (uploadedFileMapper != null) {
      uploadedFileMapper.updateById(target);
    }
    return target;
  }

  /**
   * 清理切片与向量数据（物理删切片+清向量库，保留原始文件与 DB 记录）。
   *
   * @param documentId 文档业务唯一键
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 清理多源容错，分别吞异常避免阻断状态落库
  private void cleanup(String documentId) {
    if (documentId != null && !documentId.isBlank()) {
      if (vectorStore != null) {
        try {
          vectorStore.deleteByDocumentId(documentId);
        } catch (Exception cleanupException) {
          LOG.warn("入库失败后清理向量失败 documentId={}", documentId, cleanupException);
        }
      }
      if (chunkMapper != null) {
        try {
          chunkMapper.deletePhysicallyByDocumentId(documentId);
        } catch (Exception cleanupException) {
          LOG.warn("入库失败后清理切片失败 documentId={}", documentId, cleanupException);
        }
      }
    }
  }

  /**
   * 提取异常简要消息，超长截断以符合数据库字段长度要求。
   *
   * @param error 异常
   * @return 简要消息
   */
  public static String shortMessage(Throwable error) {
    String result = "未知异常";
    if (error != null) {
      if (error instanceof Exception ex) {
        result = DocumentIngestionSupport.shortMessage(ex);
      } else {
        String message = error.getMessage();
        result =
            message == null
                ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(900, message.length()));
      }
    }
    return result;
  }

  /**
   * 深扫异常 cause 链查找熔断开闸异常（循环 getCause 防环）。
   *
   * @param throwable 待甄别异常
   * @return 若包含 CircuitOpenException 则返回对应 DependencyUnavailableException；否则返回 null
   */
  public static DependencyUnavailableException extractCircuitOpen(Throwable throwable) {
    DependencyUnavailableException result = null;
    if (throwable != null) {
      Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      Throwable current = throwable;
      DependencyUnavailableException due = null;
      DependencyUnavailableException.CircuitOpenException coe = null;
      while (current != null && seen.add(current)) {
        if (current instanceof DependencyUnavailableException d && due == null) {
          due = d;
        }
        if (current instanceof DependencyUnavailableException.CircuitOpenException c
            && coe == null) {
          coe = c;
        }
        current = current.getCause();
      }
      if (coe != null) {
        result =
            due != null
                ? due
                : new DependencyUnavailableException(
                    coe.getMessage(), coe, DependencyFailureType.CIRCUIT_OPEN);
      }
    }
    return result;
  }

  /**
   * 判断异常 cause 链是否包含熔断开闸异常。
   *
   * @param throwable 待甄别异常
   * @return 是否熔断开闸
   */
  public static boolean isCircuitOpen(Throwable throwable) {
    return extractCircuitOpen(throwable) != null;
  }
}
