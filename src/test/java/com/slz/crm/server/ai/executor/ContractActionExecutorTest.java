package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.server.service.ContractService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("创建合同执行器")
class ContractActionExecutorTest {

    @Mock
    private ContractService contractService;

    @InjectMocks
    private ContractActionExecutor executor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("订单列表交给服务层编排并返回合同引用")
    void ordersDelegateToServiceOrchestration() throws Exception {
        ContractVO created = new ContractVO();
        created.setId(41L);
        created.setContractName("年度合同");
        when(contractService.createWithOrders(any(), any())).thenReturn(created);

        String payload = objectMapper.writeValueAsString(Map.of(
                "contractName", "年度合同",
                "opportunityId", 3L,
                "totalAmount", 120,
                "orders", List.of(Map.of("productName", "实施服务", "quantity", 2, "unitPrice", 60))));
        AiExecutionResult result = executor.execute(payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(contractService).createWithOrders(any(), captor.capture());
        assertEquals("实施服务", captor.getValue().get(0).getProductName());
        assertEquals("contract", result.getReferences().get(0).type());
        assertEquals(41L, result.getReferences().get(0).id());
        assertEquals("年度合同", result.getReferences().get(0).name());
    }

    @Test
    @DisplayName("服务编排返回空时抛出")
    void orchestrationFailureThrows() {
        when(contractService.createWithOrders(any(), any())).thenReturn(null);

        assertThrows(RuntimeException.class, () -> executor.execute("{\"contractName\":\"年度合同\",\"totalAmount\":100,\"orders\":[]}"));
        verify(contractService).createWithOrders(any(), any());
    }

    @Test
    @DisplayName("引号和反斜杠输入输出仍是合法 JSON")
    void quoteAndBackslashInputProducesValidJson() throws Exception {
        String dangerousName = "合同\"名\\称";
        ContractVO created = new ContractVO();
        created.setId(42L);
        created.setContractName(dangerousName);
        when(contractService.createWithOrders(any(), any())).thenReturn(created);

        String payload = objectMapper.writeValueAsString(Map.of(
                "contractName", dangerousName,
                "opportunityId", 3L,
                "totalAmount", 100,
                "orders", List.of(Map.of("productName", "产品", "quantity", 1))));
        AiExecutionResult result = executor.execute(payload);

        JsonNode json = objectMapper.readTree(result.getResult());
        assertEquals(42L, json.get("contractId").asLong());
        assertTrue(result.getReferences().stream().allMatch(reference -> reference.name().equals(dangerousName)));
    }
}
