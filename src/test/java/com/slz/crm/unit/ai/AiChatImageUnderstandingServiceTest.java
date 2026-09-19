package com.slz.crm.unit.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.pojo.entity.AiChatImageEntity;
import com.slz.crm.server.ai.AiChatImageContextCache;
import com.slz.crm.server.ai.AiChatImageService;
import com.slz.crm.server.ai.AiChatImageUnderstandingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;

/** 图片理解 L1 持久化、L2 缓存与 VISION usage 验证。 */
class AiChatImageUnderstandingServiceTest {

  private AiChatImageService imageService;
  private ModelProvider modelProvider;
  private TokenUsageRecorder tokenUsageRecorder;
  private AiChatImageUnderstandingService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    imageService = mock(AiChatImageService.class);
    modelProvider = mock(ModelProvider.class);
    tokenUsageRecorder = mock(TokenUsageRecorder.class);
    ObjectProvider<ModelProvider> modelProviderProvider = mock(ObjectProvider.class);
    ObjectProvider<TokenUsageRecorder> recorderProvider = mock(ObjectProvider.class);
    when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
    when(recorderProvider.getIfAvailable()).thenReturn(tokenUsageRecorder);
    service =
        new AiChatImageUnderstandingService(
            imageService, new AiChatImageContextCache(), modelProviderProvider, recorderProvider);
  }

  @Test
  void understand_parsesPersistsAndRecordsVisionUsage() {
    AiChatImageEntity image = image();
    when(imageService.readBytes(image)).thenReturn("image".getBytes());
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(
            ModelCallResult.ofText(
                """
                        {"ocrText":"发票A","imageSummary":"一张发票",\
                        "keyEntities":["客户A","120万"],"focusedSummary":"发票A金额"}""",
                "qwen-vl",
                10L,
                5L,
                15L));

    var context = service.understand(image, "发票金额是多少", false).orElseThrow();

    assertThat(context.ocrText()).isEqualTo("发票A");
    assertThat(context.imageSummary()).isEqualTo("一张发票");
    assertThat(context.keyEntities()).containsExactly("客户A", "120万");
    assertThat(context.focusedSummary()).isEqualTo("发票A金额");
    verify(imageService).completeUnderstanding(7L, "发票A", "一张发票", List.of("客户A", "120万"));
    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    assertThat(captor.getValue().type()).isEqualTo(TokenUsageType.VISION);
    assertThat(captor.getValue().totalTokens()).isEqualTo(15L);
  }

  @Test
  void understand_kbOnGeneratesImageVectorAndRecordsEmbeddingUsage() {
    AiChatImageEntity image = image();
    when(imageService.readBytes(image)).thenReturn("image".getBytes());
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(
            ModelCallResult.ofText(
                "{\"ocrText\":\"发票A\",\"imageSummary\":\"一张发票\",\"keyEntities\":[\"客户A\"],"
                    + "\"focusedSummary\":\"发票A金额\"}",
                "qwen-vl",
                10L,
                5L,
                15L));
    when(modelProvider.embed(any(EmbeddingRequest.class)))
        .thenReturn(ModelCallResult.ofVector(new float[] {0.1F, 0.2F}, "text-embedding", 5L, 5L));

    var context = service.understand(image, "发票金额是多少", true).orElseThrow();

    assertThat(context.imageVector()).containsExactly(0.1F, 0.2F);
    ArgumentCaptor<EmbeddingRequest> embeddingCaptor =
        ArgumentCaptor.forClass(EmbeddingRequest.class);
    verify(modelProvider).embed(embeddingCaptor.capture());
    assertThat(embeddingCaptor.getValue().getInstructions().get(0))
        .contains("一张发票")
        .contains("发票A金额")
        .contains("发票金额是多少");

    ArgumentCaptor<TokenUsageRecord> usageCaptor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder, org.mockito.Mockito.times(2)).record(usageCaptor.capture());
    assertThat(usageCaptor.getAllValues())
        .extracting(TokenUsageRecord::type)
        .containsExactly(TokenUsageType.VISION, TokenUsageType.EMBEDDING);
  }

  @Test
  void understand_reusesQuestionFocusedCache() {
    AiChatImageEntity image = image();
    when(imageService.readBytes(image)).thenReturn("image".getBytes());
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("{\"focusedSummary\":\"缓存摘要\"}", "qwen-vl", 1L, 1L, 2L));

    assertThat(service.understand(image, "同一问题", false)).isPresent();
    assertThat(service.understand(image, "同一问题", false)).isPresent();

    verify(modelProvider, org.mockito.Mockito.times(1))
        .vision(any(Prompt.class), any(ModelCallOptions.class));
  }

  @Test
  void understand_withoutProviderReturnsPersistedL1() {
    AiChatImageUnderstandingService noProviderService =
        new AiChatImageUnderstandingService(
            imageService, new AiChatImageContextCache(), null, null);
    AiChatImageEntity image = image();
    image.setOcrText("OCR");
    image.setImageSummary("摘要");

    var context = noProviderService.understand(image, "问题", false).orElseThrow();

    assertThat(context.ocrText()).isEqualTo("OCR");
    assertThat(context.focusedSummary()).isEmpty();
    verifyNoInteractions(imageService);
  }

  private AiChatImageEntity image() {
    AiChatImageEntity image = new AiChatImageEntity();
    image.setId(7L);
    image.setSessionId(9L);
    image.setUserId(42L);
    image.setImageHash("hash");
    image.setStorageKey("ai-chat-images/test.png");
    return image;
  }
}
