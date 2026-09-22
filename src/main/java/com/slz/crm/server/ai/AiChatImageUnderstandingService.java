package com.slz.crm.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.pojo.entity.AiChatImageEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

/**
 * 聊天图片理解编排。
 *
 * <p>L1 结果按 hash 持久化；L2 的 focusedSummary 按问题缓存。 图片理解不依赖 KB 开关，只有图片向量才由检索链路按 KB 开启懒生成。
 */
@Slf4j
@Service
public class AiChatImageUnderstandingService {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String INSTRUCTION =
      """
            你是 CRM 助手的图片理解器。请只输出 JSON，字段如下：
            {"ocrText":"图片中的完整可读文字","imageSummary":"图片内容摘要，不超过300字",\
            "keyEntities":["关键客户/合同/金额/日期等实体"],"focusedSummary":"结合用户问题的聚焦摘要"}
            不要输出 Markdown 代码块，不要解释。
            """;

  private final AiChatImageService imageService;
  private final AiChatImageContextCache contextCache;
  private final ObjectProvider<ModelProvider> modelProviderProvider;
  private final ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

  public AiChatImageUnderstandingService(
      AiChatImageService imageService,
      AiChatImageContextCache contextCache,
      ObjectProvider<ModelProvider> modelProviderProvider,
      ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider) {
    this.imageService = imageService;
    this.contextCache = contextCache;
    this.modelProviderProvider = modelProviderProvider;
    this.tokenUsageRecorderProvider = tokenUsageRecorderProvider;
  }

  /** 图片理解结果；{@code imageVector} 仅 KB ON 且向量已生成时非空。 */
  public record UnderstandingContext(
      String ocrText,
      String imageSummary,
      List<String> keyEntities,
      String focusedSummary,
      float[] imageVector) {}

  public Optional<UnderstandingContext> understand(
      AiChatImageEntity image, String question, boolean generateImageVector) {
    Optional<UnderstandingContext> result = Optional.empty();
    if (image != null && image.getSessionId() != null && image.getImageHash() != null) {
      ModelProvider provider =
          modelProviderProvider == null ? null : modelProviderProvider.getIfAvailable();
      if (provider == null) {
        log.warn("ModelProvider 未就绪，仅返回图片已持久化理解: imageId={}", image.getId());
        result = Optional.of(toContext(image, null));
      } else {
        result = understandWithProvider(image, question, generateImageVector, provider);
      }
    }
    return result;
  }

  /** provider 就绪时，先查缓存，命中且满足向量条件则用缓存理解，否则走视觉全量理解。 */
  private Optional<UnderstandingContext> understandWithProvider(
      AiChatImageEntity image,
      String question,
      boolean generateImageVector,
      ModelProvider provider) {
    Optional<AiChatImageContextCache.CachedImageContext> cached =
        contextCache.get(image.getSessionId(), image.getImageHash(), question);
    return cached
        .map(context -> toContext(image, context))
        .map(context -> resolveFromCache(image, question, generateImageVector, provider, context))
        .orElseGet(() -> understandUncached(image, question, generateImageVector, provider));
  }

  /** 缓存命中：无需再生向量或已有向量直接返回；否则补生成向量后缓存。 */
  private Optional<UnderstandingContext> resolveFromCache(
      AiChatImageEntity image,
      String question,
      boolean generateImageVector,
      ModelProvider provider,
      UnderstandingContext context) {
    Optional<UnderstandingContext> result;
    if (!generateImageVector || context.imageVector() != null) {
      result = Optional.of(context);
    } else {
      float[] vector = generateImageVector(image, question, context, provider);
      if (vector != null) {
        contextCache.put(
            image.getSessionId(), image.getImageHash(), question, context.focusedSummary(), vector);
        result =
            Optional.of(
                new UnderstandingContext(
                    context.ocrText(),
                    context.imageSummary(),
                    context.keyEntities(),
                    context.focusedSummary(),
                    vector));
      } else {
        result = Optional.of(context);
      }
    }
    return result;
  }

  /** 缓存未命中：调用视觉模型解析，持久化理解并按需生成图片向量。 */
  private Optional<UnderstandingContext> understandUncached(
      AiChatImageEntity image,
      String question,
      boolean generateImageVector,
      ModelProvider provider) {
    String generated = callVision(image, question, provider);
    Understanding parsed = parse(generated);
    imageService.completeUnderstanding(
        image.getId(), parsed.ocrText(), parsed.imageSummary(), parsed.keyEntities());
    float[] imageVector = null;
    if (generateImageVector) {
      imageVector =
          generateImageVector(
              image,
              question,
              new UnderstandingContext(
                  parsed.ocrText(),
                  parsed.imageSummary(),
                  parsed.keyEntities(),
                  parsed.focusedSummary(),
                  null),
              provider);
    }
    contextCache.put(
        image.getSessionId(), image.getImageHash(), question, parsed.focusedSummary(), imageVector);
    return Optional.of(
        new UnderstandingContext(
            parsed.ocrText(),
            parsed.imageSummary(),
            parsed.keyEntities(),
            parsed.focusedSummary(),
            imageVector));
  }

