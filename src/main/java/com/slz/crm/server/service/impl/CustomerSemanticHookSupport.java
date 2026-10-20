package com.slz.crm.server.service.impl;

import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 客户语义向量同步 hook 门面（add-customer-semantic-retrieval 任务 2.3；fix-semantic-hook-support-unify 任务 2.1
 * 收缩为薄门面）： 类名与静态签名保持不变、全部调用点零改动，实现统一委托 {@link SemanticHookSupport}。
 *
 * <p>本域语义：客户公司为**逻辑删除**域——reingest 在实体缺失或已软删（{@code is_deleted = true}）时转删向量（谓词逐字保留，分叉
 * 不得抹平）；无删除后直删的 {@code deleteById} 门面（删除路径由服务层直接调摄取服务）。
 */
final class CustomerSemanticHookSupport {

  /** 日志定位域标签（承接改造前逐字实现里的日志前缀，文案形态不变）。 */
  private static final String DOMAIN_LABEL = "客户";

  private CustomerSemanticHookSupport() {}

  /**
   * 语义向量同步闸：事务激活时注册 afterCommit，提交后异步派发；无事务环境同样异步派发；异常 WARN 不阻断（详见基类）。
   *
   * @param scene 场景描述（日志定位用）
   * @param executor 异步任务执行器（单测可传直接执行器）
   * @param action 向量同步动作
   */
  static void runAfterCommitAsync(String scene, Executor executor, Runnable action) {
    SemanticHookSupport.runAfterCommitAsync(DOMAIN_LABEL, scene, executor, action);
  }

  /**
   * 回查完整实体后 upsert 语义向量；实体缺失或已软删（is_deleted）转删向量——软删谓词为本域分叉，逐字保留在此。 仅可在 runAfterCommitAsync 的
   * action 内调用（异常由异步任务闸捕获）。
   *
   * @param id 客户公司主键
   * @param ingestion 摄取服务（调用方已判空）
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   */
  static void reingestById(
      Long id,
      CustomerIngestionService ingestion,
      Function<Long, CustomerCompanyEntity> entityLoader) {
    SemanticHookSupport.reingestById(
        id,
        entityLoader,
        full -> full == null || Boolean.TRUE.equals(full.getIsDeleted()),
        ingestion::ingest,
        ingestion::deleteByCompanyId);
  }
}
