package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.slz.crm.knowledge.ingestion.ContractIngestionService;
import com.slz.crm.pojo.entity.ContractEntity;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 合同语义向量同步钩子测试（add-unified-business-semantic-retrieval 任务 5.2）。
 *
 * <p>异步非阻塞隔离：事务激活时 afterCommit 前零执行、afterCommit 仅派发不执行（HTTP 主线程零等待）；无事务环境同样异步派发； 直接执行器（{@code
 * Runnable::run}）下行为 100% 确定性与无竞态；向量动作失败仅 WARN 不向上抛出。与 {@link ContractSemanticHookSupport} 同包以覆盖
 * package-private 成员。
 */
@DisplayName("合同语义向量同步钩子")
class ContractSemanticHookSupportTest {

  @Test
  @DisplayName("事务激活：注册 afterCommit 且提交前零执行；afterCommit 仅派发至 executor 不阻塞主线程")
  void transactionActive_dispatchesOnCommitNotSynchronously() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();
    Executor capturing = captured::set;
    TransactionSynchronizationManager.initSynchronization();
    try {
      ContractSemanticHookSupport.runAfterCommitAsync("测试事务", capturing, () -> ran.set(true));

      assertFalse(ran.get(), "事务提交前不得执行向量动作");
      List<TransactionSynchronization> syncs =
          TransactionSynchronizationManager.getSynchronizations();
      assertTrue(syncs.size() == 1, "事务激活时恰好注册一个 afterCommit 同步器");
      syncs.forEach(TransactionSynchronization::afterCommit);
      assertNotNull(captured.get(), "afterCommit 后任务已派发至 executor");
      assertFalse(ran.get(), "派发不等于执行：主线程已返回，向量动作仍在线程池队列");

      captured.get().run();
      assertTrue(ran.get(), "executor 真正执行后向量动作生效");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("无事务环境：同样异步派发至 executor，主线程不等待动作执行")
  void noTransaction_dispatchesAsyncWithoutBlocking() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();
    Executor capturing = captured::set;

    ContractSemanticHookSupport.runAfterCommitAsync("无事务", capturing, () -> ran.set(true));

    assertNotNull(captured.get(), "无事务环境同样立即异步派发");
    assertFalse(ran.get(), "主线程派发即返回，不等待向量动作执行");
    captured.get().run();
    assertTrue(ran.get());
  }

  @Test
  @DisplayName("直接执行器（Runnable::run）：无事务环境行为 100% 确定，动作同步完成")
  void directExecutor_runsDeterministically() {
    AtomicBoolean ran = new AtomicBoolean(false);

    ContractSemanticHookSupport.runAfterCommitAsync("直接执行器", Runnable::run, () -> ran.set(true));

    assertTrue(ran.get(), "直接执行器下动作立即确定性完成（单测无竞态）");
  }

  @Test
  @DisplayName("向量动作失败：WARN 不向上抛出，业务主流程零阻断")
  void actionFailure_isIsolatedFromBusinessFlow() {
    AtomicBoolean ran = new AtomicBoolean(false);

    ContractSemanticHookSupport.runAfterCommitAsync(
        "失败隔离",
        Runnable::run,
        () -> {
          ran.set(true);
          throw new IllegalStateException("embed down");
        });

    assertTrue(ran.get(), "动作已尝试执行；异常被闸捕获未向上冒泡（否则本用例即红）");
  }

  @Test
  @DisplayName("reingestById：实体缺失（已物理删除）转删向量")
  void reingestById_entityMissingDeletesVector() {
    ContractIngestionService ingestion = mock(ContractIngestionService.class);

    ContractSemanticHookSupport.reingestById(201L, ingestion, id -> null);

    verify(ingestion).deleteByContractId(201L);
    verify(ingestion, never()).ingest(any());
  }

  @Test
  @DisplayName("reingestById：完整实体回查后 upsert 语义向量")
  void reingestById_ingestsFullEntity() {
    ContractIngestionService ingestion = mock(ContractIngestionService.class);
    ContractEntity full = new ContractEntity();
    full.setId(201L);
    full.setContractName("甲公司年度软件服务合同");
    Function<Long, ContractEntity> loader = id -> full;

    ContractSemanticHookSupport.reingestById(201L, ingestion, loader);

    verify(ingestion, never()).deleteByContractId(any());
    verify(ingestion).ingest(full);
  }

  @Test
  @DisplayName("deleteById：直接按合同主键删向量（删除事务提交后立即失效）")
  void deleteById_deletesVectorByDocumentId() {
    ContractIngestionService ingestion = mock(ContractIngestionService.class);

    ContractSemanticHookSupport.deleteById(201L, ingestion);

    verify(ingestion).deleteByContractId(201L);
    verify(ingestion, never()).ingest(any());
  }
}
