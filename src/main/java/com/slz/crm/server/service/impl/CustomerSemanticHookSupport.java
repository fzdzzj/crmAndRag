package com.slz.crm.server.service.impl;

import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import java.util.concurrent.Executor;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 客户语义向量同步 hook 协作类（add-customer-semantic-retrieval 任务 2.3；任务 4.3 PMD NcssCount 拆分： 拆自 {@link
 * CustomerCompanyServiceImpl}，行为等价；add-unified-business-semantic-retrieval 任务 2.1 升级为**事务后异步派发**）。
 *
 * <p>职责：事务语义闸（事务激活注册 afterCommit、无事务环境同样提交执行器）与回查重嵌。向量动作一律经 executor.execute 异步派发，HTTP
 * 业务主线程在事务提交后立即返回（微秒级），不等待任何外部向量嵌入耗时；单测可注入直接执行器（{@code Runnable::run}）保证行为 100%
 * 确定性与无竞态。无可变共享状态，可安全并发调用。
 */
@Slf4j
final class CustomerSemanticHookSupport {

  private CustomerSemanticHookSupport() {}

  /**
   * 语义向量同步闸（add-customer-semantic-retrieval 任务 2.3；任务 2.1 异步化）：事务激活时注册 afterCommit（提交后才**异步派发**给
   * executor），回滚不写向量；无事务环境同样异步派发。向量侧任何异常在异步任务内 WARN 不阻断业务主流程（全量回埋端点兜底），HTTP 主线程零等待。
   *
   * @param scene 场景描述（日志定位用）
   * @param executor 异步任务执行器（生产为 {@code embeddingTaskExecutor} 线程池；单测可传直接执行器）
   * @param action 向量同步动作
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  // 豁免理由（add-customer-semantic-retrieval 任务 4.3）：hook 契约=向量侧任何异常 WARN 不阻断业务主流程，
  // 失败类型不可枚举（嵌入外呼/向量库/确定性 id 派生均可能抛非受检异常），由全量回埋端点兜底。
  static void runAfterCommitAsync(String scene, Executor executor, Runnable action) {
    Runnable guarded =
        () -> {
          try {
            action.run();
          } catch (Exception e) {
            log.warn("客户语义向量同步失败（{}），可由全量回埋端点兜底", scene, e);
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.execute(guarded);
            }
          });
    } else {
      executor.execute(guarded);
    }
  }

  /**
   * 回查完整实体后 upsert 语义向量（DTO 部分字段更新场景防丢字段）；实体缺失或已删转删向量。 仅可在 runAfterCommitAsync 的 action
   * 内调用（异常由异步任务闸捕获）。
   *
   * @param id 客户公司主键
   * @param ingestion 摄取服务（调用方已判空）
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   */
  static void reingestById(
      Long id,
      CustomerIngestionService ingestion,
      Function<Long, CustomerCompanyEntity> entityLoader) {
    CustomerCompanyEntity full = entityLoader.apply(id);
    if (full == null || Boolean.TRUE.equals(full.getIsDeleted())) {
      ingestion.deleteByCompanyId(id);
      return;
    }
    ingestion.ingest(full);
  }
}
