package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.server.service.PaymentRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("新增回款执行器")
class PaymentActionExecutorTest {

    @Mock
    private PaymentRecordService paymentRecordService;

    @InjectMocks
    private PaymentActionExecutor executor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("成功引用使用服务返回 ID 和合同名称")
    void successUsesCreatedIdAndContractName() throws Exception {
        PaymentRecordVO created = new PaymentRecordVO();
        created.setId(61L);
        created.setContractName("年度合同");
        when(paymentRecordService.create(any())).thenReturn(created);

        AiExecutionResult result = executor.execute(
                "{\"contractId\":5,\"paymentAmount\":100,\"paymentDate\":\"2026-08-31 00:00:00\"}");

        assertEquals("payment", result.getReferences().get(0).type());
        assertEquals(61L, result.getReferences().get(0).id());
        assertEquals("年度合同", result.getReferences().get(0).name());
    }

    @Test
    @DisplayName("业务失败时抛出且不生成引用")
    void businessFailureThrowsWithoutReference() {
        when(paymentRecordService.create(any())).thenReturn(null);

        assertThrows(RuntimeException.class, () -> executor.execute(
                "{\"contractId\":5,\"paymentAmount\":100}"));
    }

    @Test
    @DisplayName("引号和反斜杠输入输出仍是合法 JSON")
    void quoteAndBackslashInputProducesValidJson() throws Exception {
        PaymentRecordVO created = new PaymentRecordVO();
        created.setId(62L);
        created.setContractName("合同\"名\\称");
        when(paymentRecordService.create(any())).thenReturn(created);
        String payload = objectMapper.writeValueAsString(Map.of(
                "contractId", 5L, "paymentAmount", 100, "remark", "备注\"反斜杠\\"));

        AiExecutionResult result = executor.execute(payload);

        assertEquals(5L, objectMapper.readTree(result.getResult()).get("contractId").asLong());
    }
}
