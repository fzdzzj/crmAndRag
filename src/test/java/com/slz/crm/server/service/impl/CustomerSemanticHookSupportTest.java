package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 客户语义向量同步 hook 薄门面回归（fix-semantic-hook-support-unify 任务 2.3）：本域原无专项测试，补此用例仅验证「委托不倒」—— 静态签名转发到
 * {@link SemanticHookSupport}，且本域**软删（is_deleted）谓词注入正确**（与物理删域的分叉不被抹平）。
 */
@DisplayName("客户语义向量同步 hook 薄门面")
class CustomerSemanticHookSupportTest {

  @Test
  @DisplayName("事务激活：门面转发基类，注册恰好一个 afterCommit；提交后才派发")
  void runAfterCommitAsync_transactionActive_registersAfterCommitViaBase() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();
    TransactionSynchronizationManager.initSynchronization();
    try {
      CustomerSemanticHookSupport.runAfterCommitAsync("门面事务", captured::set, () -> ran.set(true));

      assertFalse(ran.get(), "提交前零执行");
      List<TransactionSynchronization> syncs =
          TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, syncs.size(), "门面委派后基类只注册一个同步器");
      syncs.forEach(TransactionSynchronization::afterCommit);
      assertNotNull(captured.get(), "afterCommit 后任务派发至 executor");
      captured.get().run();
      assertTrue(ran.get());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("无事务环境：门面转发基类，同样异步派发不阻塞主线程")
  void runAfterCommitAsync_noTransaction_dispatchesAsync() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();

    CustomerSemanticHookSupport.runAfterCommitAsync("门面无事务", captured::set, () -> ran.set(true));

    assertNotNull(captured.get());
    assertFalse(ran.get());
    captured.get().run();
    assertTrue(ran.get());
  }

  @Test
  @DisplayName("reingestById：实体已软删（is_deleted=true）→删向量，谓词注入正确")
  void reingestById_softDeletedEntityDeletesVector() {
    CustomerIngestionService ingestion = mock(CustomerIngestionService.class);
    CustomerCompanyEntity deleted = new CustomerCompanyEntity();
    deleted.setId(11L);
    deleted.setIsDeleted(Boolean.TRUE);

    CustomerSemanticHookSupport.reingestById(11L, ingestion, id -> deleted);

    verify(ingestion).deleteByCompanyId(11L);
    verify(ingestion, never()).ingest(any());
  }

  @Test
  @DisplayName("reingestById：实体缺失→删向量")
  void reingestById_missingEntityDeletesVector() {
    CustomerIngestionService ingestion = mock(CustomerIngestionService.class);

    CustomerSemanticHookSupport.reingestById(11L, ingestion, id -> null);

    verify(ingestion).deleteByCompanyId(11L);
    verify(ingestion, never()).ingest(any());
  }

  @Test
  @DisplayName("reingestById：正常实体（is_deleted=false）→回查后 upsert 语义向量")
  void reingestById_presentEntityIngests() {
    CustomerIngestionService ingestion = mock(CustomerIngestionService.class);
    CustomerCompanyEntity full = new CustomerCompanyEntity();
    full.setId(11L);
    full.setIsDeleted(Boolean.FALSE);

    CustomerSemanticHookSupport.reingestById(11L, ingestion, id -> full);

    verify(ingestion, never()).deleteByCompanyId(any());
    verify(ingestion).ingest(full);
  }
}
