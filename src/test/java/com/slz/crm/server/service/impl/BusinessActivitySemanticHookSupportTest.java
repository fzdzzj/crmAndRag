package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.slz.crm.knowledge.ingestion.BusinessActivityIngestionService;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 商业活动语义向量同步 hook 薄门面回归（fix-semantic-hook-support-unify 任务 2.3）：本域原无专项测试，补此用例仅验证「委托不倒」—— 静态签名转发到
 * {@link SemanticHookSupport}，且本域**物理删（无软删谓词）**的语义与删除直删路径不被抹平。
 */
@DisplayName("商业活动语义向量同步 hook 薄门面")
class BusinessActivitySemanticHookSupportTest {

  @Test
  @DisplayName("无事务环境：门面转发基类，异步派发不阻塞主线程")
  void runAfterCommitAsync_noTransaction_dispatchesAsync() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();

    BusinessActivitySemanticHookSupport.runAfterCommitAsync(
        "门面无事务", captured::set, () -> ran.set(true));

    assertNotNull(captured.get(), "门面转发后同样立即派发");
    assertFalse(ran.get());
    captured.get().run();
    assertTrue(ran.get());
  }

  @Test
  @DisplayName("reingestById：实体缺失（已物理删除）→删向量")
  void reingestById_missingEntityDeletesVector() {
    BusinessActivityIngestionService ingestion = mock(BusinessActivityIngestionService.class);

    BusinessActivitySemanticHookSupport.reingestById(21L, ingestion, id -> null);

    verify(ingestion).deleteByActivityId(21L);
    verify(ingestion, never()).ingest(any());
  }

  @Test
  @DisplayName("reingestById：完整实体回查后 upsert 语义向量（物理删域无软删谓词，不误删）")
  void reingestById_presentEntityIngests() {
    BusinessActivityIngestionService ingestion = mock(BusinessActivityIngestionService.class);
    BusinessActivityEntity full = new BusinessActivityEntity();
    full.setId(21L);
    full.setActivityTitle("甲客户年度拜访");

    BusinessActivitySemanticHookSupport.reingestById(21L, ingestion, id -> full);

    verify(ingestion, never()).deleteByActivityId(any());
    verify(ingestion).ingest(full);
  }

  @Test
  @DisplayName("deleteById：直接按活动主键删向量（删除事务提交后立即失效）")
  void deleteById_deletesVectorByDocumentId() {
    BusinessActivityIngestionService ingestion = mock(BusinessActivityIngestionService.class);

    BusinessActivitySemanticHookSupport.deleteById(21L, ingestion);

    verify(ingestion).deleteByActivityId(21L);
    verify(ingestion, never()).ingest(any());
  }
}
