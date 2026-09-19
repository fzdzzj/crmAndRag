package com.slz.crm.platform.security;

import com.slz.crm.platform.mapper.PlatformContentSecurityEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 不可信内容分级与提示注入检测。
 *
 * <p>规则先做保守的字符串匹配，策略版本固定为 v1；后续接入模型分类时不得删除 命中规则与处置动作，保证审计可比。持久化失败不阻断业务判断。
 */
@Service
public class ContentSecurityService {

  private static final Logger log = LoggerFactory.getLogger(ContentSecurityService.class);
  private static final String POLICY_VERSION = "v1";
  private static final List<String> PROMPT_INJECTION_PATTERNS =
      List.of(
          "ignore previous instructions",
          "ignore all previous instructions",
          "reveal your system prompt",
          "developer mode",
          "忽略以上指令",
          "忽略之前指令",
          "泄露系统提示");
  private static final List<String> SENSITIVE_CREDENTIAL_PATTERNS =
      List.of("api_key=", "apikey:", "password=", "secret_key=", "private key -----begin");

  private final ObjectProvider<PlatformContentSecurityEventMapper> mapperProvider;
  private final MeterRegistry meterRegistry;

  /**
   * 构造内容安全服务。
   *
   * @param mapperProvider 安全事件 Mapper 提供器
   * @param meterRegistry Micrometer 注册表
   */
  public ContentSecurityService(
      ObjectProvider<PlatformContentSecurityEventMapper> mapperProvider,
      MeterRegistry meterRegistry) {
    this.mapperProvider = mapperProvider;
    this.meterRegistry = meterRegistry;
  }

  /**
   * 评估内容风险。
   *
   * @param sourceType 内容来源
   * @param sourceId 来源业务 ID
   * @param content 原始内容
   * @return 分级结果
   */
  public ContentSecurityResult evaluate(
      ContentSourceType sourceType, String sourceId, String content) {
    ContentSecurityResult result = classify(content);
    recordEvent(sourceType, sourceId, result, content);
    return result;
  }

  /**
   * 包装允许进入模型上下文的不可信内容。
   *
   * @param content 原始内容
   * @param result 安全检查结果
   * @return 原文或降级包装文本
   * @throws ContentRiskBlockedException 高风险内容必须拦截
   */
  public String wrapUntrustedContent(String content, ContentSecurityResult result) {
    if (result.isBlocked()) {
      throw new ContentRiskBlockedException(result);
    }
    if (result.action() == ContentSecurityAction.DEGRADE) {
      return "<untrusted_content risk=\""
          + result.riskLevel().name().toLowerCase(Locale.ROOT)
          + "\">\n"
          + content
          + "\n</untrusted_content>";
    }
    return content;
  }

  private ContentSecurityResult classify(String content) {
    String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
    for (String pattern : PROMPT_INJECTION_PATTERNS) {
      if (normalized.contains(pattern)) {
        return new ContentSecurityResult(
            ContentRiskLevel.HIGH,
            ContentSecurityAction.BLOCK,
            "prompt-injection",
            "内容试图覆盖或提取系统指令");
      }
    }
    for (String pattern : SENSITIVE_CREDENTIAL_PATTERNS) {
      if (normalized.contains(pattern)) {
        return new ContentSecurityResult(
            ContentRiskLevel.MEDIUM,
            ContentSecurityAction.DEGRADE,
            "sensitive-credential",
            "内容包含疑似凭据");
      }
    }
    return new ContentSecurityResult(
        ContentRiskLevel.SAFE, ContentSecurityAction.ALLOW, "default", "未命中安全规则");
  }

  private void recordEvent(
      ContentSourceType sourceType, String sourceId, ContentSecurityResult result, String content) {
    meterRegistry
        .counter(
            "platform.content.security",
            "source",
            sourceType.name(),
            "risk",
            result.riskLevel().name(),
            "action",
            result.action().name())
        .increment();
    try {
      PlatformContentSecurityEventMapper mapper = mapperProvider.getIfAvailable();
      if (mapper == null) {
        return;
      }
      PlatformContentSecurityEventEntity entity = new PlatformContentSecurityEventEntity();
      entity.setSourceType(sourceType.name());
      entity.setSourceId(sourceId);
      entity.setRiskLevel(result.riskLevel().name());
      entity.setAction(result.action().name());
      entity.setMatchedRule(result.matchedRule());
      entity.setReason(result.reason());
      // 不持久化原始内容，避免敏感数据复制扩散；仅保留长度与策略版本。
      entity.setPayload(
          "{\"policyVersion\":\""
              + POLICY_VERSION
              + "\",\"contentLength\":"
              + (content == null ? 0 : content.length())
              + "}");
      LocalDateTime now = LocalDateTime.now();
      entity.setCreateTime(now);
      entity.setUpdateTime(now);
      mapper.insert(entity);
    } catch (Exception exception) {
      log.warn("内容安全事件落库失败: sourceType={}, sourceId={}", sourceType, sourceId, exception);
    }
  }
}
