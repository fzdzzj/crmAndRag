package com.slz.crm.server.service.impl;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 语义向量同步 hook 基类（fix-semantic-hook-support-unify 任务 1.1/1.2）：合同/客户/跟进任务/销售商机/商业活动五域原先各自持有一份
 * 逐字同形的「事务后异步派发 + 回查重嵌」模板，此处承接该模板的**唯一实现**，五域类收缩为薄门面（签名不变、调用点零改动）。
 *
 * <p>职责：事务语义闸 + 异步非阻塞隔离——事务激活时注册 afterCommit（提交后才派发，回滚不派发），无事务环境同样提交给 executor；向量动作内部异常一律 WARN
 * 兜底（全量回埋端点兜底），既不阻断也不外溢业务主流程。HTTP 业务主线程在事务提交后立即 返回响应（微秒级），不等待任何外部向量嵌入耗时。单测可注入直接执行器（{@code
 * Runnable::run}）保证行为 100% 确定性与无竞态。 无可变共享状态，可安全并发调用。
 */
@Slf4j
final class SemanticHookSupport {

  private SemanticHookSupport() {}

  /**
   * 语义向量同步闸（行为与改造前五份逐字实现等价）：事务激活时注册 afterCommit，提交后**异步派发**给 executor，回滚不派发；
   * 无事务环境同样异步派发。向量侧任何异常在异步任务内 WARN 不阻断业务主流程（全量回埋端点兜底），HTTP 主线程零等待。
   *
   * @param domainLabel 域标签（日志定位用：合同 / 客户 / 跟进任务 / 销售商机 / 商业活动，由各域门面固定传入）
   * @param scene 场景描述（日志定位用）
   * @param executor 异步任务执行器（生产为 {@code embeddingTaskExecutor} 线程池；单测可传直接执行器）
   * @param action 向量同步动作
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  // 豁免理由（承接 add-customer-semantic-retrieval 任务 4.3，理由逐字保留）：hook 契约=向量侧任何异常 WARN 不阻断业务主流程，
  // 失败类型不可枚举（嵌入外呼/向量库/确定性 id 派生均可能抛非受检异常），由全量回埋端点兜底。
  static void runAfterCommitAsync(
      String domainLabel, String scene, Executor executor, Runnable action) {
    Runnable guarded =
        () -> {
          try {
            action.run();
          } catch (Exception e) {
            log.warn("{}语义向量同步失败（{}），可由全量回埋端点兜底", domainLabel, scene, e);
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
   * 回查完整实体后 upsert 语义向量（DTO 部分字段更新场景防丢字段）；「是否需要转删向量」由调用域的谓词显式表达——实体缺失为五域共有，
   * 软删（is_deleted）与物理删的分叉逐域自带、留在门面里可见，基类不做二次判空以避免把分叉抹平。仅可在 runAfterCommitAsync 的 action
   * 内调用（异常由异步任务闸捕获）。
   *
   * @param id 业务主键
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   * @param needsVectorDelete 「是否需要删向量」的完整谓词，须自行处理实体缺失（入参可能为 {@code null}）
   * @param ingestAction 重新摄取动作（正常分支）
   * @param deleteAction 删向量动作（缺失/软删分支，按主键删）
   * @param <E> 实体类型
   */
  static <E> void reingestById(
      Long id,
      Function<Long, E> entityLoader,
      Predicate<E> needsVectorDelete,
      Consumer<E> ingestAction,
      LongConsumer deleteAction) {
    E full = entityLoader.apply(id);
    if (needsVectorDelete.test(full)) {
      deleteAction.accept(id);
      return;
    }
    ingestAction.accept(full);
  }
}
