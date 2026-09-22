package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.PaymentRecordDTO;
import com.slz.crm.pojo.dto.ai.AiPaymentDraftPayloadDTO;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.PaymentRecordService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 新增回款执行器（CREATE_PAYMENT） 委托 PaymentRecordService.create()，contractId 已由校验层解析，合同存在/订单归属校验由该方法内置兜底
 */
@Slf4j
@Component
public class PaymentActionExecutor implements AiActionExecutor {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_PAYMENT.getValue();

  private static final DateTimeFormatter DT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Autowired private PaymentRecordService paymentRecordService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiExecutionResult execute(String payloadJson) {
    try {
      AiPaymentDraftPayloadDTO payload =
          objectMapper.readValue(payloadJson, AiPaymentDraftPayloadDTO.class);

      PaymentRecordDTO dto = new PaymentRecordDTO();
      dto.setContractId(payload.getContractId());
      dto.setOrderItemId(payload.getOrderItemId());
      dto.setPaymentAmount(payload.getPaymentAmount());
      dto.setPaymentMethod(payload.getPaymentMethod());
      dto.setPaymentStatus(payload.getPaymentStatus());
      dto.setRemark(payload.getRemark());
      // 回款日期缺省当天
      if (payload.getPaymentDate() != null && !payload.getPaymentDate().isBlank()) {
        dto.setPaymentDate(LocalDateTime.parse(payload.getPaymentDate(), DT_FMT));
      } else {
        dto.setPaymentDate(LocalDateTime.now());
      }

      PaymentRecordVO created = paymentRecordService.create(dto);
      if (created == null || created.getId() == null) {
        throw new IllegalStateException("新增回款失败");
      }

      Map<String, Object> resultData = new java.util.HashMap<>();

      resultData.put("created", "payment");

      resultData.put("contractId", payload.getContractId());

      resultData.put("amount", payload.getPaymentAmount());

      return AiExecutionResult.of(objectMapper.writeValueAsString(resultData), reference(created));

    } catch (Exception e) {
      throw new RuntimeException("执行新增回款失败: " + e.getMessage(), e);
    }
  }

  /** 引用 ID 使用创建服务返回值；名称从库中详情读取，避免沿用输入草稿文本。 */
  private List<AiReferenceCollector.Reference> reference(PaymentRecordVO created) {
    List<AiReferenceCollector.Reference> result = List.of();
    if (created != null
        && created.getId() != null
        && created.getContractName() != null
        && !created.getContractName().isBlank()) {
      result =
          List.of(
              new AiReferenceCollector.Reference(
                  "payment", created.getId(), created.getContractName()));
    }
    return result;
  }
}
