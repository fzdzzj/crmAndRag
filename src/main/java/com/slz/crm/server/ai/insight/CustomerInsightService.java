package com.slz.crm.server.ai.insight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 客户 360° AI 智能商业诊断服务（add-ai-customer-360-insight 任务 2.1/2.2）。
 *
 * <p>流程：公司级 DataScope 守卫（复用 {@code BusinessRecordAccessService#assertCanReadCompany}，跨部门无权访问抛
 * 12002）→ 多实体快照聚合 → 确定性规则评分 → {@link ModelProvider} 生成摘要/风险点/拜访建议。
 *
 * <p>红线：模型未装配、超时、外呼或解析异常一律安全降级为规则文本，绝不抛 500；纯只读零写路径。
 */
@Component
public class CustomerInsightService {

  private static final Logger LOG = LoggerFactory.getLogger(CustomerInsightService.class);

  private static final int DEFAULT_TIMEOUT_MS = 5000;

  private static final int MAX_RISK_ALERTS = 5;

  private static final int MAX_ACTION_SUGGESTIONS = 3;

  private static final int MAX_SUMMARY_LENGTH = 120;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String SYSTEM_PROMPT =
      """
      你是 CRM 客户健康诊断专家。根据给定的客户多维快照与规则预警，只输出一个标准 JSON 对象，格式严格为：
      {"summary":"一句话客户全景商业画像摘要","riskAlerts":["关键风险1","关键风险2","关键风险3"],"actionSuggestions":["建议1","建议2"]}
      要求：summary 不超过 60 字且应包含健康分结论；riskAlerts 3~5 条；actionSuggestions 2~4 条；严禁输出 Markdown 标记或解释性文字。
      """;

  private final CustomerInsightCollectorSupport collector;

  private final CustomerInsightRuleEngine ruleEngine;

  private final CustomerCompanyMapper customerCompanyMapper;

  private final BusinessRecordAccessService recordAccessService;

  private final ObjectProvider<ModelProvider> modelProviderProvider;

  private final ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  private final Executor llmAuxExecutor;

  /** 构造注入（{@code llmAuxExecutor} 与检索侧同一异步外呼池，超时口径见 {@code callModel}）。 */
  public CustomerInsightService(
      CustomerInsightCollectorSupport collector,
      CustomerInsightRuleEngine ruleEngine,
      CustomerCompanyMapper customerCompanyMapper,
      BusinessRecordAccessService recordAccessService,
      ObjectProvider<ModelProvider> modelProviderProvider,
      ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      @Qualifier("llmAuxTaskExecutor") Executor llmAuxExecutor) {
    this.collector = collector;
    this.ruleEngine = ruleEngine;
    this.customerCompanyMapper = customerCompanyMapper;
    this.recordAccessService = recordAccessService;
    this.modelProviderProvider = modelProviderProvider;
    this.tokenUsageRecorderProvider = tokenUsageRecorderProvider;
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.llmAuxExecutor = llmAuxExecutor;
  }

  /**
   * 生成目标客户的 360° 商业诊断。
   *
   * @param companyId 客户公司 ID
   * @return 结构化诊断结果；模型不可用时安全回退规则文本
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 聚合/守卫多源，数据侧异常按业务错误上抛，LLM 侧恒定回退
  public CustomerInsightResult diagnose(Long companyId) {
    if (companyId == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "companyId 不能为空");
    }
    CustomerCompanyEntity company = customerCompanyMapper.selectById(companyId);
    if (company == null) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS);
    }
    recordAccessService.assertCanReadCompany(company);

    CustomerInsightSnapshot snapshot = collector.collect(company);
    CustomerInsightRuleEngine.RuleResult rule = ruleEngine.evaluate(snapshot);
    LlmInsight llm = tryLlm(snapshot, rule);
    CustomerInsightResult result;
    if (llm != null) {
      result =
          new CustomerInsightResult(
              companyId,
              company.getCompanyName(),
              rule.healthScore(),
              rule.healthLevel(),
              llm.summary(),
              llm.riskAlerts(),
              llm.actionSuggestions(),
              rule.dimensionScores(),
              rule.ruleWarnings(),
              true);
    } else {
      result = buildFallbackResult(company, rule);
    }
    return result;
  }

  /** LLM 增强尝试：任何异常恒定回退规则文本（红线：绝不抛 500）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // LLM 外呼多源，失败恒定回退规则文本
  private LlmInsight tryLlm(
      CustomerInsightSnapshot snapshot, CustomerInsightRuleEngine.RuleResult rule) {
    ModelProvider provider =
        modelProviderProvider == null ? null : modelProviderProvider.getIfAvailable();
    LlmInsight result = null;
    if (provider != null) {
      try {
        ModelCallResult<String> call = callModel(provider, snapshot, rule);
        LlmInsight parsed = parse(call == null ? null : call.content());
        if (parsed == null) {
          LOG.info("客户诊断 LLM 输出不可用或解析失败，回退规则文本");
          recordUsage(call, false);
        } else {
          recordUsage(call, true);
          result = parsed;
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        LOG.warn("客户诊断 LLM 被中断，回退规则文本: {}", exception.getMessage());
        recordUsage(null, false);
      } catch (Exception exception) {
        LOG.warn("客户诊断 LLM 调用失败或超时，回退规则文本: {}", exception.getMessage());
        recordUsage(null, false);
      }
    }
    return result;
  }

  private ModelCallResult<String> callModel(
      ModelProvider provider,
      CustomerInsightSnapshot snapshot,
      CustomerInsightRuleEngine.RuleResult rule)
      throws Exception {
    List<Message> messages =
        List.of(
            new SystemMessage(SYSTEM_PROMPT),
            new UserMessage(
                OBJECT_MAPPER.writeValueAsString(
                    CustomerInsightLlmPayloadSupport.build(snapshot, rule))));
    ModelCallOptions options = new ModelCallOptions(null, false, 0.2d, 512, null, null, Map.of());
    CompletableFuture<ModelCallResult<String>> future =
        CompletableFuture.supplyAsync(
            () -> provider.chat(new Prompt(messages), options), llmAuxExecutor);
    try {
      return future.get(resolveTimeoutMs(), TimeUnit.MILLISECONDS);
    } finally {
      future.cancel(true);
    }
  }

  /** 解析 LLM 结构化输出；格式不符/缺失字段返回 null 触发规则回退。 */
  private LlmInsight parse(String output) {
    LlmInsight result = null;
    if (output != null && !output.isBlank()) {
      try {
        JsonNode root = OBJECT_MAPPER.readTree(output);
        String summary = textOf(root.get("summary"));
        List<String> risks = stringListOf(root.get("riskAlerts"));
        List<String> actions = stringListOf(root.get("actionSuggestions"));
        if (summary != null && !risks.isEmpty() && !actions.isEmpty()) {
          result = new LlmInsight(summary, risks, actions);
        }
      } catch (JsonProcessingException exception) {
        LOG.info("客户诊断 LLM 输出 JSON 解析失败，回退规则文本");
      }
    }
    return result;
  }

  /** 规则安全回退结果：摘要/风险点/建议全部由规则层确定性文本生成。 */
  private CustomerInsightResult buildFallbackResult(
      CustomerCompanyEntity company, CustomerInsightRuleEngine.RuleResult rule) {
    List<String> riskAlerts = cap(rule.ruleWarnings(), MAX_RISK_ALERTS);
    String summary =
        String.format(
            "客户「%s」当前健康分 %d，等级 %s；%s",
            company.getCompanyName(),
            rule.healthScore(),
            rule.healthLevel().name(),
            riskAlerts.isEmpty() ? "客户整体状态平稳" : riskAlerts.get(0));
    return new CustomerInsightResult(
        company.getId(),
        company.getCompanyName(),
        rule.healthScore(),
        rule.healthLevel(),
        summary,
        riskAlerts,
        deriveSuggestions(rule),
        rule.dimensionScores(),
        rule.ruleWarnings(),
        false);
  }

  /** 由规则快照确定性推导下一步拜访建议（回退路径专用）。 */
  private List<String> deriveSuggestions(CustomerInsightRuleEngine.RuleResult rule) {
    List<String> suggestions = new ArrayList<>();
    List<String> warnings = rule.ruleWarnings();
    if (warnings.stream().anyMatch(w -> w.contains("未跟进"))) {
      suggestions.add("建议尽快安排客户拜访或电话回访，恢复跟进节奏");
    }
    if (warnings.stream().anyMatch(w -> w.contains("回款") || w.contains("款项"))) {
      suggestions.add("建议核对合同回款进度，优先跟进待确认与逾期款项");
    }
    if (warnings.stream().anyMatch(w -> w.contains("停滞"))) {
      suggestions.add("建议推动停滞商机阶段流转，明确下一步成交动作与责任人");
    }
    if (warnings.stream().anyMatch(w -> w.contains("决策人"))) {
      suggestions.add("建议补充关键决策人联系人，提升客户关系覆盖");
    }
    if (warnings.stream().anyMatch(w -> w.contains("超期"))) {
      suggestions.add("建议立即处理超期高优待办任务，避免客户跟进断档");
    }
    if (suggestions.isEmpty()) {
      suggestions.add("建议保持定期跟进，持续巩固客户关系");
    }
    return cap(suggestions, MAX_ACTION_SUGGESTIONS);
  }

  private String textOf(JsonNode node) {
    String result = null;
    if (node != null && node.isTextual()) {
      String text = node.asText().trim();
      if (!text.isEmpty()) {
        result = text.length() > MAX_SUMMARY_LENGTH ? text.substring(0, MAX_SUMMARY_LENGTH) : text;
      }
    }
    return result;
  }

  private List<String> stringListOf(JsonNode node) {
    List<String> result = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual()) {
          String text = item.asText().trim();
          if (!text.isEmpty()) {
            result.add(text);
          }
        }
      }
    }
    return result;
  }

  private List<String> cap(List<String> source, int max) {
    List<String> result = List.of();
    if (source != null && !source.isEmpty()) {
      result = source.size() <= max ? List.copyOf(source) : List.copyOf(source.subList(0, max));
    }
    return result;
  }

  private void recordUsage(ModelCallResult<String> result, boolean success) {
    TokenUsageRecorder recorder =
        tokenUsageRecorderProvider == null ? null : tokenUsageRecorderProvider.getIfAvailable();
    if (recorder == null) {
      return;
    }
    String model = result == null ? "unknown" : result.model();
    recorder.record(
        new TokenUsageRecord(
            model,
            currentUserRef(),
            null,
            null,
            TokenUsageType.SUMMARY,
            result == null ? null : result.promptTokens(),
            result == null ? null : result.completionTokens(),
            result == null ? null : result.totalTokens(),
            success));
  }

  private String currentUserRef() {
    UserContext user = UserContextHolder.current();
    return user == null ? "user:system" : user.userIdRef();
  }

  private long resolveTimeoutMs() {
    DynamicConfigService config =
        dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable();
    Long configured =
        config == null
            ? null
            : config.get("ai.insight.llm.timeout-ms", Long.class, (long) DEFAULT_TIMEOUT_MS);
    return configured == null || configured < 1 ? DEFAULT_TIMEOUT_MS : configured;
  }

  /** LLM 结构化洞察（内部承载，null = 规则回退）。 */
  private record LlmInsight(
      String summary, List<String> riskAlerts, List<String> actionSuggestions) {}
}
