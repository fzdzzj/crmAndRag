package com.slz.crm.server.ai.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.server.service.InvoiceInfoService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("新增发票执行器")
class InvoiceActionExecutorTest {

  @Mock private InvoiceInfoService invoiceInfoService;

  @InjectMocks private InvoiceActionExecutor executor;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("成功引用使用服务返回 ID 和发票号")
  void successUsesCreatedIdAndInvoiceNo() throws Exception {
    InvoiceInfoVO created = new InvoiceInfoVO();
    created.setId(71L);
    created.setInvoiceNo("INV-001");
    when(invoiceInfoService.create(any())).thenReturn(created);

    AiExecutionResult result =
        executor.execute("{\"contractId\":5,\"invoiceNo\":\"INV-001\",\"invoiceAmount\":100}");

    assertEquals("invoice", result.getReferences().get(0).type());
    assertEquals(71L, result.getReferences().get(0).id());
    assertEquals("INV-001", result.getReferences().get(0).name());
  }

  @Test
  @DisplayName("业务失败时抛出且不生成引用")
  void businessFailureThrowsWithoutReference() {
    when(invoiceInfoService.create(any())).thenReturn(null);

    assertThrows(
        RuntimeException.class,
        () ->
            executor.execute("{\"contractId\":5,\"invoiceNo\":\"INV-001\",\"invoiceAmount\":100}"));
  }

  @Test
  @DisplayName("引号和反斜杠输入输出仍是合法 JSON")
  void quoteAndBackslashInputProducesValidJson() throws Exception {
    String dangerousNo = "INV\"001\\A";
    InvoiceInfoVO created = new InvoiceInfoVO();
    created.setId(72L);
    created.setInvoiceNo(dangerousNo);
    when(invoiceInfoService.create(any())).thenReturn(created);
    String payload =
        objectMapper.writeValueAsString(
            Map.of("contractId", 5L, "invoiceNo", dangerousNo, "invoiceAmount", 100));

    AiExecutionResult result = executor.execute(payload);

    assertEquals(dangerousNo, objectMapper.readTree(result.getResult()).get("invoiceNo").asText());
  }
}
