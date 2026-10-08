package com.slz.crm.knowledge.document;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.resilience.DependencyRecoveryPolicy;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UploadedFileMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 摄取恢复重放调度器（wire-ingestion-recovery-replay 任务 3）。
 *
 * <p>定时扫描处于 PENDING 状态的待恢复文档，以原上传者真实身份构造 {@link UserContext} 调用 {@link
 * DocumentIngestionService#reingest(String, UserContext)} 自动重放。
 *
 * <ul>
 *   <li>开关控制：tick 首行实时读动态配置 {@code rag.ingest.replay-enabled}，关闭或异常时空转跳过；
 *   <li>批量上限：单批最多扫描 {@code rag.ingest.replay-batch-size} 篇（默认 5，范围 1~50）；
 *   <li>流转语义：成功转 COMPLETED；熔断仍开保持 PENDING（零费用）；授权拒绝或身份失效转 FAILED 终态；
 *   <li>单篇容错：单篇异常记录日志并继续，不中断当轮批量重放。
 * </ul>
 */
@Component
public class IngestionReplayScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(IngestionReplayScheduler.class);

  public static final String KEY_REPLAY_ENABLED = "rag.ingest.replay-enabled";
  public static final String KEY_REPLAY_BATCH_SIZE = "rag.ingest.replay-batch-size";

  public static final boolean DEFAULT_REPLAY_ENABLED = false;
  public static final int DEFAULT_REPLAY_BATCH_SIZE = 5;
  public static final int MIN_BATCH_SIZE = 1;
  public static final int MAX_BATCH_SIZE = 50;

  private final DocumentIngestionService ingestionService;
  private final UploadedFileMapper uploadedFileMapper;
  private final UserMapper userMapper;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private final DynamicConfigService directConfigService;
  private final DependencyRecoveryPolicy<UploadedFileEntity, UploadedFileEntity> recoveryPolicy;

  @Autowired
  public IngestionReplayScheduler(
      DocumentIngestionService ingestionService,
      UploadedFileMapper uploadedFileMapper,
      UserMapper userMapper,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      DependencyRecoveryPolicy<UploadedFileEntity, UploadedFileEntity> recoveryPolicy) {
    this.ingestionService = ingestionService;
    this.uploadedFileMapper = uploadedFileMapper;
    this.userMapper = userMapper;
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.directConfigService = null;
    this.recoveryPolicy = recoveryPolicy;
  }

  IngestionReplayScheduler(
      DocumentIngestionService ingestionService,
      UploadedFileMapper uploadedFileMapper,
      UserMapper userMapper,
      DynamicConfigService directConfigService,
      DependencyRecoveryPolicy<UploadedFileEntity, UploadedFileEntity> recoveryPolicy) {
    this.ingestionService = ingestionService;
    this.uploadedFileMapper = uploadedFileMapper;
    this.userMapper = userMapper;
    this.dynamicConfigProvider = null;
    this.directConfigService = directConfigService;
    this.recoveryPolicy = recoveryPolicy;
  }

  /** 定时重放扫描入口：首行实时判定开关，开启时拉取 PENDING 批次按原上传者重放。 */
  @Scheduled(fixedDelayString = "${crm.ingest.replay-tick-ms:60000}")
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 调度主循环容错：单篇或全批异常均吞入并记录日志，避免打断定时器
  public void tick() {
    if (isReplayEnabled()) {
      int batchSize = resolveBatchSize();
      List<UploadedFileEntity> pendingFiles = null;
      try {
        pendingFiles =
            uploadedFileMapper.selectList(
                new QueryWrapper<UploadedFileEntity>()
                    .eq("status", "PENDING")
                    .orderByAsc("id")
                    .last("LIMIT " + batchSize));
      } catch (Exception exception) {
        LOG.error("扫描 PENDING 状态文档失败", exception);
      }

      if (pendingFiles != null && !pendingFiles.isEmpty()) {
        LOG.info("开始执行摄取恢复重放 batchCount={}", pendingFiles.size());
        for (UploadedFileEntity file : pendingFiles) {
          replaySingleFile(file);
        }
      }
    }
  }

  /**
   * 重放单篇文档：校验上传者身份、执行 reingest 并按结果流转状态。
   *
   * @param file 待重放文件实体
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 业务与权限多源异常捕获，按分类分别落终态或保持 PENDING
  private void replaySingleFile(UploadedFileEntity file) {
    UserContext operator = resolveOperator(file);
    if (operator == null) {
      LOG.warn("重放文档上传者不存在或身份不完整，转 FAILED 终态 fileId={} userId={}", file.getId(), file.getUserId());
      markPermanentFailed(file, new IllegalStateException("上传者不存在或身份不完整: " + file.getUserId()));
    } else {
      try {
        ingestionService.reingest(file.getDocumentId(), operator);
        LOG.info("重放文档成功 documentId={}", file.getDocumentId());
      } catch (Exception exception) {
        DependencyUnavailableException circuitError =
            IngestionRecoveryPolicy.extractCircuitOpen(exception);
        if (circuitError != null) {
          LOG.info("重放文档遇到依赖熔断开闸，保持 PENDING 下轮再试 documentId={}", file.getDocumentId());
          file.setStatus("PENDING");
          file.setErrorMessage(DocumentIngestionSupport.shortMessage(exception));
          uploadedFileMapper.updateById(file);
        } else {
          LOG.warn("重放文档失败转 FAILED 终态 documentId={}", file.getDocumentId(), exception);
          markPermanentFailed(file, exception);
        }
      }
    }
  }

  private void markPermanentFailed(UploadedFileEntity file, Throwable error) {
    if (recoveryPolicy != null) {
      recoveryPolicy.onPermanentFailure(file, error);
    } else {
      file.setStatus("FAILED");
      file.setErrorMessage(IngestionRecoveryPolicy.shortMessage(error));
      uploadedFileMapper.updateById(file);
    }
  }

  /**
   * 按 uploaded_file.userId 解析原上传者身份（对齐 KnowledgeReingestRunner 身份自检）。
   *
   * @param file 上传文件实体
   * @return 校验通过的 UserContext，若无效则返回 null
   */
  private UserContext resolveOperator(UploadedFileEntity file) {
    UserContext result = null;
    if (file != null && file.getUserId() != null && !file.getUserId().isBlank()) {
      Long userId = null;
      try {
        userId = Long.valueOf(file.getUserId().strip());
      } catch (NumberFormatException ignored) {
        userId = null;
      }
      if (userId != null) {
        UserEntity userEntity = userMapper.selectById(userId);
        if (userEntity != null
            && userEntity.getRoleId() != null
            && userEntity.getDeptId() != null) {
          result =
              new UserContext(
                  userEntity.getId(),
                  userEntity.getRoleId(),
                  userEntity.getDeptId(),
                  DataScopeLevel.NONE,
                  userEntity.getRealName());
        }
      }
    }
    return result;
  }

  /**
   * 实时解析重放总开关，fail-safe 回落默认值 false。
   *
   * @return 是否开启重放
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 配置容错解析，异常必回落默认值
  public boolean isReplayEnabled() {
    boolean result = DEFAULT_REPLAY_ENABLED;
    try {
      DynamicConfigService config = resolveConfigService();
      if (config != null) {
        Boolean value = config.get(KEY_REPLAY_ENABLED, Boolean.class, DEFAULT_REPLAY_ENABLED);
        if (value != null) {
          result = value;
        }
      }
    } catch (Exception ignored) {
      result = DEFAULT_REPLAY_ENABLED;
    }
    return result;
  }

  /**
   * 实时解析单批重放数量上限，范围 1~50，fail-safe 回落默认值 5。
   *
   * @return 单批重放数量
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 配置容错解析，异常必回落默认值
  public int resolveBatchSize() {
    int result = DEFAULT_REPLAY_BATCH_SIZE;
    try {
      DynamicConfigService config = resolveConfigService();
      if (config != null) {
        Integer value = config.get(KEY_REPLAY_BATCH_SIZE, Integer.class, DEFAULT_REPLAY_BATCH_SIZE);
        if (value != null && value >= MIN_BATCH_SIZE && value <= MAX_BATCH_SIZE) {
          result = value;
        }
      }
    } catch (Exception ignored) {
      result = DEFAULT_REPLAY_BATCH_SIZE;
    }
    return result;
  }

  private DynamicConfigService resolveConfigService() {
    DynamicConfigService result = directConfigService;
    if (result == null && dynamicConfigProvider != null) {
      result = dynamicConfigProvider.getIfAvailable();
    }
    return result;
  }
}
