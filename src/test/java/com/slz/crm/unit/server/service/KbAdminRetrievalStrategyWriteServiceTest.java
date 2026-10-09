package com.slz.crm.unit.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.knowledge.entity.KbRetrievalStrategy;
import com.slz.crm.knowledge.entity.KbRetrievalStrategyHistory;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyService;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyWhitelist;
import com.slz.crm.platform.config.DynamicConfigCache;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.server.mapper.KbRetrievalStrategyHistoryMapper;
import com.slz.crm.server.mapper.KbRetrievalStrategyMapper;
import com.slz.crm.server.service.KbAdminRetrievalStrategyWriteService;
import com.slz.crm.server.service.KbRetrievalStrategyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * per-KB 策略写端服务测试（add-per-kb-retrieval-strategy-override 任务 4.1，红锚转绿）。
 *
 * <p>覆盖：白名单外键拒绝、越界拒绝；PUT 覆盖 canonical 写入 + version +1 + history 审计 + 缓存失效； DELETE 软删回落全局；ROLLBACK
 * 按历史版本写回。
 */
class KbAdminRetrievalStrategyWriteServiceTest {

  private final KbRetrievalStrategyWhitelist whitelist =
      new KbRetrievalStrategyWhitelist(new DynamicConfigKeyRegistry(new ObjectMapper()));
  private final KbRetrievalStrategyMapper mapper = mock(KbRetrievalStrategyMapper.class);
  private final KbRetrievalStrategyHistoryMapper historyMapper =
      mock(KbRetrievalStrategyHistoryMapper.class);
  private final KbRetrievalStrategyService cacheService = mock(KbRetrievalStrategyService.class);
  private final DynamicConfigCache dynamicConfigCache = mock(DynamicConfigCache.class);

  private KbAdminRetrievalStrategyWriteService newService() {
    return new KbAdminRetrievalStrategyWriteService(
        mapper, historyMapper, whitelist, cacheService, dynamicConfigCache);
  }

  @Test
  @DisplayName("PUT：白名单外键拒绝（BaseException）")
  void putRejectsOutOfWhitelist() {
    KbAdminRetrievalStrategyWriteService service = newService();
    BaseException ex =
        assertThrows(
            BaseException.class, () -> service.put(1L, "rag.retrieval.rerank.mode", "llm"));
    assertTrue(ex.getMessage().contains("不在 per-KB 覆盖白名单"));
  }

  @Test
  @DisplayName("PUT：越界值拒绝（topK=999）")
  void putRejectsOutOfRange() {
    KbAdminRetrievalStrategyWriteService service = newService();
    assertThrows(BaseException.class, () -> service.put(1L, "rag.retrieval.topK", "999"));
  }

  @Test
  @DisplayName("PUT：新建覆盖写 canonical + version +1 + history + 缓存失效")
  void putWritesCanonicalVersionHistory() {
    when(mapper.selectOne(any())).thenReturn(null);
    KbAdminRetrievalStrategyWriteService service = newService();
    KbRetrievalStrategyItem item = service.put(1L, "rag.retrieval.topK", "8");
    assertEquals("8", item.effectiveValue());

    ArgumentCaptor<KbRetrievalStrategy> strat = ArgumentCaptor.forClass(KbRetrievalStrategy.class);
    verify(mapper).insert(strat.capture());
    assertEquals("8", strat.getValue().getConfigValue());
    assertEquals(2, strat.getValue().getVersion(), "新建 version 起点 1 +1 = 2");
    assertEquals(1L, strat.getValue().getKbId());

    ArgumentCaptor<KbRetrievalStrategyHistory> hist =
        ArgumentCaptor.forClass(KbRetrievalStrategyHistory.class);
    verify(historyMapper).insert(hist.capture());
    assertEquals("CREATE", hist.getValue().getOperationType());
    verify(cacheService).invalidate(1L, "rag.retrieval.topK");
  }

  @Test
  @DisplayName("PUT：既有覆盖更新 version +1（UPDATE），软删行复活（REVIVE）")
  void putUpdatesExistingAndRevives() {
    // 既有活覆盖 → UPDATE
    KbRetrievalStrategy existing = row(1L, "rag.retrieval.topK", "5", false, 3);
    when(mapper.selectOne(any())).thenReturn(existing);
    KbAdminRetrievalStrategyWriteService service = newService();
    service.put(1L, "rag.retrieval.topK", "9");
    verify(mapper).updateById(any());
    assertEquals(4, existing.getVersion(), "UPDATE version 3+1=4");
    assertEquals("9", existing.getConfigValue());

    // 软删行 → REVIVE
    KbRetrievalStrategy deleted = row(1L, "rag.retrieval.minScore", "0.9", true, 5);
    when(mapper.selectOne(any())).thenReturn(deleted);
    service.put(1L, "rag.retrieval.minScore", "0.3");
    assertFalse(Boolean.TRUE.equals(deleted.getIsDeleted()), "复活必须清软删标记");
    assertEquals(6, deleted.getVersion(), "REVIVE version 5+1=6");
  }

  @Test
  @DisplayName("DELETE：软删回落全局，无活覆盖返回 false")
  void deleteSoftDeletes() {
    KbRetrievalStrategy existing = row(1L, "rag.retrieval.topK", "5", false, 3);
    when(mapper.selectOne(any())).thenReturn(existing);
    KbAdminRetrievalStrategyWriteService service = newService();
    assertTrue(service.delete(1L, "rag.retrieval.topK"));
    assertTrue(Boolean.TRUE.equals(existing.getIsDeleted()));
    verify(cacheService).invalidate(1L, "rag.retrieval.topK");

    when(mapper.selectOne(any())).thenReturn(null);
    assertFalse(service.delete(1L, "rag.retrieval.topK"), "无活覆盖 delete 返回 false");
  }

  @Test
  @DisplayName("ROLLBACK：按历史版本写回当前值")
  void rollbackWritesBackHistoryValue() {
    KbRetrievalStrategy current = row(1L, "rag.retrieval.topK", "9", false, 4);
    when(mapper.selectOne(any())).thenReturn(current);
    KbRetrievalStrategyHistory hist = new KbRetrievalStrategyHistory();
    hist.setKbId(1L);
    hist.setStrategyKey("rag.retrieval.topK");
    hist.setVersion(2);
    hist.setNewValue("6");
    when(historyMapper.selectOne(any())).thenReturn(hist);
    when(mapper.selectOne(any())).thenReturn(current);

    KbAdminRetrievalStrategyWriteService service = newService();
    assertTrue(service.rollback(1L, "rag.retrieval.topK", 2));
    assertEquals("6", current.getConfigValue(), "回滚应写回历史目标版本 new_value");
    assertEquals(5, current.getVersion(), "回滚 version 4+1=5");
    verify(cacheService).invalidate(1L, "rag.retrieval.topK");
  }

  private static KbRetrievalStrategy row(
      Long kbId, String key, String value, boolean deleted, int version) {
    KbRetrievalStrategy e = new KbRetrievalStrategy();
    e.setId(kbId);
    e.setKbId(kbId);
    e.setStrategyKey(key);
    e.setConfigValue(value);
    e.setIsDeleted(deleted);
    e.setVersion(version);
    return e;
  }
}
