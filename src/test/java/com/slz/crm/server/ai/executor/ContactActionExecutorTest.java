package com.slz.crm.server.ai.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.server.service.CustomerContactService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("新增联系人执行器")
class ContactActionExecutorTest {

  @Mock private CustomerContactService customerContactService;

  @InjectMocks private ContactActionExecutor executor;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("成功引用使用服务返回 ID 和名称")
  void successUsesCreatedIdAndName() throws Exception {
    CustomerContactVO created = new CustomerContactVO();
    created.setId(21L);
    created.setName("张三");
    when(customerContactService.insert(any())).thenReturn(created);

    AiExecutionResult result = executor.execute("{\"companyId\":9,\"name\":\"张三\"}");

    assertEquals("contact", result.getReferences().get(0).type());
    assertEquals(21L, result.getReferences().get(0).id());
    assertEquals("张三", result.getReferences().get(0).name());
  }

  @Test
  @DisplayName("业务失败时抛出且不生成引用")
  void businessFailureThrowsWithoutReference() {
    when(customerContactService.insert(any())).thenReturn(null);

    assertThrows(
        RuntimeException.class, () -> executor.execute("{\"companyId\":9,\"name\":\"张三\"}"));
  }

  @Test
  @DisplayName("引号和反斜杠输入输出仍是合法 JSON")
  void quoteAndBackslashInputProducesValidJson() throws Exception {
    String dangerousName = "张\"三\\测试";
    CustomerContactVO created = new CustomerContactVO();
    created.setId(22L);
    created.setName(dangerousName);
    when(customerContactService.insert(any())).thenReturn(created);

    AiExecutionResult result =
        executor.execute(
            objectMapper.writeValueAsString(Map.of("companyId", 9L, "name", dangerousName)));

    JsonNode json = objectMapper.readTree(result.getResult());
    assertEquals(dangerousName, json.get("name").asText());
    assertEquals(dangerousName, result.getReferences().get(0).name());
  }
}
