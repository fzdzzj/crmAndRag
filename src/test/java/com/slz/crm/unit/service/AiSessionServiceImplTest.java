package com.slz.crm.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.server.ai.AiChatImageContextCache;
import com.slz.crm.server.service.PendingActionService;
import com.slz.crm.server.service.impl.AiSessionServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 离线验证：归档成功（提交后）才释放图片上下文缓存，提前返回或写失败不释放。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话归档释放图片上下文缓存")
class AiSessionServiceImplTest {

  @Mock private PendingActionService pendingActionService;

  private AiChatImageContextCache cache;

  private AiSessionServiceImpl service;

  @BeforeEach
  void setUp() {
    cache = new AiChatImageContextCache();
    service = spy(new AiSessionServiceImpl());
    ReflectionTestUtils.setField(service, "pendingActionService", pendingActionService);
    ReflectionTestUtils.setField(service, "imageContextCache", cache);
  }

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void archive_success_evictsOnlyAfterBothWritesAndOnlyThatSession() {
    AiSessionEntity entity = activeSession(9L);
    stubOwnedSession(9L, entity);
    doReturn(true).when(service).updateById(entity);
    cache.put(9L, "hash1", "问题1", "摘要9", new float[] {0.1F});
    cache.put(10L, "hash2", "问题2", "摘要10", new float[] {0.2F});
    AtomicBoolean cachePresentAtCancelTime = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              cachePresentAtCancelTime.set(cache.get(9L, "hash1", "问题1").isPresent());
              return null;
            })
        .when(pendingActionService)
        .cancelBySessionId(9L);

    service.archiveSession(9L, 7L);

    assertThat(cachePresentAtCancelTime).isTrue();
    assertThat(cache.get(9L, "hash1", "问题1")).isEmpty();
    assertThat(cache.get(10L, "hash2", "问题2")).isPresent();
  }

  @Test
  void archive_success_withTransactionSync_evictsOnlyOnAfterCommit() {
    AiSessionEntity entity = activeSession(9L);
    stubOwnedSession(9L, entity);
    doReturn(true).when(service).updateById(entity);
    cache.put(9L, "hash1", "问题1", "摘要9", null);
    cache.put(10L, "hash2", "问题2", "摘要10", null);
    TransactionSynchronizationManager.initSynchronization();

    service.archiveSession(9L, 7L);

    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
    List<TransactionSynchronization> synchronizations =
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
    assertThat(synchronizations).hasSize(1);
    synchronizations.forEach(TransactionSynchronization::afterCommit);
    assertThat(cache.get(9L, "hash1", "问题1")).isEmpty();
    assertThat(cache.get(10L, "hash2", "问题2")).isPresent();
  }

  @Test
  void archive_sessionMissing_returnsWithoutEvictingAnyCache() {
    doReturn(null).when(service).getOwnedSession(9L, 7L);
    cache.put(9L, "hash1", "问题1", "摘要9", null);
    cache.put(10L, "hash2", "问题2", "摘要10", null);

    service.archiveSession(9L, 7L);

    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
    assertThat(cache.get(10L, "hash2", "问题2")).isPresent();
    verify(pendingActionService, never()).cancelBySessionId(any());
  }

  @Test
  void archive_alreadyArchived_returnsWithoutEvictingAnyCache() {
    AiSessionEntity archived = activeSession(9L);
    archived.setStatus(0);
    stubOwnedSession(9L, archived);
    cache.put(9L, "hash1", "问题1", "摘要9", null);
    cache.put(10L, "hash2", "问题2", "摘要10", null);

    service.archiveSession(9L, 7L);

    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
    assertThat(cache.get(10L, "hash2", "问题2")).isPresent();
    verify(service, never()).updateById(any(AiSessionEntity.class));
    verify(pendingActionService, never()).cancelBySessionId(any());
  }

  @Test
  void archive_updateFails_doesNotEvict() {
    AiSessionEntity entity = activeSession(9L);
    stubOwnedSession(9L, entity);
    doThrow(new RuntimeException("update failed")).when(service).updateById(entity);
    cache.put(9L, "hash1", "问题1", "摘要9", null);

    assertThatRuntimeException()
        .isThrownBy(() -> service.archiveSession(9L, 7L))
        .withMessage("update failed");

    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
    verify(pendingActionService, never()).cancelBySessionId(any());
  }

  @Test
  void archive_cancelFails_doesNotEvict() {
    AiSessionEntity entity = activeSession(9L);
    stubOwnedSession(9L, entity);
    doReturn(true).when(service).updateById(entity);
    doThrow(new RuntimeException("cancel failed")).when(pendingActionService).cancelBySessionId(9L);
    cache.put(9L, "hash1", "问题1", "摘要9", null);

    assertThatRuntimeException()
        .isThrownBy(() -> service.archiveSession(9L, 7L))
        .withMessage("cancel failed");

    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
  }

  @Test
  void archive_cancelFails_withTransactionSync_registersNoEviction() {
    AiSessionEntity entity = activeSession(9L);
    stubOwnedSession(9L, entity);
    doReturn(true).when(service).updateById(entity);
    doThrow(new RuntimeException("cancel failed")).when(pendingActionService).cancelBySessionId(9L);
    cache.put(9L, "hash1", "问题1", "摘要9", null);
    TransactionSynchronizationManager.initSynchronization();

    assertThatRuntimeException()
        .isThrownBy(() -> service.archiveSession(9L, 7L))
        .withMessage("cancel failed");

    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    assertThat(cache.get(9L, "hash1", "问题1")).isPresent();
  }

  private AiSessionEntity activeSession(Long id) {
    AiSessionEntity entity = new AiSessionEntity();
    entity.setId(id);
    entity.setStatus(1);
    return entity;
  }

  private void stubOwnedSession(Long sessionId, AiSessionEntity entity) {
    doReturn(entity).when(service).getOwnedSession(sessionId, 7L);
  }
}
