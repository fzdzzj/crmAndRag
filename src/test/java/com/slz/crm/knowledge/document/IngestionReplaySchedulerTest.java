package com.slz.crm.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.resilience.DependencyRecoveryPolicy;
import com.slz.crm.platform.resilience.DependencyUnavailableException;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UploadedFileMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 摄取恢复重放调度器测试（wire-ingestion-recovery-replay 任务 3.1）。
 *
 * <p>覆盖契约 3 与契约 4：
 *
 * <ul>
 *   <li>enabled=false 空转零调用（费用红线）
 *   <li>enabled=true 扫 PENDING 按批量上限
 *   <li>成功转 COMPLETED
 *   <li>熔断仍开保持 PENDING
 *   <li>授权拒绝与身份失效转 FAILED 终态
 *   <li>单篇失败不中断本轮
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionReplaySchedulerTest {

  @Mock private DocumentIngestionService ingestionService;
  @Mock private UploadedFileMapper uploadedFileMapper;
  @Mock private UserMapper userMapper;
  @Mock private DynamicConfigService dynamicConfigService;
  @Mock private DependencyRecoveryPolicy<UploadedFileEntity, UploadedFileEntity> recoveryPolicy;

  private IngestionReplayScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler =
        new IngestionReplayScheduler(
            ingestionService, uploadedFileMapper, userMapper, dynamicConfigService, recoveryPolicy);
  }

  @Test
  @DisplayName("契约 4：enabled=false 时空转零调用")
  void tickDisabledShouldDoNothing() {
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_ENABLED), eq(Boolean.class), any()))
        .thenReturn(false);

    scheduler.tick();

    verify(uploadedFileMapper, never()).selectList(any());
    verify(ingestionService, never()).reingest(anyString(), any());
  }

  @Test
  @DisplayName("契约 3：enabled=true 且有 PENDING 文档时逐篇以原上传者身份重放")
  void tickEnabledShouldReplayPendingDocuments() {
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_ENABLED), eq(Boolean.class), any()))
        .thenReturn(true);
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_BATCH_SIZE), eq(Integer.class), any()))
        .thenReturn(5);

    UploadedFileEntity file = new UploadedFileEntity();
    file.setId(101L);
    file.setDocumentId("doc-101");
    file.setUserId("201");
    file.setStatus("PENDING");

    when(uploadedFileMapper.selectList(any())).thenReturn(List.of(file));

    UserEntity user = new UserEntity();
    user.setId(201L);
    user.setRoleId(1L);
    user.setDeptId(10L);
    user.setRealName("张三");
    when(userMapper.selectById(201L)).thenReturn(user);

    scheduler.tick();

    ArgumentCaptor<UserContext> userCaptor = ArgumentCaptor.forClass(UserContext.class);
    verify(ingestionService).reingest(eq("doc-101"), userCaptor.capture());
    UserContext operator = userCaptor.getValue();
    assertThat(operator.userId()).isEqualTo(201L);
    assertThat(operator.roleId()).isEqualTo(1L);
    assertThat(operator.deptId()).isEqualTo(10L);
    assertThat(operator.displayName()).isEqualTo("张三");
  }

  @Test
  @DisplayName("契约 3：重放遇到熔断开闸异常应保持 PENDING 下轮再试")
  void tickReingestFailsWithCircuitOpenShouldKeepPending() {
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_ENABLED), eq(Boolean.class), any()))
        .thenReturn(true);

    UploadedFileEntity file = new UploadedFileEntity();
    file.setId(102L);
    file.setDocumentId("doc-102");
    file.setUserId("202");
    file.setStatus("PENDING");

    when(uploadedFileMapper.selectList(any())).thenReturn(List.of(file));

    UserEntity user = new UserEntity();
    user.setId(202L);
    user.setRoleId(1L);
    user.setDeptId(10L);
    user.setRealName("李四");
    when(userMapper.selectById(202L)).thenReturn(user);

    when(ingestionService.reingest(eq("doc-102"), any()))
        .thenThrow(DependencyUnavailableException.circuitOpen("embedding"));

    scheduler.tick();

    // 保持 PENDING，更新 errorMessage
    ArgumentCaptor<UploadedFileEntity> captor = ArgumentCaptor.forClass(UploadedFileEntity.class);
    verify(uploadedFileMapper).updateById(captor.capture());
    UploadedFileEntity updated = captor.getValue();
    assertThat(updated.getStatus()).isEqualTo("PENDING");
    assertThat(updated.getErrorMessage()).contains("依赖熔断中");
    verify(recoveryPolicy, never()).onPermanentFailure(any(), any());
  }

  @Test
  @DisplayName("契约 3：授权拒绝应转 FAILED 终态")
  void tickReingestFailsWithSecurityExceptionShouldSetFailed() {
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_ENABLED), eq(Boolean.class), any()))
        .thenReturn(true);

    UploadedFileEntity file = new UploadedFileEntity();
    file.setId(103L);
    file.setDocumentId("doc-103");
    file.setUserId("203");
    file.setStatus("PENDING");

    when(uploadedFileMapper.selectList(any())).thenReturn(List.of(file));

    UserEntity user = new UserEntity();
    user.setId(203L);
    user.setRoleId(3L);
    user.setDeptId(10L);
    user.setRealName("王五");
    when(userMapper.selectById(203L)).thenReturn(user);

    when(ingestionService.reingest(eq("doc-103"), any()))
        .thenThrow(new SecurityException("无知识库写入权限"));

    scheduler.tick();

    verify(recoveryPolicy).onPermanentFailure(eq(file), any(SecurityException.class));
  }

  @Test
  @DisplayName("契约 3：上传者不存在或身份不完整应转 FAILED 终态")
  void tickOperatorMissingOrIncompleteShouldSetFailed() {
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_ENABLED), eq(Boolean.class), any()))
        .thenReturn(true);

    UploadedFileEntity fileMissing = new UploadedFileEntity();
    fileMissing.setId(104L);
    fileMissing.setDocumentId("doc-104");
    fileMissing.setUserId("9999");
    fileMissing.setStatus("PENDING");

    when(uploadedFileMapper.selectList(any())).thenReturn(List.of(fileMissing));
    when(userMapper.selectById(9999L)).thenReturn(null);

    scheduler.tick();

    verify(ingestionService, never()).reingest(anyString(), any());
    verify(recoveryPolicy).onPermanentFailure(eq(fileMissing), any());
  }

  @Test
  @DisplayName("契约 3：单篇失败不中断本轮处理")
  void tickSingleFailureDoesNotBreakBatch() {
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_ENABLED), eq(Boolean.class), any()))
        .thenReturn(true);

    UploadedFileEntity file1 = new UploadedFileEntity();
    file1.setId(105L);
    file1.setDocumentId("doc-105");
    file1.setUserId("205");
    file1.setStatus("PENDING");

    UploadedFileEntity file2 = new UploadedFileEntity();
    file2.setId(106L);
    file2.setDocumentId("doc-106");
    file2.setUserId("206");
    file2.setStatus("PENDING");

    when(uploadedFileMapper.selectList(any())).thenReturn(List.of(file1, file2));

    UserEntity user = new UserEntity();
    user.setId(205L);
    user.setRoleId(1L);
    user.setDeptId(10L);
    when(userMapper.selectById(205L)).thenReturn(user);

    UserEntity user2 = new UserEntity();
    user2.setId(206L);
    user2.setRoleId(1L);
    user2.setDeptId(10L);
    when(userMapper.selectById(206L)).thenReturn(user2);

    // 第一篇失败（权限拒绝）
    when(ingestionService.reingest(eq("doc-105"), any())).thenThrow(new SecurityException("无权限"));

    scheduler.tick();

    // 第二篇仍应被调用
    verify(ingestionService).reingest(eq("doc-105"), any());
    verify(ingestionService).reingest(eq("doc-106"), any());
  }

  @Test
  @DisplayName("配置解析：非法值/缺失值回退安全默认值")
  void configResolutionFallback() {
    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_ENABLED), eq(Boolean.class), any()))
        .thenReturn(null);
    assertThat(scheduler.isReplayEnabled()).isFalse();

    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_BATCH_SIZE), eq(Integer.class), any()))
        .thenReturn(-1);
    assertThat(scheduler.resolveBatchSize()).isEqualTo(5);

    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_BATCH_SIZE), eq(Integer.class), any()))
        .thenReturn(100);
    assertThat(scheduler.resolveBatchSize()).isEqualTo(5);

    when(dynamicConfigService.get(
            eq(IngestionReplayScheduler.KEY_REPLAY_BATCH_SIZE), eq(Integer.class), any()))
        .thenReturn(20);
    assertThat(scheduler.resolveBatchSize()).isEqualTo(20);
  }

  @Test
  @DisplayName("注册表：DynamicConfigKeyRegistry 应包含 rag.ingest 2 个配置键定义")
  void registryRegistersRagIngestKeys() {
    com.slz.crm.platform.config.DynamicConfigKeyRegistry registry =
        new com.slz.crm.platform.config.DynamicConfigKeyRegistry(
            new com.fasterxml.jackson.databind.ObjectMapper());

    var enabledDef = registry.definitionOf("rag.ingest.replay-enabled");
    assertThat(enabledDef).isPresent();
    assertThat(enabledDef.get().type())
        .isEqualTo(com.slz.crm.platform.config.ConfigValueType.BOOLEAN);
    assertThat(enabledDef.get().defaultValue()).isEqualTo("false");

    var batchDef = registry.definitionOf("rag.ingest.replay-batch-size");
    assertThat(batchDef).isPresent();
    assertThat(batchDef.get().type())
        .isEqualTo(com.slz.crm.platform.config.ConfigValueType.INTEGER);
    assertThat(batchDef.get().defaultValue()).isEqualTo("5");
    assertThat(batchDef.get().minValue()).isEqualTo("1");
    assertThat(batchDef.get().maxValue()).isEqualTo("50");

    var ingestDefs = registry.byNamespace("rag.ingest");
    assertThat(ingestDefs).hasSize(2);
  }
}
