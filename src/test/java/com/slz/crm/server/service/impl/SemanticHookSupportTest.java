package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 语义向量同步 hook 基类测试（fix-semantic-hook-support-unify 任务 1.3，四象限）。
 *
 * <p>覆盖：① 事务激活时注册 afterCommit、提交前零执行、afterCommit 仅派发不执行；② 回滚（afterCompletion 未提交）不派发；③ 无事务环境直发
 * executor；④ 动作抛异常 WARN 兜底不阻断不外溢（并校验参数化日志文案保留域定位能力）；⑤ reingest 三分支：实体缺失→删、软删→删（此处按 {@link
 * CustomerSemanticHookSupport} 的软删谓词形态构造，证明基类对该分叉的承载）、正常→ingest。与 {@link SemanticHookSupport} 同包以覆盖
 * package-private 成员。
 */
@DisplayName("语义向量同步 hook 基类")
class SemanticHookSupportTest {

  private static final String DOMAIN_LABEL = "客户";

  @Test
  @DisplayName("事务激活：注册单个 afterCommit，提交前零执行，提交后仅派发至 executor 不阻塞主线程")
  void transactionActive_registersAfterCommitAndDispatchesOnlyAfterCommit() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();
    Executor capturing = captured::set;
    TransactionSynchronizationManager.initSynchronization();
    try {
      SemanticHookSupport.runAfterCommitAsync(DOMAIN_LABEL, "测试事务", capturing, () -> ran.set(true));

      assertFalse(ran.get(), "事务提交前不得执行向量动作");
      List<TransactionSynchronization> syncs =
          TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, syncs.size(), "事务激活时恰好注册一个 afterCommit 同步器");
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
  @DisplayName("事务回滚：afterCommit 不被回调，向量动作零派发（回滚不写向量）")
  void transactionRolledBack_doesNotDispatch() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();
    Executor capturing = captured::set;
    TransactionSynchronizationManager.initSynchronization();
    try {
      SemanticHookSupport.runAfterCommitAsync(DOMAIN_LABEL, "回滚事务", capturing, () -> ran.set(true));

      List<TransactionSynchronization> syncs =
          TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, syncs.size());
      syncs.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

      assertNull(captured.get(), "回滚不得派发向量动作");
      assertFalse(ran.get());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("无事务环境：同样异步派发至 executor，主线程不等待动作执行")
  void noTransaction_dispatchesToExecutorImmediately() {
    AtomicBoolean ran = new AtomicBoolean(false);
    AtomicReference<Runnable> captured = new AtomicReference<>();
    Executor capturing = captured::set;

    SemanticHookSupport.runAfterCommitAsync(DOMAIN_LABEL, "无事务", capturing, () -> ran.set(true));

    assertNotNull(captured.get(), "无事务环境同样立即异步派发");
    assertFalse(ran.get(), "主线程派发即返回，不等待向量动作执行");
    captured.get().run();
    assertTrue(ran.get());
  }

  @Test
  @DisplayName("动作抛异常：WARN 兜底不向上冒泡，且日志仍带域标签与场景（定位能力保留）")
  void actionFailure_isWarnedAndNotPropagated() {
    Logger logger = (Logger) LoggerFactory.getLogger(SemanticHookSupport.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.setLevel(Level.WARN);
    logger.addAppender(appender);
    AtomicBoolean ran = new AtomicBoolean(false);
    try {
      SemanticHookSupport.runAfterCommitAsync(
          DOMAIN_LABEL,
          "失败隔离",
          Runnable::run,
          () -> {
            ran.set(true);
            throw new IllegalStateException("embed down");
          });

      assertTrue(ran.get(), "动作已尝试执行；异常被闸捕获未向上冒泡（否则本用例即红）");
      assertEquals(1, appender.list.size(), "异常仅记录一条 WARN");
      ILoggingEvent event = appender.list.get(0);
      assertEquals(Level.WARN, event.getLevel());
      assertEquals(
          "客户语义向量同步失败（失败隔离），可由全量回埋端点兜底", event.getFormattedMessage(), "域标签 + 场景参数化后文案与原逐字实现一致");
    } finally {
      logger.setLevel(previousLevel);
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("reingestById：实体缺失→删向量（Customer 软删谓词形态下同样成立）")
  void reingestById_missingEntityDeletesVector() {
    CustomerIngestionService ingestion = mock(CustomerIngestionService.class);

    SemanticHookSupport.reingestById(
        7L,
        id -> null,
        full -> full == null || Boolean.TRUE.equals(full.getIsDeleted()),
        ingestion::ingest,
        ingestion::deleteByCompanyId);

    verify(ingestion).deleteByCompanyId(7L);
    verify(ingestion, never()).ingest(any());
  }

  @Test
  @DisplayName("reingestById：实体已软删（is_deleted=true）→删向量（软删分叉由谓词承载）")
  void reingestById_softDeletedEntityDeletesVector() {
    CustomerIngestionService ingestion = mock(CustomerIngestionService.class);
    CustomerCompanyEntity deleted = new CustomerCompanyEntity();
    deleted.setId(7L);
    deleted.setIsDeleted(Boolean.TRUE);

    SemanticHookSupport.reingestById(
        7L,
        id -> deleted,
        full -> full == null || Boolean.TRUE.equals(full.getIsDeleted()),
        ingestion::ingest,
        ingestion::deleteByCompanyId);

    verify(ingestion).deleteByCompanyId(7L);
    verify(ingestion, never()).ingest(any());
  }

  @Test
  @DisplayName("reingestById：正常实体→回查后 upsert 语义向量（不删）")
  void reingestById_presentEntityIngests() {
    CustomerIngestionService ingestion = mock(CustomerIngestionService.class);
    CustomerCompanyEntity full = new CustomerCompanyEntity();
    full.setId(7L);
    full.setIsDeleted(Boolean.FALSE);
    full.setCompanyName("甲公司");

    SemanticHookSupport.reingestById(
        7L,
        id -> full,
        entity -> entity == null || Boolean.TRUE.equals(entity.getIsDeleted()),
        ingestion::ingest,
        ingestion::deleteByCompanyId);

    verify(ingestion, never()).deleteByCompanyId(any());
    verify(ingestion).ingest(full);
  }
}
