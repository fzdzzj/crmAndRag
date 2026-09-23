package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * AI 助手 SSE 生命周期。
 *
 * <p>负责模型流订阅、事件缓冲、协作式中止与助手消息终态落库。
 *
 * <p>tighten-pmd-residual-325 任务 6.4 批D：按职责拆为三个协作类——{@link AiChatStreamChunkProcessor}
 * （模型块处理管线）、{@link AiChatStreamFinalizer}（终态收尾与活跃流状态）、 {@link
 * AiChatStreamErrorRecovery}（错误重试/降级决策）；本类保留流订阅、Flux 装配与 公共 API 委托，构造器签名、对外事件序列与冻结契约不变，行为等价。既有 OOR
 * 豁免 （subscribe / finishStreamNormally）随方法原样迁移，未新增豁免。
 */
@Slf4j
@Component
public class AiChatStreamLifecycle {

  private final ChatClient.Builder chatClientBuilder;
  private final AiProperties aiProperties;
  private final AiChatStreamHeartbeat heartbeat;
  private final String modelName;
  private final AiChatStreamChunkProcessor chunkProcessor;
  private final AiChatStreamFinalizer finalizer;
  private final AiChatStreamErrorRecovery errorRecovery;

  /** ModelProvider 实现归 base；缺失时保留 CRM 原有 ChatClient 兼容路径。 */
  @Autowired(required = false)
  private ObjectProvider<ModelProvider> modelProviderProvider;

  /** 记忆加工是可选增强；测试或旁路组件缺失时不得影响主答。 */
  @Autowired(required = false)
  private AiMemoryOrchestrator memoryOrchestrator;

  /** Spring AI 兼容路径（含工具循环）也必须上报 usage，避免模型计量盲区。 */
  @Autowired(required = false)
  private ObjectProvider<com.slz.crm.platform.contract.TokenUsageRecorder>
      tokenUsageRecorderProvider;

  /**
   * 构造器参数签名保持不变（tighten-pmd-residual-325 任务 6.4 批D）：{@code aiMessageService} 在 批D拆分后仅由 Spring
   * 按位注入与两处测试构造消费，本体已无读取点（拆分前该字段即为死存赋值）； 按「签名契约只豁免、不删参」登记，避免为压数改动 Spring 装配面与测试构造。
   */
  @SuppressWarnings("PMD.UnusedFormalParameter") // 构造器按位注入的签名契约，删参等于改动 Spring 装配面
  public AiChatStreamLifecycle(
      ChatClient.Builder chatClientBuilder,
      AiProperties aiProperties,
      AiMessageService aiMessageService,
      AiStreamRegistry aiStreamRegistry,
      AiChatPromptService promptService,
      AiChatSseEventWriter eventWriter,
      AiChatStreamHeartbeat heartbeat,
      AiChatMetrics metrics,
      AiAssistantMessageStore assistantMessageStore,
      @Value("${spring.ai.chat.options.model:qwen-plus}") String modelName) {
    this.chatClientBuilder = chatClientBuilder;
    this.aiProperties = aiProperties;
    this.heartbeat = heartbeat;
    this.modelName = modelName;
    this.finalizer =
        new AiChatStreamFinalizer(
            eventWriter,
            assistantMessageStore,
            aiStreamRegistry,
            metrics,
            promptService,
            () -> memoryOrchestrator,
            () ->
                tokenUsageRecorderProvider == null
                    ? null
                    : tokenUsageRecorderProvider.getIfAvailable(),
            modelName);
    this.chunkProcessor = new AiChatStreamChunkProcessor(finalizer, metrics, modelName);
    this.errorRecovery =
        new AiChatStreamErrorRecovery(
            finalizer, aiProperties, aiStreamRegistry, metrics, this::subscribe);
  }

  /**
   * 订阅模型流。
   *
   * @param activeStream 当前活跃流
   * @param context 请求上下文
   */
  public void subscribe(AiStreamRegistry.ActiveStream activeStream, AiChatStreamContext context) {
    if (activeStream.isFinished()) {
      return;
    }
    activeStream.setContext(context);
    Long sessionId = context.sessionId();
    String effectiveModel = context.effectiveModel(modelName);
    ModelCallOptions callOptions = buildModelCallOptions(context, sessionId, effectiveModel);
    AiThinkTagStripper.StreamingStripper thinkStripper = AiThinkTagStripper.streaming();
    AtomicBoolean thinkingFinished = new AtomicBoolean(false);
    org.springframework.ai.chat.metadata.Usage[] usageHolder =
        new org.springframework.ai.chat.metadata.Usage[1];

    Flux<ChatResponse> responseFlux =
        openResponseFlux(context, sessionId, effectiveModel, callOptions);
    reactor.core.Disposable subscription =
        responseFlux
            .timeout(resolveLlmTimeout())
            .doOnNext(
                response ->
                    chunkProcessor.handleChatChunk(
                        activeStream,
                        response,
                        usageHolder,
                        context.thinking(),
                        thinkStripper,
                        thinkingFinished))
            .doOnComplete(
                () ->
                    finalizer.finishStreamNormally(
                        activeStream, context, effectiveModel, usageHolder, thinkingFinished))
            .doOnError(
                error -> errorRecovery.handleStreamError(activeStream, effectiveModel, error))
            .doOnCancel(() -> errorRecovery.handleStreamCancelled(activeStream, sessionId))
            .subscribe();
    activeStream.setSubscription(subscription);
    heartbeat.start(activeStream, () -> finalizer.sendHeartbeat(activeStream));
  }

