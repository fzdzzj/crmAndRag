package com.slz.crm.unit.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.server.controller.KnowledgeAdminController;
import com.slz.crm.server.service.KbAdminRetrievalStrategyWriteService;
import com.slz.crm.server.service.KnowledgeAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * KnowledgeAdminController per-KB 策略公开面契约（add-per-kb-retrieval-strategy-override 任务 4.1）。
 *
 * <p>红测试先行：在基线 master@9379420 上，四个策略端点未实施——对既有 {@code /knowledge} 前缀请求策略路径返回 404（端点未实现）；实现后逐端点返回
 * 2xx。本门禁守住「端点必须存在」这一最低不可变契约，防止收缩后把端点删没。
 *
 * <p>这里用 standalone MockMvc（只挂 KnowledgeAdminController + mock 写服务），不依赖 Spring 全上下文；
 * 正反权限（900/403）另由 {@code KbRetrievalStrategyIT}（Testcontainers）覆盖。
 */
class KnowledgeAdminStrategyEndpointTest {

  private final KnowledgeAdminService service = mock(KnowledgeAdminService.class);
  private final KbAdminRetrievalStrategyWriteService writeService =
      mock(KbAdminRetrievalStrategyWriteService.class);
  private final KnowledgeAdminController controller = new KnowledgeAdminController(service);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

  @BeforeEach
  void wireWriteService() {
    // 4 个新端点的写服务经字段注入；standalone 构造无法绕 Spring，故用反射注入 mock。
    ReflectionTestUtils.setField(controller, "kbStrategyWriteService", writeService);
  }

  @Test
  @DisplayName("GET /knowledge/strategies 存在（红锚：未实施 404 → 实施后 2xx）")
  void listEndpointExists() throws Exception {
    mockMvc
        .perform(get("/knowledge/strategies").param("kbId", "1"))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  @DisplayName("PUT /knowledge/strategies/{key} 存在（红锚：未实施 404 → 实施后 2xx）")
  void putEndpointExists() throws Exception {
    mockMvc
        .perform(put("/knowledge/strategies/rag.retrieval.topK").param("kbId", "1").content("8"))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  @DisplayName("DELETE /knowledge/strategies/{key} 存在（红锚：未实施 404 → 实施后 2xx）")
  void deleteEndpointExists() throws Exception {
    mockMvc
        .perform(delete("/knowledge/strategies/rag.retrieval.topK").param("kbId", "1"))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  @DisplayName("POST /knowledge/strategies/{key}/rollback 存在（红锚：未实施 404 → 实施后 2xx）")
  void rollbackEndpointExists() throws Exception {
    mockMvc
        .perform(
            post("/knowledge/strategies/rag.retrieval.topK/rollback")
                .param("kbId", "1")
                .param("version", "1"))
        .andExpect(status().is2xxSuccessful());
  }
}
