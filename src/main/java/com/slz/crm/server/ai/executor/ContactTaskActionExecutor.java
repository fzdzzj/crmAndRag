package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.dto.ai.AiContactTaskDraftPayloadDTO;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.ContactTaskService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 新增跟进任务执行器（CREATE_CONTACT_TASK） 委托 ContactTaskService.create()，companyId
 * 已由校验层解析，任务主题/状态/优先级兜底由该服务内置 extend-ai-draft-and-eval 任务 1.2
 */
@Slf4j
@Component
public class ContactTaskActionExecutor implements AiActionExecutor {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_CONTACT_TASK.getValue();

  private static final DateTimeFormatter DT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Autowired private ContactTaskService contactTaskService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 执行器混调service+objectMapper多源，失败包装业务异常上抛
  public AiExecutionResult execute(String payloadJson) {
    try {
      AiContactTaskDraftPayloadDTO payload =
          objectMapper.readValue(payloadJson, AiContactTaskDraftPayloadDTO.class);

      ContactTaskDTO dto = new ContactTaskDTO();
      dto.setCompanyId(payload.getCompanyId());
      dto.setContactId(payload.getContactId());
      dto.setOpportunityId(payload.getOpportunityId());
      dto.setTaskTitle(payload.getTaskTitle());
      dto.setTaskContent(payload.getTaskContent());
      dto.setTaskType(payload.getTaskType());
      if (payload.getStartTime() != null && !payload.getStartTime().isBlank()) {
        dto.setStartTime(LocalDateTime.parse(payload.getStartTime(), DT_FMT));
      }
      if (payload.getEndTime() != null && !payload.getEndTime().isBlank()) {
        dto.setEndTime(LocalDateTime.parse(payload.getEndTime(), DT_FMT));
      }
      dto.setPriorityStr(payload.getPriorityStr());
      dto.setStatusStr(payload.getStatusStr());
      dto.setAssigneeId(payload.getAssigneeId());

      boolean created = Boolean.TRUE.equals(contactTaskService.create(dto));
      if (!created) {
        throw new IllegalStateException("新增跟进任务失败");
      }

      Map<String, Object> resultData = new java.util.HashMap<>();

      resultData.put("created", "contactTask");

      resultData.put("taskTitle", payload.getTaskTitle());

      resultData.put("companyId", payload.getCompanyId());

      return AiExecutionResult.of(objectMapper.writeValueAsString(resultData));

    } catch (Exception e) {
      throw new RuntimeException("执行新增跟进任务失败: " + e.getMessage(), e);
    }
  }
}
