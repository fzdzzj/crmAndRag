package com.slz.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.platform.config.controller.ConfigRollbackRequest;
import com.slz.crm.platform.config.controller.DynamicConfigAdminController;
import com.slz.crm.platform.config.service.DynamicConfigAdminService;
import com.slz.crm.platform.contract.PlatformErrorCode;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * 动态配置管理接口测试：路由、统一 Result 封装、越权/校验错误经 GlobalExceptionHandler 映射为平台错误码。 （鉴权判定在服务层，本测试只验证 HTTP
 * 层委托与错误映射。）
 */
@ExtendWith(MockitoExtension.class)
class DynamicConfigAdminControllerTest {

  @Mock private DynamicConfigAdminService adminService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DynamicConfigAdminController(adminService))
            .setControllerAdvice(new com.slz.crm.server.handler.GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("列表接口返回统一 Result（code=1）")
  void listReturnsResult() throws Exception {
    when(adminService.listItems("rag.retrieval"))
        .thenReturn(
            List.of(
                new ConfigItemView(
                    "rag.retrieval.topK",
                    "rag.retrieval",
                    "INTEGER",
                    "8",
                    "5",
                    "检索 topK",
                    1,
                    null,
                    "user:1",
                    false,
                    false,
                    "1",
                    "100",
                    null)));
    mockMvc
        .perform(get("/platform/config/items").param("namespace", "rag.retrieval"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data[0].key").value("rag.retrieval.topK"));
  }

  @Test
  @DisplayName("写接口成功返回 code=1")
  void updateSucceeds() throws Exception {
    when(adminService.updateValue(eq("rag.retrieval.topK"), eq("6"), eq("收紧")))
        .thenReturn(
            new ConfigItemView(
                "rag.retrieval.topK",
                "rag.retrieval",
                "INTEGER",
                "6",
                "5",
                "检索 topK",
                2,
                null,
                "user:1",
                false,
                false,
                "1",
                "100",
                null));
    mockMvc
        .perform(
            post("/platform/config/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"rag.retrieval.topK\",\"value\":\"6\",\"remark\":\"收紧\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.version").value(2));
  }

  @Test
  @DisplayName("越权写：服务层抛 FORBIDDEN → 统一错误码 96005")
  void forbiddenMappedToPlatformCode() throws Exception {
    when(adminService.updateValue(any(), any(), any()))
        .thenThrow(
            new com.slz.crm.common.exiception.ServiceException(
                PlatformErrorCode.FORBIDDEN.getCode(), PlatformErrorCode.FORBIDDEN.getMessage()));
    mockMvc
        .perform(
            post("/platform/config/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"rag.retrieval.topK\",\"value\":\"6\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96005));
  }

  @Test
  @DisplayName("非法值：服务层抛 VALIDATION → 统一错误码 96007")
  void validationMappedToPlatformCode() throws Exception {
    when(adminService.updateValue(any(), any(), any()))
        .thenThrow(
            new com.slz.crm.common.exiception.ServiceException(
                PlatformErrorCode.VALIDATION.getCode(), "取值不能小于 1"));
    mockMvc
        .perform(
            post("/platform/config/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"rag.retrieval.topK\",\"value\":\"-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96007));
  }

  @Test
  @DisplayName("回滚目标版本非法（0/负数）在控制器层直接拒绝，不触达服务层")
  void rollbackInvalidVersionRejectedAtController() throws Exception {
    ConfigRollbackRequest request = new ConfigRollbackRequest();
    request.setVersion(0);
    mockMvc
        .perform(
            post("/platform/config/items/rag.retrieval.topK/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96007));
    verify(adminService, never())
        .rollback(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("回滚成功走服务层并返回 code=1")
  void rollbackSucceeds() throws Exception {
    when(adminService.rollback(eq("rag.retrieval.topK"), eq(1), eq(null)))
        .thenReturn(
            new ConfigItemView(
                "rag.retrieval.topK",
                "rag.retrieval",
                "INTEGER",
                "8",
                "5",
                "检索 topK",
                3,
                null,
                "user:1",
                false,
                false,
                "1",
                "100",
                null));
    mockMvc
        .perform(
            post("/platform/config/items/rag.retrieval.topK/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.version").value(3));
  }

  @Test
  @DisplayName("软删恢复默认：DELETE 路由委托服务层")
  void deleteOverrideSucceeds() throws Exception {
    when(adminService.deleteOverride(eq("rag.retrieval.topK"), eq(null)))
        .thenReturn(
            new ConfigItemView(
                "rag.retrieval.topK",
                "rag.retrieval",
                "INTEGER",
                null,
                "5",
                "检索 topK",
                2,
                null,
                "user:1",
                true,
                false,
                "1",
                "100",
                null));
    mockMvc
        .perform(delete("/platform/config/items/rag.retrieval.topK"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.deleted").value(true));
  }

  @Test
  @DisplayName("手动刷新缓存返回条目数")
  void refreshCacheSucceeds() throws Exception {
    when(adminService.refreshCache()).thenReturn(3);
    mockMvc
        .perform(post("/platform/config/cache/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data").value(3));
  }

  // ==================== add-dynamic-config-key-tier-acl 红锚（任务 1.1/1.2/3.1） ====================

  @Test
  @DisplayName("红锚→绿：PermissionOperates 须含 PLATFORM_DYNAMIC_CONFIG_MANAGE(608)")
  void platformDynamicConfigManageConstantExists() {
    PermissionOperates constant = PermissionOperates.valueOf("PLATFORM_DYNAMIC_CONFIG_MANAGE");
    assertThat(constant.getId()).isEqualTo(608L);
  }

  @Test
  @DisplayName("红锚→绿：7 端点全部挂 @RequirePermission(PLATFORM_DYNAMIC_CONFIG_MANAGE)")
  void allEndpointsAnnotatedWithRequirePermission608() {
    List<Method> endpoints =
        Arrays.stream(DynamicConfigAdminController.class.getDeclaredMethods())
            .filter(
                m ->
                    m.isAnnotationPresent(GetMapping.class)
                        || m.isAnnotationPresent(PostMapping.class)
                        || m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class))
            .toList();
    assertThat(endpoints).hasSize(7);
    for (Method endpoint : endpoints) {
      RequirePermission annotation = endpoint.getAnnotation(RequirePermission.class);
      assertThat(annotation).as("端点 %s 缺 @RequirePermission 注解", endpoint.getName()).isNotNull();
      assertThat(annotation.value())
          .as("端点 %s 注解值应为 PLATFORM_DYNAMIC_CONFIG_MANAGE", endpoint.getName())
          .isEqualTo(PermissionOperates.valueOf("PLATFORM_DYNAMIC_CONFIG_MANAGE"));
    }
  }
}
