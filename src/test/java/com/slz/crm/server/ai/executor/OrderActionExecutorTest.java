package com.slz.crm.server.ai.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.ContractService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("创建订单执行器")
class OrderActionExecutorTest {

  @Mock private ContractOrderItemService contractOrderItemService;

  @Mock private ContractService contractService;

  @InjectMocks private OrderActionExecutor executor;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("同合同多订单只返回一个合同引用")
  void duplicateContractsProduceSingleReference() throws Exception {
    OrderVO first = new OrderVO();
    first.setId(51L);
    first.setProductName("产品A");
    OrderVO second = new OrderVO();
    second.setId(52L);
    second.setProductName("产品B");
    when(contractOrderItemService.createBatch(any())).thenReturn(List.of(first, second));
    ContractVO contract = new ContractVO();
    contract.setId(5L);
    contract.setContractName("年度合同");
    when(contractService.getContractById(5L)).thenReturn(contract);

    String payload =
        objectMapper.writeValueAsString(
            Map.of(
                "orders",
                List.of(
                    Map.of("contractId", 5L, "productName", "产品A", "quantity", 1),
                    Map.of("contractId", 5L, "productName", "产品B", "quantity", 2))));
    AiExecutionResult result = executor.execute(payload);

    assertEquals(1, result.getReferences().size());
    assertEquals("contract", result.getReferences().get(0).type());
    assertEquals(5L, result.getReferences().get(0).id());
    assertEquals("年度合同", result.getReferences().get(0).name());
  }

  @Test
  @DisplayName("订单创建失败时抛出且不生成引用")
  void creationFailureThrowsWithoutReference() {
    when(contractOrderItemService.createBatch(any()))
        .thenThrow(new IllegalStateException("批量插入失败"));

    assertThrows(
        RuntimeException.class,
        () ->
            executor.execute(
                "{\"orders\":[{\"contractId\":5,\"productName\":\"产品A\",\"quantity\":1}]}"));
  }

  @Test
  @DisplayName("引号和反斜杠输入不破坏结果 JSON")
  void quoteAndBackslashInputProducesValidJson() throws Exception {
    when(contractOrderItemService.createBatch(any())).thenReturn(List.of());
    String payload =
        objectMapper.writeValueAsString(
            Map.of(
                "orders",
                List.of(Map.of("contractId", 5L, "productName", "产品\"A\\B", "quantity", 1))));

    AiExecutionResult result = executor.execute(payload);

    assertEquals(0, objectMapper.readTree(result.getResult()).get("created").asInt());
  }
}
