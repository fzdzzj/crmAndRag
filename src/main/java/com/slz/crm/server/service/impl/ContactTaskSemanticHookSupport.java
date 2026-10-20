package com.slz.crm.server.service.impl;

import com.slz.crm.knowledge.ingestion.ContactTaskIngestionService;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 跟进待办任务语义向量同步 hook 门面（add-unified-business-semantic-retrieval 任务
 * 2.2；fix-semantic-hook-support-unify 任务 2.1 收缩为薄门面）：类名与静态签名保持不变、全部调用点零改动，实现统一委托 {@link
 * SemanticHookSupport}。
 *
 * <p>本域语义：contact_task 为**物理删除**域——reingest 仅在实体缺失时转删向量，无软删谓词；本域另有四个服务层便捷闸 （单条/批量同步、单条/批量删除），属本域独有
 * API，保留原签名并只做「依赖判空 + 委托」。
 */
final class ContactTaskSemanticHookSupport {

  /** 日志定位域标签（承接改造前逐字实现里的日志前缀，文案形态不变）。 */
  private static final String DOMAIN_LABEL = "跟进任务";

  private ContactTaskSemanticHookSupport() {}

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
   * 回查完整实体后 upsert 语义向量；实体缺失转删向量（contact_task 物理删域，无软删谓词，分叉由此处谓词显式表达）。 仅可在 runAfterCommitAsync 的
   * action 内调用（异常由异步任务闸捕获）。
   *
   * @param id 任务主键
   * @param ingestion 摄取服务（调用方已判空）
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   */
  static void reingestById(
      Long id,
      ContactTaskIngestionService ingestion,
      Function<Long, ContactTaskEntity> entityLoader) {
    SemanticHookSupport.reingestById(
        id, entityLoader, full -> full == null, ingestion::ingest, ingestion::deleteByTaskId);
  }

  /**
   * 删除任务后连带失效向量（contact_task 为物理删除，删除事务提交后向量必须立即失效——直接删向量，不回查）。 仅可在 runAfterCommitAsync 的 action
   * 内调用（异常由异步任务闸捕获）。
   *
   * @param id 任务主键
   * @param ingestion 摄取服务（调用方已判空）
   */
  static void deleteById(Long id, ContactTaskIngestionService ingestion) {
    ingestion.deleteByTaskId(id);
  }

  /**
   * 服务层便捷闸（任务 2.2）：依赖未装配（executor/ingestion 缺失，部分单测场景）整体跳过； 装配则事务提交后异步回查重嵌单条任务。
   *
   * @param scene 场景描述（日志定位用）
   * @param executor 异步任务执行器（可空，此时跳过）
   * @param ingestion 摄取服务（可空，此时跳过）
   * @param id 任务主键
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   */
  static void syncAfterCommit(
      String scene,
      Executor executor,
      ContactTaskIngestionService ingestion,
      Long id,
      Function<Long, ContactTaskEntity> entityLoader) {
    if (executor == null || ingestion == null) {
      return;
    }
    runAfterCommitAsync(scene, executor, () -> reingestById(id, ingestion, entityLoader));
  }

  /**
   * 服务层便捷闸（任务 2.2）：批量场景——依赖未装配或 ids 为空整体跳过；装配则事务提交后逐条异步回查重嵌。
   *
   * @param scene 场景描述（日志定位用）
   * @param executor 异步任务执行器（可空，此时跳过）
   * @param ingestion 摄取服务（可空，此时跳过）
   * @param ids 任务主键列表（可空/空，此时跳过）
   * @param entityLoader 完整实体回查函数（ServiceImpl::getById）
   */
  static void syncAllAfterCommit(
      String scene,
      Executor executor,
      ContactTaskIngestionService ingestion,
      List<Long> ids,
      Function<Long, ContactTaskEntity> entityLoader) {
    if (executor == null || ingestion == null || ids == null || ids.isEmpty()) {
      return;
    }
    runAfterCommitAsync(
        scene,
        executor,
        () -> {
          for (Long id : ids) {
            reingestById(id, ingestion, entityLoader);
          }
        });
  }

  /**
   * 服务层便捷闸（任务 2.2）：删除场景——依赖未装配整体跳过；装配则事务提交后异步失效单条任务向量。
   *
   * @param scene 场景描述（日志定位用）
   * @param executor 异步任务执行器（可空，此时跳过）
   * @param ingestion 摄取服务（可空，此时跳过）
   * @param id 任务主键
   */
  static void deleteAfterCommit(
      String scene, Executor executor, ContactTaskIngestionService ingestion, Long id) {
    if (executor == null || ingestion == null) {
      return;
    }
    runAfterCommitAsync(scene, executor, () -> deleteById(id, ingestion));
  }

  /**
   * 服务层便捷闸（任务 2.2）：批量删除场景——依赖未装配或 ids 为空整体跳过；装配则事务提交后逐条异步失效任务向量。
   *
   * @param scene 场景描述（日志定位用）
   * @param executor 异步任务执行器（可空，此时跳过）
   * @param ingestion 摄取服务（可空，此时跳过）
   * @param ids 任务主键列表（可空/空，此时跳过）
   */
  static void deleteAllAfterCommit(
      String scene, Executor executor, ContactTaskIngestionService ingestion, List<Long> ids) {
    if (executor == null || ingestion == null || ids == null || ids.isEmpty()) {
      return;
    }
    runAfterCommitAsync(
        scene,
        executor,
        () -> {
          for (Long id : ids) {
            deleteById(id, ingestion);
          }
        });
  }
}
