package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/** 查询改写与失败降级测试。 */
@ExtendWith(MockitoExtension.class)
class RetrievalQueryRewriteServiceTest {
  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Test
  void shouldUseModelRewrittenQuery() {
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("改写后查询：客户合同流程", "qwen-plus", 10L, 5L, 15L));
    RetrievalQueryRewriteService service =
        new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider);

    assertEquals("客户合同流程", service.rewrite("它怎么走"));
  }

  @Test
  void shouldFallbackWhenModelFails() {
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenThrow(new IllegalStateException("模型不可用"));
    RetrievalQueryRewriteService service =
        new RetrievalQueryRewriteService(modelProvider, dynamicConfigProvider);

    assertEquals("客户合同流程", service.rewrite("客户合同流程"));
  }
}