  /** 解析 LLM 流式超时秒数（配置缺省 60s）。 */
  private Duration resolveLlmTimeout() {
    Integer timeoutSeconds = aiProperties.getLlmTimeoutSeconds();
    return Duration.ofSeconds(timeoutSeconds == null ? 60 : timeoutSeconds);
  }

  /**
   * 打开模型响应流：修正轮3后工具环由 ModelProvider 承载；Spring AI 仅保留 Provider 缺失时的兼容路径 （兼容路径只供测试/Provider
   * 未装配时兜底，不承载生产工具调用）。
   */
  private Flux<ChatResponse> openResponseFlux(
      AiChatStreamContext context,
      Long sessionId,
      String effectiveModel,
      ModelCallOptions callOptions) {
    ModelProvider modelProvider =
        modelProviderProvider == null ? null : modelProviderProvider.getIfAvailable();
    Flux<ChatResponse> responseFlux;
    if (modelProvider != null) {
      responseFlux = modelProvider.streamChat(new Prompt(context.messages()), callOptions);
    } else {
      ChatClient.ChatClientRequestSpec requestSpec =
          chatClientBuilder.build().prompt().messages(context.messages());
      if (context.thinking() || context.modelOverride() != null) {
        // 兼容路径使用 Spring AI 通用 options；Provider 差异不再由业务层承载。
        requestSpec = requestSpec.options(buildFallbackOptions(context, sessionId, effectiveModel));
      }
      if (!context.toolCallbacks().isEmpty()) {
        requestSpec =
            requestSpec
                .toolCallbacks(context.toolCallbacks())
                .toolContext(buildToolContext(context, sessionId));
      }
      responseFlux = requestSpec.stream().chatResponse();
    }
    return responseFlux;
  }

  /** 处理模型流错误，必要时降级备用模型（委托 {@link AiChatStreamErrorRecovery}，公共 API 不变）。 */
  public void handleStreamError(
      AiStreamRegistry.ActiveStream activeStream, String effectiveModel, Throwable error) {
    errorRecovery.handleStreamError(activeStream, effectiveModel, error);
  }

  /**
   * 用户取消或接管旧流（委托 {@link AiChatStreamErrorRecovery}，公共 API 不变）。
   *
   * @return 是否执行了取消
   */
  public boolean cancel(AiStreamRegistry.ActiveStream activeStream, Long sessionId) {
    return errorRecovery.cancel(activeStream, sessionId);
  }

  /** 客户端断开或超时兜底清理（委托 {@link AiChatStreamErrorRecovery}，公共 API 不变）。 */
  public void cleanup(AiStreamRegistry.ActiveStream activeStream, String reason) {
    errorRecovery.cleanup(activeStream, reason);
  }

  /** 发送 SSE 心跳（包内入口，委托 {@link AiChatStreamFinalizer}）。 */
  void sendHeartbeat(AiStreamRegistry.ActiveStream activeStream) {
    finalizer.sendHeartbeat(activeStream);
  }

  /** 装配中立模型调用参数；思考开关是真实请求参数而非 prompt 约束。 */
  private ModelCallOptions buildModelCallOptions(
      AiChatStreamContext context, Long sessionId, String effectiveModel) {
    return new ModelCallOptions(
        effectiveModel,
        context.thinking(),
        null,
        null,
        List.copyOf(context.toolCallbacks()),
        buildToolContext(context, sessionId),
        Map.of());
  }

  private Map<String, Object> buildToolContext(AiChatStreamContext context, Long sessionId) {
    Map<String, Object> toolContext = new HashMap<>();
    toolContext.put("sessionId", sessionId);
    toolContext.put("repairCounter", context.repairCounter());
    toolContext.put("references", context.referenceCollector());
    toolContext.put("user", context.currentUser());
    return toolContext;
  }

  private DefaultToolCallingChatOptions buildFallbackOptions(
      AiChatStreamContext context, Long sessionId, String effectiveModel) {
    DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
    options.setModel(effectiveModel);
    options.setToolCallbacks(context.toolCallbacks());
    options.setToolContext(buildToolContext(context, sessionId));
    return options;
  }
}
