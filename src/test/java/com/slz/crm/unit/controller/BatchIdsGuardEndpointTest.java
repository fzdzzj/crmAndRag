package com.slz.crm.unit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.server.controller.BusinessActivityController;
import com.slz.crm.server.controller.CustomerCompanyController;
import com.slz.crm.server.controller.UserController;
import com.slz.crm.server.handler.GlobalExceptionHandler;
import com.slz.crm.server.service.BusinessActivityService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.UserService;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 批量端点统一闸的三种请求形态抽查（fix-backlog-batch3 任务 2.3）。
 *
 * <p>16 个端点里三类形态各取一个代表：①{@code @RequestBody List<Long>}（商业活动批量物理删）；②{@code @RequestParam
 * List<Long>}（用户批量删）；③级联删入口（客户公司批量逻辑删）。三处都必须做到「空集 → 50001、超 500(去重后) → 50007 且文案带上限、合规入参 → 照常下沉
 * service」，且被拦下时 service 零调用。
 */
@DisplayName("批量端点统一闸（@RequestBody / @RequestParam / 级联删三形态）")
class BatchIdsGuardEndpointTest {

  private static final String PARAM_EMPTY_CODE = "50001";
  private static final String PARAM_OUT_OF_RANGE_CODE = "50007";

  @Test
  @DisplayName("@RequestBody 形态（DELETE /business/activity）：空集 50001、501 个 50007、合规下沉 service")
  void requestBodyEndpoint_enforcesGuard() throws Exception {
    BusinessActivityService service = mock(BusinessActivityService.class);
    when(service.deleteByIds(anyList())).thenReturn(2);
    BusinessActivityController controller = new BusinessActivityController();
    ReflectionTestUtils.setField(controller, "businessActivityService", service);
    MockMvc mvc = mvc(controller);

    mvc.perform(delete("/business/activity").contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_EMPTY_CODE));
    verifyNoInteractions(service);

    mvc.perform(
            delete("/business/activity")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tooManyIdsJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_OUT_OF_RANGE_CODE));
    verifyNoInteractions(service);

    mvc.perform(
            delete("/business/activity").contentType(MediaType.APPLICATION_JSON).content("[1,2]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    verify(service).deleteByIds(List.of(1L, 2L));
  }

  @Test
  @DisplayName("@RequestParam 形态（DELETE /user）：501 个 50007、空清单 50001、合规下沉 service")
  void requestParamEndpoint_enforcesGuard() throws Exception {
    UserService service = mock(UserService.class);
    when(service.deleteById(anyList())).thenReturn(true);
    UserController controller = new UserController(service);
    MockMvc mvc = mvc(controller);

    // 空清单直接走方法体（HTTP 侧空串的空值语义由 Spring 绑定决定，不属于本闸口径）
    BaseException emptyEx = assertThrows(BaseException.class, () -> controller.delete(List.of()));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), emptyEx.getCode());
    verifyNoInteractions(service);

    String[] tooMany = LongStream.range(0, 501).mapToObj(String::valueOf).toArray(String[]::new);
    mvc.perform(delete("/user").param("ids", tooMany))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_OUT_OF_RANGE_CODE));
    verifyNoInteractions(service);

    mvc.perform(delete("/user").param("ids", "1", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    verify(service).deleteById(List.of(1L, 2L));
  }

  @Test
  @DisplayName("级联删形态（DELETE /company/logical/batch）：空集 50001、501 个 50007、合规下沉 service")
  void cascadeDeleteEndpoint_enforcesGuard() throws Exception {
    CustomerCompanyService service = mock(CustomerCompanyService.class);
    when(service.batchLogicalDeleteByIds(anyList())).thenReturn(2);
    CustomerCompanyController controller = new CustomerCompanyController();
    ReflectionTestUtils.setField(controller, "customerCompanyService", service);
    MockMvc mvc = mvc(controller);

    mvc.perform(
            delete("/company/logical/batch").contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_EMPTY_CODE));
    verifyNoInteractions(service);

    mvc.perform(
            delete("/company/logical/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tooManyIdsJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_OUT_OF_RANGE_CODE))
        .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("500")));
    verifyNoInteractions(service);

    mvc.perform(
            delete("/company/logical/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[1,2]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    verify(service).batchLogicalDeleteByIds(List.of(1L, 2L));
  }

  /** standalone MockMvc：只挂全局异常处理器，不注册权限拦截器与真实依赖。 */
  private static MockMvc mvc(Object controller) {
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  /** 501 个不同 id 的请求体（去重后 501 > MAX_BATCH_IDS(500)）。 */
  private static String tooManyIdsJson() {
    return LongStream.range(0, 501)
        .mapToObj(String::valueOf)
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }
}
