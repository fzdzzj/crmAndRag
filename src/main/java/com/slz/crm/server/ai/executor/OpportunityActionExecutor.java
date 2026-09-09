package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.SalesOpportunityDTO;
import com.slz.crm.pojo.dto.ai.AiOpportunityDraftPayloadDTO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.SalesOpportunityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 新增商机执行器（CREATE_OPPORTUNITY）
 * 委托 SalesOpportunityService.create()，companyId 已由校验层解析
 */
@Slf4j
@Component
public class OpportunityActionExecutor implements AiActionExecutor {

    public static final String ACTION_TYPE = ActionTypeEnum.CREATE_OPPORTUNITY.getValue();

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private SalesOpportunityService salesOpportunityService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public AiExecutionResult execute(String payloadJson) {
        try {
            AiOpportunityDraftPayloadDTO payload = objectMapper.readValue(payloadJson, AiOpportunityDraftPayloadDTO.class);

            SalesOpportunityDTO dto = new SalesOpportunityDTO();
            dto.setOpportunityName(payload.getOpportunityName());
            dto.setCompanyId(payload.getCompanyId());
            dto.setAmount(payload.getAmount());
            dto.setSource(payload.getSource());
            dto.setDescription(payload.getDescription());
            if (payload.getExpectedCloseDate() != null && !payload.getExpectedCloseDate().isBlank()) {
                dto.setExpectedCloseDate(LocalDateTime.parse(payload.getExpectedCloseDate(), DT_FMT));
            }

            SalesOpportunityVO created = salesOpportunityService.create(dto);
            if (created == null || created.getId() == null) {
                throw new IllegalStateException("新增商机失败");
            }

            List<AiReferenceCollector.Reference> references = reference(created);

              Map<String, Object> resultData = new java.util.HashMap<>();

              resultData.put("created", "opportunity");

              resultData.put("opportunityName", created.getOpportunityName());

              resultData.put("companyId", payload.getCompanyId());

              return AiExecutionResult.of(objectMapper.writeValueAsString(resultData), references);

        } catch (Exception e) {
            throw new RuntimeException("执行新增商机失败: " + e.getMessage(), e);
        }
    }

    /**
     * 引用 ID 使用创建服务返回值；名称从库中详情读取，避免沿用输入草稿文本。
     */
    private List<AiReferenceCollector.Reference> reference(SalesOpportunityVO created) {
        if (created != null && created.getId() != null
                && created.getOpportunityName() != null && !created.getOpportunityName().isBlank()) {
            return List.of(new AiReferenceCollector.Reference(
                    "opportunity", created.getId(), created.getOpportunityName()));
        }
        return List.of();
    }
}
