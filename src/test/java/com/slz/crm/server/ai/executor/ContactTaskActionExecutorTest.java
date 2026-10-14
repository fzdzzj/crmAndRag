package com.slz.crm.server.ai.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.server.service.ContactTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ContactTaskActionExecutor 单测：正常落库（mock service）+ 业务失败（extend-ai-draft-and-eval 任务 1.4） */
@ExtendWith(MockitoExtension.class)
@DisplayName("新增跟进任务执行器")
class ContactTaskActionExecutorTest {

  @Mock private ContactTaskService contactTaskService;

  @InjectMocks private ContactTaskActionExecutor executor;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("正常落库：字段映射到 ContactTaskDTO 并调用 create")
  void successCreatesViaService() throws Exception {
    when(contactTaskService.create(any())).thenReturn(true);

    AiExecutionResult result =
        executor.execute(
            "{\"companyId\":5,\"taskTitle\":\"下周拜访\",\"taskContent\":\"约见采购总监\","
                + "\"startTime\":\"2026-10-15 09:00:00\",\"endTime\":\"2026-10-15 11:00:00\","
                + "\"priorityStr\":\"高\",\"assigneeId\":7}");

    ArgumentCaptor<ContactTaskDTO> captor = ArgumentCaptor.forClass(ContactTaskDTO.class);
    verify(contactTaskService).create(captor.capture());
    ContactTaskDTO dto = captor.getValue();
    assertEquals(5L, dto.getCompanyId());
    assertEquals("下周拜访", dto.getTaskTitle());
    assertEquals("约见采购总监", dto.getTaskContent());
    assertEquals("高", dto.getPriorityStr());
    assertEquals(7L, dto.getAssigneeId());
    assertEquals(
        java.time.LocalDateTime.parse(
            "2026-10-15 09:00:00",
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        dto.getStartTime());
    assertEquals(
        java.time.LocalDateTime.parse(
            "2026-10-15 11:00:00",
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        dto.getEndTime());

    assertEquals("contactTask", objectMapper.readTree(result.getResult()).get("created").asText());
  }

  @Test
  @DisplayName("业务失败（create 返回 false）抛出且无引用")
  void businessFailureThrows() {
    when(contactTaskService.create(any())).thenReturn(false);

    assertThrows(
        RuntimeException.class, () -> executor.execute("{\"companyId\":5,\"taskTitle\":\"下周拜访\"}"));
  }
}
