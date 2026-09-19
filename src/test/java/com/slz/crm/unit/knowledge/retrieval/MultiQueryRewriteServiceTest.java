package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.slz.crm.knowledge.retrieval.MultiQueryRewriteService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/** 多查询变体生成器单测（enhance-query-transformation 任务 1.1/1.3）： 变体解析/去重/截断与全路径降级（关闭、失败、空输出 → 单查询）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiQueryRewriteServiceTest {
  @Mock private ModelProvider modelProvider;
  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  @Mock private DynamicConfigService dynamicConfigService;

  private MultiQueryRewriteService service(Boolean enabled, Integer variants) {
    lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    lenient()
        .when(
            dynamicConfigService.get(
                eq("rag.query.multi-query.enabled"), eq(Boolean.class), eq(false)))
        .thenReturn(enabled);
    lenient()
        .when(
            dynamicConfigService.get(
                eq("rag.query.multi-query.variants"), eq(Integer.class), eq(3)))
        .thenReturn(variants);
    return new MultiQueryRewriteService(modelProvider, dynamicConfigProvider);
  }

  private void llmReturns(String output) {
    lenient()
        .when(modelProvider.chat(any(), any()))
        .thenReturn(ModelCallResult.ofText(output, "test-model", 10L, 20L, 30L));
  }

  /** 任务 1.1：启用后产出 原始 + N 变体，首元素恒为原始查询。 */
  @Test
  void expandShouldReturnOriginalPlusVariants() {
    llmReturns("XR-500 的售后服务联系人和流程\nXR-500 设备坏了找谁\n售后 支持 通道");
    List<String> routes = service(true, 3).expand("XR-500售后找谁");

    assertEquals(4, routes.size());
    assertEquals("XR-500售后找谁", routes.getFirst(), "首元素恒为原始查询");
    assertEquals("XR-500 的售后服务联系人和流程", routes.get(1));
    assertEquals("XR-500 设备坏了找谁", routes.get(2));
    assertEquals("售后 支持 通道", routes.get(3));
  }

  /** 变体解析：去行首编号、截断到配置变体数、剔除与原始查询重复项。 */
  @Test
  void expandShouldStripNumberingCapAndDedup() {
    llmReturns("1. 变体一\n2. 变体二\n变体三\n变体一");
    List<String> routes = service(true, 2).expand("原查询");

    assertEquals(3, routes.size(), "原始 + 2 个变体（上限截断，重复的「变体一」被剔）");
    assertEquals("变体一", routes.get(1));
    assertEquals("变体二", routes.get(2));
  }

  /** 任务 1.3：LLM 失败 / 空输出 / 变体全为空 → 回退单查询。（doThrow/doReturn 重打桩不触发上一条桩） */
  @Test
  void expandShouldFallBackToSingleQueryOnFailureOrBlank() {
    org.mockito.Mockito.doThrow(new IllegalStateException("模型超载"))
        .when(modelProvider)
        .chat(any(), any());
    assertEquals(List.of("原查询"), service(true, 3).expand("原查询"));

    org.mockito.Mockito.doReturn(ModelCallResult.ofText("   \n  ", "m", null, null, null))
        .when(modelProvider)
        .chat(any(), any());
    assertEquals(List.of("原查询"), service(true, 3).expand("原查询"));
  }

  /** 任务 1.4 关闭态：enabled=false 时不发起任何 LLM 调用，直接单查询。 */
  @Test
  void expandShouldSkipLlmWhenDisabled() {
    List<String> routes = service(false, 3).expand("原查询");

    assertEquals(List.of("原查询"), routes);
    verify(modelProvider, never()).chat(any(), any());
  }

  /** 配置中心缺席（兼容装配）= 关闭。 */
  @Test
  void expandShouldDegradeWhenConfigUnavailable() {
    lenient().when(dynamicConfigProvider.getIfAvailable()).thenReturn(null);
    MultiQueryRewriteService noConfig =
        new MultiQueryRewriteService(modelProvider, dynamicConfigProvider);

    assertEquals(List.of("原查询"), noConfig.expand("原查询"));
    verify(modelProvider, never()).chat(any(), any());
  }

  /** 变体数上限 5（防 runaway 成本），下限 1。 */
  @Test
  void expandShouldClampVariantCount() {
    llmReturns("v1\nv2\nv3\nv4\nv5\nv6\nv7");
    assertEquals(6, service(true, 7).expand("q").size(), "变体数被钳到上限 5 + 原始 1");
    llmReturns("v1\nv2");
    assertEquals(2, service(true, 1).expand("q").size(), "variants=1 时只取 1 个变体");
    assertTrue(service(true, 0).expand("q").size() >= 1, "非法配置回退默认 3，至少含原始查询");
  }
}
