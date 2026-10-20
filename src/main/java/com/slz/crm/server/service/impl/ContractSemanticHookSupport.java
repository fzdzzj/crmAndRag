package com.slz.crm.server.service.impl;

import com.slz.crm.knowledge.ingestion.ContractIngestionService;
import com.slz.crm.pojo.entity.ContractEntity;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 销售合同语义向量同步 hook 门面（add-unified-business-semantic-retrieval 任务 2.2；fix-semantic-hook-support-unify
 * 任务 2.1 收缩为薄门面）：类名与静态签名保持不变、全部调用点零改动，实现统一委托 {@link SemanticHookSupport}。
 *
 * <p>本域语义：合同为**物理删除**域——reingest 仅在实体缺失时转删向量，无软删（is_deleted）谓词；删除后直接按主键删向量 （{@link
 * #deleteById}，调用面实测仍被 {@code ContractServiceImpl} 使用，故保留门面）。
 */
final class ContractSemanticHookSupport {

  /** 日志定位域标签（承接改造前逐字实现里的日志前缀，文案形态不变）。 */
  private static final String DOMAIN_LABEL = "合同";

  private ContractSemanticHookSupport() {}

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
   * 回查完整实体后 upsert 语义向量；实体缺失转删向量（合同物理删域，无软删谓词，分叉由此处谓词显式表达）。 仅可在 runAfterCommitAsync 的 action
   * 内调用（异常由异步任务闸捕获）。
   *
   * @param id 合同主键
   * @param ingestion 摄取服务（调用方已判空）
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   */
  static void reingestById(
      Long id, ContractIngestionService ingestion, Function<Long, ContractEntity> entityLoader) {
    SemanticHookSupport.reingestById(
        id, entityLoader, full -> full == null, ingestion::ingest, ingestion::deleteByContractId);
  }

  /**
   * 删除合同后连带失效向量（contract 为物理删除，删除事务提交后向量必须立即失效——直接删向量，不回查）。 仅可在 runAfterCommitAsync 的 action
   * 内调用（异常由异步任务闸捕获）。
   *
   * @param id 合同主键
   * @param ingestion 摄取服务（调用方已判空）
   */
  static void deleteById(Long id, ContractIngestionService ingestion) {
    ingestion.deleteByContractId(id);
  }
}
