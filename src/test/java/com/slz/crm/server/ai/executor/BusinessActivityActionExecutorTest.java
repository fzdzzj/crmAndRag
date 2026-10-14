package com.slz.crm.server.ai.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.server.service.BusinessActivityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** BusinessActivityActionExecutor 单测：正常落库（mock service）+ 业务失败（extend-ai-draft-and-eval 任务 2.4） */
@ExtendWith(MockitoExtension.class)
@DisplayName("新增业务活动执行器")
class BusinessActivityActionExecutorTest {

  @Mock private BusinessActivityService businessActivityService;

  @InjectMocks private BusinessActivityActionExecutor executor;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("正常落库：字段映射到 BusinessActivityDTO 并调用 create")
  void successCreatesViaService() throws Exception {
    when(businessActivityService.create(any())).thenReturn(true);

    AiExecutionResult result =
        executor.execute(
            "{\"opportunityId\":5,\"activityTitle\":\"客户需求调研\",\"activityContent\":\"收集预算\","
                + "\"activityTime\":\"2026-10-15 10:00:00\",\"activityType\":\"电话沟通\","
                + "\"activityDuration\":30,\"remark\":\"跟进\"}");

    ArgumentCaptor<BusinessActivityDTO> captor = ArgumentCaptor.forClass(BusinessActivityDTO.class);
    verify(businessActivityService).create(captor.capture());
    BusinessActivityDTO dto = captor.getValue();
    assertEquals(5L, dto.getOpportunityId());
    assertEquals("客户需求调研", dto.getActivityTitle());
    assertEquals("收集预算", dto.getActivityContent());
    assertEquals("电话沟通", dto.getActivityType());
    assertEquals(30, dto.getActivityDuration());
    assertEquals("跟进", dto.getRemark());
    assertEquals(
        java.time.LocalDateTime.parse(
            "2026-10-15 10:00:00",
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        dto.getActivityTime());

    assertEquals(
        "businessActivity", objectMapper.readTree(result.getResult()).get("created").asText());
  }

  @Test
  @DisplayName("业务失败（create 返回 false）抛出且无引用")
  void businessFailureThrows() {
    when(businessActivityService.create(any())).thenReturn(false);

    assertThrows(
        RuntimeException.class,
        () -> executor.execute("{\"opportunityId\":5,\"activityTitle\":\"客户需求调研\"}"));
  }
}