  private String callVision(AiChatImageEntity image, String question, ModelProvider provider) {
    String result;
    try {
      byte[] content = imageService.readBytes(image);
      MimeType mimeType = resolveMimeType(image);
      List<Message> messages =
          List.of(
              new SystemMessage(INSTRUCTION),
              UserMessage.builder()
                  .text(question == null || question.isBlank() ? "请描述图片" : question)
                  .media(List.of(new Media(mimeType, new ByteArrayResource(content))))
                  .build());
      ModelCallResult<String> callResult =
          provider.vision(new Prompt(messages), ModelCallOptions.defaults());
      recordUsage(image, callResult, TokenUsageType.VISION);
      result = AiThinkTagStripper.strip(callResult.content());
    } catch (Exception exception) {
      log.warn("聊天图片理解失败，降级为无图片上下文: imageId={}", image.getId(), exception);
      result = "";
    }
    return result;
  }

  private float[] generateImageVector(
      AiChatImageEntity image,
      String question,
      UnderstandingContext context,
      ModelProvider provider) {
    float[] floats;
    try {
      StringBuilder text = new StringBuilder("图片理解：");
      if (context.imageSummary() != null && !context.imageSummary().isBlank()) {
        text.append(context.imageSummary().trim());
      }
      if (context.ocrText() != null && !context.ocrText().isBlank()) {
        text.append("\nOCR：").append(context.ocrText().trim());
      }
      if (!context.keyEntities().isEmpty()) {
        text.append("\n实体：").append(String.join("、", context.keyEntities()));
      }
      if (context.focusedSummary() != null && !context.focusedSummary().isBlank()) {
        text.append("\n聚焦：").append(context.focusedSummary().trim());
      }
      if (question != null && !question.isBlank()) {
        text.append("\n问题：").append(question.trim());
      }
      ModelCallResult<float[]> result =
          provider.embed(
              new EmbeddingRequest(
                  List.of(text.toString()), EmbeddingOptionsBuilder.builder().build()));
      recordUsage(image, result, TokenUsageType.EMBEDDING);
      floats = result.vector();
    } catch (Exception exception) {
      log.warn("聊天图片向量懒生成失败，降级为纯文本检索: imageId={}", image.getId(), exception);
      floats = null;
    }
    return floats;
  }

  private Understanding parse(String content) {
    Understanding result;
    if (content == null || content.isBlank()) {
      result = new Understanding("", "", List.of(), "");
    } else {
      String normalized = content.trim();
      if (normalized.startsWith("```")) {
        normalized = normalized.replaceAll("^```[a-zA-Z]*", "").replaceAll("```$", "").trim();
      }
      try {
        JsonNode root = OBJECT_MAPPER.readTree(normalized);
        List<String> entities = new ArrayList<>();
        JsonNode entityNode = root.get("keyEntities");
        if (entityNode != null && entityNode.isArray()) {
          entityNode.forEach(item -> entities.add(item.asText("")));
        }
        entities.removeIf(String::isBlank);
        result =
            new Understanding(
                root.path("ocrText").asText(""),
                root.path("imageSummary").asText(""),
                List.copyOf(entities),
                root.path("focusedSummary").asText(""));
      } catch (Exception exception) {
        // 模型偶发非 JSON 输出时，把它作为 focused 摘要降级，不阻塞主答。
        log.warn("图片理解输出不是 JSON，按 focusedSummary 降级", exception);
        result =
            new Understanding(
                "",
                normalized.length() > 300 ? normalized.substring(0, 300) : normalized,
                List.of(),
                normalized);
      }
    }
    return result;
  }

  private UnderstandingContext toContext(
      AiChatImageEntity image, AiChatImageContextCache.CachedImageContext cached) {
    List<String> entities = parseEntities(image.getKeyEntities());
    return new UnderstandingContext(
        defaultText(image.getOcrText()),
        defaultText(image.getImageSummary()),
        entities,
        cached == null ? "" : cached.focusedSummary(),
        cached == null ? null : cached.imageVector());
  }

  private List<String> parseEntities(String entitiesJson) {
    List<String> result;
    if (entitiesJson == null || entitiesJson.isBlank()) {
      result = List.of();
    } else {
      try {
        JsonNode root = OBJECT_MAPPER.readTree(entitiesJson);
        List<String> entities = new ArrayList<>();
        root.forEach(item -> entities.add(item.asText("")));
        entities.removeIf(String::isBlank);
        result = List.copyOf(entities);
      } catch (Exception exception) {
        result = List.of();
      }
    }
    return result;
  }

  private String defaultText(String text) {
    return text == null ? "" : text;
  }

  private MimeType resolveMimeType(AiChatImageEntity image) {
    String key = image.getStorageKey() == null ? "" : image.getStorageKey().toLowerCase();
    MimeType result;
    if (key.endsWith(".jpg") || key.endsWith(".jpeg")) {
      result = MimeType.valueOf("image/jpeg");
    } else if (key.endsWith(".webp")) {
      result = MimeType.valueOf("image/webp");
    } else if (key.endsWith(".gif")) {
      result = MimeType.valueOf("image/gif");
    } else {
      result = MimeType.valueOf("image/png");
    }
    return result;
  }

  private void recordUsage(
      AiChatImageEntity image, ModelCallResult<?> result, TokenUsageType type) {
    TokenUsageRecorder recorder =
        tokenUsageRecorderProvider == null ? null : tokenUsageRecorderProvider.getIfAvailable();
    if (recorder == null || !result.hasUsage()) {
      return;
    }
    recorder.record(
        new TokenUsageRecord(
            result.model(),
            image.getUserId() == null ? "user:system" : "user:" + image.getUserId(),
            String.valueOf(image.getSessionId()),
            null,
            type,
            result.promptTokens(),
            result.completionTokens(),
            result.totalTokens(),
            true));
  }

  private record Understanding(
      String ocrText, String imageSummary, List<String> keyEntities, String focusedSummary) {}
}
