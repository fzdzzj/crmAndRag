package com.slz.crm.server.service.impl;

import com.slz.crm.knowledge.ingestion.BusinessActivityIngestionService;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 商业活动语义向量同步 hook 门面（add-business-activity-semantic-retrieval 任务
 * 2.2；fix-semantic-hook-support-unify 任务 2.1 收缩为薄门面）：类名与静态签名保持不变、全部调用点零改动，实现统一委托 {@link
 * SemanticHookSupport}。
 *
 * <p>本域语义：business_activity 无软删列、删除即**物理删除**——reingest 仅在实体缺失时转删向量，无软删谓词；删除后按主键直删向量 （{@link
 * #deleteById}）。
 */
final class BusinessActivitySemanticHookSupport {

  /** 日志定位域标签（承接改造前逐字实现里的日志前缀，文案形态不变）。 */
  private static final String DOMAIN_LABEL = "商业活动";

  private BusinessActivitySemanticHookSupport() {}

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
   * 回查完整实体后 upsert 语义向量；实体缺失（已删）转删向量（business_activity 物理删域，无软删谓词，分叉由此处谓词显式表达）。 仅可在
   * runAfterCommitAsync 的 action 内调用（异常由异步任务闸捕获）。
   *
   * @param id 商业活动主键
   * @param ingestion 摄取服务（调用方已判空）
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   */
  static void reingestById(
      Long id,
      BusinessActivityIngestionService ingestion,
      Function<Long, BusinessActivityEntity> entityLoader) {
    SemanticHookSupport.reingestById(
        id, entityLoader, full -> full == null, ingestion::ingest, ingestion::deleteByActivityId);
  }

  /**
   * 物理删除后连带失效向量（business_activity 无软删列，删除即物理删除，回查必为空——直接删向量，不做回查）。 仅可在 runAfterCommitAsync 的 action
   * 内调用（异常由异步任务闸捕获）。
   *
   * @param id 商业活动主键
   * @param ingestion 摄取服务（调用方已判空）
   */
  static void deleteById(Long id, BusinessActivityIngestionService ingestion) {
    ingestion.deleteByActivityId(id);
  }
}
