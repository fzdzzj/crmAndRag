package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.dto.ai.AiContractDraftPayloadDTO;
import com.slz.crm.pojo.dto.ai.AiOrderItemDraftDTO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.ContractService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 合同创建执行器（CREATE_CONTRACT） 委托 ContractService.createWithOrders() 在服务层事务中创建合同和订单 */
@Slf4j
@Component
public class ContractActionExecutor implements AiActionExecutor {

  public static final String ACTION_TYPE = ActionTypeEnum.CREATE_CONTRACT.getValue();

  @Autowired private ContractService contractService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String actionType() {
    return ACTION_TYPE;
  }

  @Override
  public AiExecutionResult execute(String payloadJson) {
    try {
      AiContractDraftPayloadDTO payload =
          objectMapper.readValue(payloadJson, AiContractDraftPayloadDTO.class);

      // 创建合同
      ContractDTO contractDTO = toContractDTO(payload);
      List<OrderDTO> orders = new ArrayList<>();
      if (payload.getOrders() != null) {
        for (AiOrderItemDraftDTO item : payload.getOrders()) {
          orders.add(toOrderDTO(item));
        }
      }
      ContractVO created = contractService.createWithOrders(contractDTO, orders);
      if (created == null || created.getId() == null) {
        throw new IllegalStateException("创建合同失败");
      }

      List<AiReferenceCollector.Reference> references = reference(created);

      Map<String, Object> resultData = new java.util.HashMap<>();

      resultData.put("contractId", created.getId());

      resultData.put("orders", orders.size());

      return AiExecutionResult.of(objectMapper.writeValueAsString(resultData), references);

    } catch (Exception e) {
      throw new RuntimeException("执行创建合同失败: " + e.getMessage(), e);
    }
  }

  /** 引用 ID 使用创建服务返回值；名称从库中详情读取，避免沿用输入草稿文本。 */
  private List<AiReferenceCollector.Reference> reference(ContractVO created) {
    if (created != null
        && created.getId() != null
        && created.getContractName() != null
        && !created.getContractName().isBlank()) {
      return List.of(
          new AiReferenceCollector.Reference(
              "contract", created.getId(), created.getContractName()));
    }
    return List.of();
  }

  private ContractDTO toContractDTO(AiContractDraftPayloadDTO payload) {
    ContractDTO dto = new ContractDTO();
    dto.setContractName(payload.getContractName());
    dto.setOpportunityId(payload.getOpportunityId());
    // totalAmount 未提供时从订单明细自动汇总
    if (payload.getTotalAmount() != null) {
      dto.setTotalAmount(payload.getTotalAmount());
    } else if (payload.getOrders() != null) {
      BigDecimal sum =
          payload.getOrders().stream()
              .map(item -> item.getAmount() != null ? item.getAmount() : BigDecimal.ZERO)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      dto.setTotalAmount(sum);
    }
    return dto;
  }

  private OrderDTO toOrderDTO(AiOrderItemDraftDTO item) {
    OrderDTO dto = new OrderDTO();
    dto.setProductName(item.getProductName());
    dto.setQuantity(item.getQuantity());
    dto.setUnitPrice(item.getUnitPrice());
    // amount 缺省时由 quantity×unitPrice 推算
    if (item.getAmount() != null) {
      dto.setAmount(item.getAmount());
    } else if (item.getQuantity() != null && item.getUnitPrice() != null) {
      dto.setAmount(item.getQuantity().multiply(item.getUnitPrice()));
    } else {
      dto.setAmount(BigDecimal.ZERO);
    }
    dto.setRemark(item.getRemark());
    return dto;
  }
}
