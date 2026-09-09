package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.dto.ai.AiOrderDraftPayloadDTO;
import com.slz.crm.pojo.dto.ai.AiOrderItemDraftDTO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.enums.ActionTypeEnum;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.ContractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单创建执行器（CREATE_ORDER）
 * 复用现有 ContractOrderItemService.createBatch，权限与数据校验由现有链路保证
 */
@Slf4j

@Component
public class OrderActionExecutor implements AiActionExecutor {


    public static final String ACTION_TYPE = ActionTypeEnum.CREATE_ORDER.getValue();

    @Autowired
    private ContractOrderItemService contractOrderItemService;

    @Autowired
    private ContractService contractService;

    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    public String actionType() {
        return ACTION_TYPE;

    }

    @Override
    public AiExecutionResult execute(String payloadJson) {
        try {
            AiOrderDraftPayloadDTO payload = objectMapper.readValue(payloadJson, AiOrderDraftPayloadDTO.class);

            List<OrderDTO> orders = new ArrayList<>();

            if (payload.getOrders() != null) {

                for (AiOrderItemDraftDTO item : payload.getOrders()) {

                    orders.add(toOrderDTO(item));

                }

            }
            List<OrderVO> createdOrders = contractOrderItemService.createBatch(orders);

              Map<String, Object> resultData = new java.util.HashMap<>();

              resultData.put("created", createdOrders.size());

              return AiExecutionResult.of(objectMapper.writeValueAsString(resultData), contractReferences(payload));

        } catch (Exception e) {

            throw new RuntimeException("执行创建订单失败: " + e.getMessage(), e);

        }
    }

    /**
     * 订单无独立详情页，引用目标为其挂靠合同（按 contractId 去重；回查失败不影响确认结果）
     */
    private List<AiReferenceCollector.Reference> contractReferences(AiOrderDraftPayloadDTO payload) {
        List<AiReferenceCollector.Reference> references = new ArrayList<>();

        if (payload.getOrders() == null) {

            return references;

        }
        Set<Long> seen = new LinkedHashSet<>();

        for (AiOrderItemDraftDTO item : payload.getOrders()) {

            Long contractId = item.getContractId();

            if (contractId == null || !seen.add(contractId)) {

                continue;

            }
            try {
                ContractVO contract = contractService.getContractById(contractId);

                references.add(new AiReferenceCollector.Reference("contract", contractId,

                        contract != null && contract.getContractName() != null ? contract.getContractName() : "合同 #" + contractId));

            } catch (Exception e) {

                log.warn("回查合同名称失败，跳过该引用, contractId={}", contractId, e);

            }
        }
        return references;

    }

    private OrderDTO toOrderDTO(AiOrderItemDraftDTO item) {
        OrderDTO dto = new OrderDTO();

        dto.setContractId(item.getContractId());

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
