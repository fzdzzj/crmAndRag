package com.slz.crm.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.server.controller.BusinessActivityController;
import com.slz.crm.server.handler.GlobalExceptionHandler;
import com.slz.crm.server.service.BusinessActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 关联维护 null-id 前置拦截（fix-backlog-batch2 ⑧）。
 *
 * <p>5 个 DTO 的身份字段挂 {@code @NotNull} 后，写端点在方法体执行前统一被 Bean Validation 拦下：全局 handler 返回 {@code
 * PARAM_FORMAT_ERROR}(50002) + 「参数校验失败」，{@code businessActivityService} 零调用。旧实现要等 service 内
 * list-vs-set 比对（或数据库 NOT NULL 约束）才失败，文案还可能是「关联已存在」这种误导提示。
 *
 * <p>本类同时锁定创建路径的零回归：创建请求本就不带活动 id，{@code BusinessActivityDTO.id} 的必填约束只在 {@code
 * ValidationGroups.Update} 组生效，故创建照旧放行。
 */
@DisplayName("商业活动关联维护 null-id 前置拦截（50002 参数校验失败）")
class BusinessActivityNullIdValidationTest {

  private static final String PARAM_FORMAT_ERROR_CODE = "50002";
  private static final String VALIDATION_FAILED_MSG = "参数校验失败";

  private BusinessActivityService businessActivityService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    BusinessActivityController controller = new BusinessActivityController();
    businessActivityService = mock(BusinessActivityService.class);
    when(businessActivityService.create(any())).thenReturn(true);
    when(businessActivityService.updateList(anyList())).thenReturn(1);
    when(businessActivityService.addUsersToActivity(anyLong(), anyList())).thenReturn(true);
    when(businessActivityService.addContactsToActivity(anyLong(), anyList())).thenReturn(true);
    ReflectionTestUtils.setField(controller, "businessActivityService", businessActivityService);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test
  @DisplayName("create：参与人员列表里 userId 为空 → 50002 参数校验失败，service 零调用")
  void create_nullUserId_rejectedBeforeService() throws Exception {
    mvc.perform(
            json(
                post("/business/activity"),
                "{\"activityTitle\":\"客户拜访\",\"isAddUser\":true,"
                    + "\"userIdList\":[{\"userRole\":\"主讲人\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_FORMAT_ERROR_CODE))
        .andExpect(jsonPath("$.msg").value(VALIDATION_FAILED_MSG));

    verifyNoInteractions(businessActivityService);
  }

  @Test
  @DisplayName("create：参与联系人列表里 contactId 为空 → 50002 参数校验失败，service 零调用")
  void create_nullContactId_rejectedBeforeService() throws Exception {
    mvc.perform(
            json(
                post("/business/activity"),
                "{\"activityTitle\":\"客户拜访\",\"isAddContact\":true,"
                    + "\"contactIdList\":[{\"contactRole\":\"决策者\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_FORMAT_ERROR_CODE))
        .andExpect(jsonPath("$.msg").value(VALIDATION_FAILED_MSG));

    verifyNoInteractions(businessActivityService);
  }

  @Test
  @DisplayName("addUsers：请求列表项 userId 为空 → 50002 参数校验失败，service 零调用")
  void addUsers_nullUserId_rejectedBeforeService() throws Exception {
    mvc.perform(
            json(
                post("/business/activity/1/users"),
                "[{\"userRole\":\"主讲人\"},{\"userRole\":\"陪同人\"}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_FORMAT_ERROR_CODE))
        .andExpect(jsonPath("$.msg").value(VALIDATION_FAILED_MSG));

    verifyNoInteractions(businessActivityService);
  }

  @Test
  @DisplayName("addContacts：请求列表项 contactId 为空 → 50002 参数校验失败，service 零调用")
  void addContacts_nullContactId_rejectedBeforeService() throws Exception {
    mvc.perform(
            json(
                post("/business/activity/1/contacts"),
                "[{\"contactRole\":\"决策者\"},{\"contactRole\":\"参会人\"}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_FORMAT_ERROR_CODE))
        .andExpect(jsonPath("$.msg").value(VALIDATION_FAILED_MSG));

    verifyNoInteractions(businessActivityService);
  }

  @Test
  @DisplayName("update：列表项内 userId 为空 → 50002 参数校验失败，service 零调用")
  void update_nullUserId_rejectedBeforeService() throws Exception {
    mvc.perform(
            json(put("/business/activity"), "[{\"id\":1,\"userIdList\":[{\"userRole\":\"主讲人\"}]}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_FORMAT_ERROR_CODE))
        .andExpect(jsonPath("$.msg").value(VALIDATION_FAILED_MSG));

    verifyNoInteractions(businessActivityService);
  }

  @Test
  @DisplayName("创建零回归：请求体不带活动 id 且列表项身份字段齐全 → 正常进入 service")
  void create_withoutActivityId_stillPassesValidation() throws Exception {
    mvc.perform(
            json(
                post("/business/activity"),
                "{\"activityTitle\":\"客户拜访\",\"isAddUser\":true,\"isAddContact\":true,"
                    + "\"userIdList\":[{\"userId\":7,\"userRole\":\"主讲人\"}],"
                    + "\"contactIdList\":[{\"contactId\":9,\"contactRole\":\"决策者\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));

    verify(businessActivityService).create(any());
  }
}
