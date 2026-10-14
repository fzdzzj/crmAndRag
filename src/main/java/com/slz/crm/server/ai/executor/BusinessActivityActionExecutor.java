package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.ai.AiBusinessActivityDraftPayloadDTO;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.BusinessActivityService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 新增业务活动执行器（CREATE_BUSINESS_ACTIVITY） 委托 BusinessActivityService.create()，opportunityId
 * 已由校验层解析；仅主实体 create，addContacts/addUsers 关联子操作不入卡（裁决假设 5） extend-ai-draft-and-eval 任务 2.2
 */
@Slf4j
@Component
public class BusinessActivityActionExecutor implements AiActionExecutor {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_BUSINESS_ACTIVITY.getValue();

  private static final DateTimeFormatter DT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Autowired private BusinessActivityService businessActivityService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 执行器混调service+objectMapper多源，失败包装业务异常上抛
  public AiExecutionResult execute(String payloadJson) {
    try {
      AiBusinessActivityDraftPayloadDTO payload =
          objectMapper.readValue(payloadJson, AiBusinessActivityDraftPayloadDTO.class);

      BusinessActivityDTO dto = new BusinessActivityDTO();
      dto.setActivityTitle(payload.getActivityTitle());
      dto.setActivityContent(payload.getActivityContent());
      // 活动时间缺省当天
      if (payload.getActivityTime() != null && !payload.getActivityTime().isBlank()) {
        dto.setActivityTime(LocalDateTime.parse(payload.getActivityTime(), DT_FMT));
      } else {
        dto.setActivityTime(LocalDateTime.now());
      }
      dto.setActivityType(payload.getActivityType());
      dto.setActivityDuration(payload.getActivityDuration());
      dto.setOpportunityId(payload.getOpportunityId());
      dto.setRemark(payload.getRemark());

      boolean created = Boolean.TRUE.equals(businessActivityService.create(dto));
      if (!created) {
        throw new IllegalStateException("新增业务活动失败");
      }

      Map<String, Object> resultData = new java.util.HashMap<>();

      resultData.put("created", "businessActivity");

      resultData.put("activityTitle", payload.getActivityTitle());

      resultData.put("opportunityId", payload.getOpportunityId());

      return AiExecutionResult.of(objectMapper.writeValueAsString(resultData));

    } catch (Exception e) {
      throw new RuntimeException("执行新增业务活动失败: " + e.getMessage(), e);
    }
  }
}
