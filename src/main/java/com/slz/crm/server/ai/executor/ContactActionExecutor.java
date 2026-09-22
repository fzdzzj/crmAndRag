package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.dto.ai.AiContactDraftPayloadDTO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.CustomerContactService;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 新增联系人执行器（CREATE_CONTACT） 委托 CustomerContactService.insert()，companyId 已由校验层解析 */
@Slf4j
@Component
public class ContactActionExecutor implements AiActionExecutor {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_CONTACT.getValue();

  @Autowired private CustomerContactService customerContactService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 执行器混调service+objectMapper多源，失败包装业务异常上抛
  public AiExecutionResult execute(String payloadJson) {
    try {
      AiContactDraftPayloadDTO payload =
          objectMapper.readValue(payloadJson, AiContactDraftPayloadDTO.class);

      CustomerContactDTO dto = new CustomerContactDTO();
      BeanUtils.copyProperties(payload, dto);
      // 草稿不支持备注子列表，置空避免误写
      dto.setRemarks(null);

      CustomerContactVO created = customerContactService.insert(dto);
      if (created == null || created.getId() == null) {
        throw new IllegalStateException("新增联系人失败");
      }

      Map<String, Object> resultData = new java.util.HashMap<>();

      resultData.put("created", "contact");

      resultData.put("name", created.getName());

      resultData.put("companyId", payload.getCompanyId());

      return AiExecutionResult.of(objectMapper.writeValueAsString(resultData), reference(created));

    } catch (Exception e) {
      throw new RuntimeException("执行新增联系人失败: " + e.getMessage(), e);
    }
  }

  /** 引用 ID 使用创建服务返回值；名称从库中详情读取，避免沿用输入草稿文本。 */
  private List<AiReferenceCollector.Reference> reference(CustomerContactVO created) {
    List<AiReferenceCollector.Reference> result = List.of();
    if (created != null
        && created.getId() != null
        && created.getName() != null
        && !created.getName().isBlank()) {
      result =
          List.of(
              new AiReferenceCollector.Reference("contact", created.getId(), created.getName()));
    }
    return result;
  }
}
