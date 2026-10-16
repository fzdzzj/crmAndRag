package com.slz.crm.unit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.knowledge.ingestion.BusinessActivityIngestionService;
import com.slz.crm.knowledge.ingestion.ContactTaskIngestionService;
import com.slz.crm.knowledge.ingestion.ContractIngestionService;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.knowledge.ingestion.SalesOpportunityIngestionService;
import com.slz.crm.server.controller.KnowledgeAdminController;
import com.slz.crm.server.service.KnowledgeAdminService;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * KnowledgeAdminController 向量运维统一收口端点契约（unify-vector-ops-endpoints 任务组 3）。
 *
 * <p>单测全 mock 各实体回埋服务（不装配 EmbeddingService / 向量库），零真实重嵌入；覆盖三件事： ① 枚举校验：五实体合法返回 2xx，非法实体返回 400
 * 且不触任何服务；② 委托调用断言：verify 各实体 reingestAll 被正确委托；③ 权限锚：两个端点都挂
 * {@code @RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900)} （复用既有常量，零新增）。
 */
class KnowledgeAdminSyncEndpointTest {

  private final KnowledgeAdminService service = mock(KnowledgeAdminService.class);
  private final CustomerIngestionService customerService = mock(CustomerIngestionService.class);
  private final ContractIngestionService contractService = mock(ContractIngestionService.class);
  private final ContactTaskIngestionService contactTaskService =
      mock(ContactTaskIngestionService.class);
  private final BusinessActivityIngestionService businessActivityService =
      mock(BusinessActivityIngestionService.class);
  private final SalesOpportunityIngestionService salesOpportunityService =
      mock(SalesOpportunityIngestionService.class);

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(
              new KnowledgeAdminController(
                  service,
                  customerService,
                  contractService,
                  contactTaskService,
                  businessActivityService,
                  salesOpportunityService))
          .build();

  @Test
  @DisplayName("POST /knowledge/admin/sync/customer 委托客户回埋服务（2xx + 计数透传）")
  void syncCustomerDelegates() throws Exception {
    when(customerService.reingestAll())
        .thenReturn(new CustomerIngestionService.ReingestResult(10, 9, 1));
    mockMvc
        .perform(post("/knowledge/admin/sync/customer"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.data.total").value(10))
        .andExpect(jsonPath("$.data.success").value(9))
        .andExpect(jsonPath("$.data.failure").value(1));
    verify(customerService).reingestAll();
  }

  @Test
  @DisplayName("POST /knowledge/admin/sync/contract 委托合同回埋服务")
  void syncContractDelegates() throws Exception {
    when(contractService.reingestAll())
        .thenReturn(new ContractIngestionService.ReingestResult(20, 18, 2));
    mockMvc
        .perform(post("/knowledge/admin/sync/contract"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.data.success").value(18));
    verify(contractService).reingestAll();
  }

  @Test
  @DisplayName("POST /knowledge/admin/sync/contactTask 委托跟进任务回埋服务")
  void syncContactTaskDelegates() throws Exception {
    when(contactTaskService.reingestAll())
        .thenReturn(new ContactTaskIngestionService.ReingestResult(30, 30, 0));
    mockMvc
        .perform(post("/knowledge/admin/sync/contactTask"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.data.failure").value(0));
    verify(contactTaskService).reingestAll();
  }

  @Test
  @DisplayName("POST /knowledge/admin/sync/businessActivity 委托商业活动回埋服务")
  void syncBusinessActivityDelegates() throws Exception {
    when(businessActivityService.reingestAll())
        .thenReturn(new BusinessActivityIngestionService.ReingestResult(7, 5, 2));
    mockMvc
        .perform(post("/knowledge/admin/sync/businessActivity"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.data.success").value(5));
    verify(businessActivityService).reingestAll();
  }

  @Test
  @DisplayName("POST /knowledge/admin/sync/salesOpportunity 委托销售商机回埋服务")
  void syncSalesOpportunityDelegates() throws Exception {
    when(salesOpportunityService.reingestAll())
        .thenReturn(new SalesOpportunityIngestionService.ReingestResult(12, 12, 0));
    mockMvc
        .perform(post("/knowledge/admin/sync/salesOpportunity"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.data.total").value(12));
    verify(salesOpportunityService).reingestAll();
  }

  @Test
  @DisplayName("非法 entity 返回 400 且不触任何回埋服务")
  void invalidEntityReturnsBadRequest() throws Exception {
    mockMvc
        .perform(post("/knowledge/admin/sync/unknownEntity"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(50002));
    verifyNoInteractions(
        customerService,
        contractService,
        contactTaskService,
        businessActivityService,
        salesOpportunityService);
  }

  @Test
  @DisplayName("POST /knowledge/admin/sync/all 依枚举序逐个委托五实体回埋服务")
  void syncAllDelegatesToAllFiveServices() throws Exception {
    when(customerService.reingestAll())
        .thenReturn(new CustomerIngestionService.ReingestResult(10, 9, 1));
    when(contractService.reingestAll())
        .thenReturn(new ContractIngestionService.ReingestResult(20, 18, 2));
    when(contactTaskService.reingestAll())
        .thenReturn(new ContactTaskIngestionService.ReingestResult(30, 30, 0));
    when(businessActivityService.reingestAll())
        .thenReturn(new BusinessActivityIngestionService.ReingestResult(7, 5, 2));
    when(salesOpportunityService.reingestAll())
        .thenReturn(new SalesOpportunityIngestionService.ReingestResult(12, 12, 0));
    mockMvc
        .perform(post("/knowledge/admin/sync/all"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.data.customer.success").value(9))
        .andExpect(jsonPath("$.data.contract.total").value(20))
        .andExpect(jsonPath("$.data.contactTask.failure").value(0))
        .andExpect(jsonPath("$.data.businessActivity.success").value(5))
        .andExpect(jsonPath("$.data.salesOpportunity.total").value(12));
    verify(customerService).reingestAll();
    verify(contractService).reingestAll();
    verify(contactTaskService).reingestAll();
    verify(businessActivityService).reingestAll();
    verify(salesOpportunityService).reingestAll();
  }

  @Test
  @DisplayName("权限锚：sync/{entity} 与 sync/all 均挂 @RequirePermission(900) 复用既有常量")
  void syncEndpointsCarryRequirePermission900() throws Exception {
    Method syncEntity =
        KnowledgeAdminController.class.getDeclaredMethod(
            "syncEntity", String.class, HttpServletResponse.class);
    Method syncAll = KnowledgeAdminController.class.getDeclaredMethod("syncAll");
    assertRequirePermission900(syncEntity);
    assertRequirePermission900(syncAll);
  }

  private static void assertRequirePermission900(Method method) {
    RequirePermission annotation = method.getAnnotation(RequirePermission.class);
    assertNotNull(annotation, method.getName() + " 缺 @RequirePermission");
    assertEquals(
        PermissionOperates.KNOWLEDGE_ADMIN_MANAGE,
        annotation.value(),
        method.getName() + " 应复用 KNOWLEDGE_ADMIN_MANAGE(900)，零新增权限常量");
  }
}
