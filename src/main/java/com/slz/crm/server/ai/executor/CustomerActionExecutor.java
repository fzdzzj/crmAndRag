package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.dto.ai.AiCustomerDraftPayloadDTO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.CustomerCompanyService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 新增客户执行器（CREATE_CUSTOMER） 委托 CustomerCompanyService.add()，公司名+部门判重等内置校验原样生效 */
@Slf4j
@Component
public class CustomerActionExecutor implements AiActionExecutor {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_CUSTOMER.getValue();

  @Autowired private CustomerCompanyService customerCompanyService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiExecutionResult execute(String payloadJson) {
    try {
      AiCustomerDraftPayloadDTO payload =
          objectMapper.readValue(payloadJson, AiCustomerDraftPayloadDTO.class);

      CustomerCompanyDTO dto = new CustomerCompanyDTO();
      BeanUtils.copyProperties(payload, dto);

      CustomerCompanyVO created = customerCompanyService.add(dto);
      if (created == null || created.getId() == null) {
        throw new IllegalStateException("新增客户失败");
      }

      Map<String, Object> resultData = new HashMap<>();

      resultData.put("created", "customer");

      resultData.put("companyName", created.getCompanyName());

      return AiExecutionResult.of(objectMapper.writeValueAsString(resultData), reference(created));

    } catch (Exception e) {
      throw new RuntimeException("执行新增客户失败: " + e.getMessage(), e);
    }
  }

  /** 引用 ID 使用创建服务返回值；名称从库中详情读取，避免沿用输入草稿文本。 */
  private List<AiReferenceCollector.Reference> reference(CustomerCompanyVO created) {
    List<AiReferenceCollector.Reference> result = List.of();
    if (created != null
        && created.getId() != null
        && created.getCompanyName() != null
        && !created.getCompanyName().isBlank()) {
      result =
          List.of(
              new AiReferenceCollector.Reference(
                  "customerCompany", created.getId(), created.getCompanyName()));
    }
    return result;
  }
}
