package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.server.service.CustomerCompanyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("新增客户执行器")
class CustomerActionExecutorTest {

    @Mock
    private CustomerCompanyService customerCompanyService;

    @InjectMocks
    private CustomerActionExecutor executor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("成功引用使用返回 ID 和库中名称")
    void successUsesCreatedIdAndDatabaseName() throws Exception {
        String payload = """
                {"companyName":"公司\\"引号","dept":"研发部"}
                """;
        CustomerCompanyVO created = new CustomerCompanyVO();
        created.setId(88L);
        created.setCompanyName("库中公司名");
        when(customerCompanyService.add(any())).thenReturn(created);

        AiExecutionResult result = executor.execute(payload);

        JsonNode json = objectMapper.readTree(result.getResult());
        assertEquals("customer", json.get("created").asText());
        assertEquals(1, result.getReferences().size());
        assertEquals("customerCompany", result.getReferences().get(0).type());
        assertEquals(88L, result.getReferences().get(0).id());
        assertEquals("库中公司名", result.getReferences().get(0).name());
    }

    @Test
    @DisplayName("同名创建不回查猜 ID，各自引用保留")
    void sameNameCreatesKeepDistinctReferences() throws Exception {
        CustomerCompanyVO first = new CustomerCompanyVO();
        first.setId(11L);
        first.setCompanyName("同名公司");
        CustomerCompanyVO second = new CustomerCompanyVO();
        second.setId(12L);
        second.setCompanyName("同名公司");
        when(customerCompanyService.add(any())).thenReturn(first, second);

        AiExecutionResult firstResult = executor.execute("{\"companyName\":\"同名公司\",\"dept\":\"研发部\"}");
        AiExecutionResult secondResult = executor.execute("{\"companyName\":\"同名公司\",\"dept\":\"市场部\"}");

        assertEquals(11L, firstResult.getReferences().get(0).id());
        assertEquals(12L, secondResult.getReferences().get(0).id());
        assertEquals("同名公司", firstResult.getReferences().get(0).name());
        assertEquals("同名公司", secondResult.getReferences().get(0).name());
    }

    @Test
    @DisplayName("名称缺失时降级为空引用")
    void missingNameDegradesToEmptyReferences() throws Exception {
        CustomerCompanyVO created = new CustomerCompanyVO();
        created.setId(99L);
        when(customerCompanyService.add(any())).thenReturn(created);

        AiExecutionResult result = executor.execute("{\"companyName\":\"公司\",\"dept\":\"研发部\"}");

        assertTrue(result.getReferences().isEmpty());
        assertEquals("customer", objectMapper.readTree(result.getResult()).get("created").asText());
    }

    @Test
    @DisplayName("创建失败不输出错误 ID")
    void failureReturnsNoReference() {
        when(customerCompanyService.add(any())).thenReturn(null);

        assertThrows(RuntimeException.class, () -> executor.execute("{\"companyName\":\"公司\"}"));
    }

    @Test
    @DisplayName("引号和反斜杠输入输出仍是合法 JSON")
    void quoteAndBackslashInputProducesValidJson() throws Exception {
        String dangerousName = "公司\"引号\\反斜杠";
        CustomerCompanyVO created = new CustomerCompanyVO();
        created.setId(77L);
        created.setCompanyName(dangerousName);
        when(customerCompanyService.add(any())).thenReturn(created);

        AiExecutionResult result = executor.execute(objectMapper.writeValueAsString(
                java.util.Map.of("companyName", dangerousName, "dept", "研发部")));

        assertEquals(dangerousName, objectMapper.readTree(result.getResult()).get("companyName").asText());
        assertEquals(dangerousName, result.getReferences().get(0).name());
    }
}
