package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.InvoiceInfoDTO;
import com.slz.crm.pojo.dto.ai.AiInvoiceDraftPayloadDTO;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.InvoiceInfoService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 新增发票执行器（CREATE_INVOICE） 委托 InvoiceInfoService.create()，contractId 已由校验层解析 */
@Slf4j
@Component
public class InvoiceActionExecutor implements AiActionExecutor {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_INVOICE.getValue();

  private static final DateTimeFormatter DT_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Autowired private InvoiceInfoService invoiceInfoService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiExecutionResult execute(String payloadJson) {
    try {
      AiInvoiceDraftPayloadDTO payload =
          objectMapper.readValue(payloadJson, AiInvoiceDraftPayloadDTO.class);

      InvoiceInfoDTO dto = new InvoiceInfoDTO();
      dto.setContractId(payload.getContractId());
      dto.setPaymentId(payload.getPaymentId());
      dto.setInvoiceNo(payload.getInvoiceNo());
      dto.setInvoiceAmount(payload.getInvoiceAmount());
      dto.setInvoiceType(payload.getInvoiceType());
      dto.setRemark(payload.getRemark());
      // 开票日期缺省当天
      if (payload.getInvoiceDate() != null && !payload.getInvoiceDate().isBlank()) {
        dto.setInvoiceDate(LocalDateTime.parse(payload.getInvoiceDate(), DT_FMT));
      } else {
        dto.setInvoiceDate(LocalDateTime.now());
      }

      InvoiceInfoVO created = invoiceInfoService.create(dto);
      if (created == null || created.getId() == null) {
        throw new IllegalStateException("新增发票失败");
      }

      Map<String, Object> resultData = new java.util.HashMap<>();

      resultData.put("created", "invoice");

      resultData.put("contractId", payload.getContractId());

      resultData.put("invoiceNo", created.getInvoiceNo());

      return AiExecutionResult.of(objectMapper.writeValueAsString(resultData), reference(created));

    } catch (Exception e) {
      throw new RuntimeException("执行新增发票失败: " + e.getMessage(), e);
    }
  }

  /** 引用 ID 使用创建服务返回值；名称从库中详情读取，避免沿用输入草稿文本。 */
  private List<AiReferenceCollector.Reference> reference(InvoiceInfoVO created) {
    if (created != null
        && created.getId() != null
        && created.getInvoiceNo() != null
        && !created.getInvoiceNo().isBlank()) {
      return List.of(
          new AiReferenceCollector.Reference("invoice", created.getId(), created.getInvoiceNo()));
    }
    return List.of();
  }
}
