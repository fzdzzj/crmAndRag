package com.slz.crm.server.ai.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.service.SalesOpportunityService;
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
@DisplayName("新增商机执行器")
class OpportunityActionExecutorTest {

    @Mock
    private SalesOpportunityService salesOpportunityService;

    @InjectMocks
    private OpportunityActionExecutor executor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("成功引用使用服务返回 ID 和名称")
    void successUsesCreatedIdAndName() throws Exception {
        SalesOpportunityVO created = new SalesOpportunityVO();
        created.setId(31L);
        created.setOpportunityName("年度续约");
        when(salesOpportunityService.create(any())).thenReturn(created);

        AiExecutionResult result = executor.execute("{\"companyId\":9,\"opportunityName\":\"年度续约\"}");

        assertEquals("opportunity", result.getReferences().get(0).type());
        assertEquals(31L, result.getReferences().get(0).id());
        assertEquals("年度续约", result.getReferences().get(0).name());
    }

    @Test
    @DisplayName("业务失败时抛出且不生成引用")
    void businessFailureThrowsWithoutReference() {
        when(salesOpportunityService.create(any())).thenReturn(null);

        assertThrows(RuntimeException.class, () -> executor.execute("{\"companyId\":9,\"opportunityName\":\"年度续约\"}"));
    }

    @Test
    @DisplayName("引号和反斜杠输入输出仍是合法 JSON")
    void quoteAndBackslashInputProducesValidJson() throws Exception {
        String dangerousName = "续\"约\\项目";
        SalesOpportunityVO created = new SalesOpportunityVO();
        created.setId(32L);
        created.setOpportunityName(dangerousName);
        when(salesOpportunityService.create(any())).thenReturn(created);

        AiExecutionResult result = executor.execute(objectMapper.writeValueAsString(
                Map.of("companyId", 9L, "opportunityName", dangerousName)));

        assertEquals(dangerousName, objectMapper.readTree(result.getResult()).get("opportunityName").asText());
        assertEquals(dangerousName, result.getReferences().get(0).name());
    }
}
