package com.slz.crm.platform.token;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.platform.mapper.PlatformTokenUsageMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 请求前 Token 预算检查服务。
 *
 * <p>调用方在发起 LLM 请求前传入预估 token；请求完成后仍必须通过 {@code TokenUsageRecorder} 上报实际用量。这里只做预算闸门，不产数。
 */
@Service
public class TokenBudgetService {

  private final PlatformTokenUsageMapper tokenUsageMapper;
  private final TokenBudgetProperties properties;
  private final MeterRegistry meterRegistry;
  private final Clock clock;

  /**
   * 使用系统时钟构造预算服务。
   *
   * @param tokenUsageMapper 计量明细 Mapper
   * @param properties 预算配置
   * @param meterRegistry Micrometer 注册表
   */
  // 集成修正：多构造器（此 public + 下方 package-private 带 Clock 版）需显式 @Autowired 指定注入入口，
  // 否则 Spring 回退找无参构造器报 "No default constructor found"、上下文加载失败。
  @Autowired
  public TokenBudgetService(
      PlatformTokenUsageMapper tokenUsageMapper,
      TokenBudgetProperties properties,
      MeterRegistry meterRegistry) {
    this(tokenUsageMapper, properties, meterRegistry, Clock.systemDefaultZone());
  }

  /**
   * 使用显式时钟构造预算服务，便于测试周期边界。
   *
   * @param tokenUsageMapper 计量明细 Mapper
   * @param properties 预算配置
   * @param meterRegistry Micrometer 注册表
   * @param clock 周期计算时钟
   */
  TokenBudgetService(
      PlatformTokenUsageMapper tokenUsageMapper,
      TokenBudgetProperties properties,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.tokenUsageMapper = tokenUsageMapper;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
  }

  /**
   * 检查当前预算。
   *
   * @param request 预算请求
   * @return 允许或拒绝决策
   */
  public TokenBudgetDecision check(TokenBudgetRequest request) {
    Objects.requireNonNull(request, "TokenBudgetRequest 不能为空");
    Objects.requireNonNull(request.scope(), "预算范围不能为空");
    if (request.estimatedTokens() < 0) {
      throw new IllegalArgumentException("预估token不能小于0");
    }
    TokenBudgetDecision result;
    if (!properties.isEnabled()) {
      result =
          TokenBudgetDecision.allow(Long.MAX_VALUE, 0, request.estimatedTokens(), Long.MAX_VALUE);
    } else {
      LocalDateTime now = LocalDateTime.now(clock);
      TokenBudgetDecision dailyDecision =
          evaluate(
              request,
              now,
              now.toLocalDate().atStartOfDay(),
              now.toLocalDate().plusDays(1).atStartOfDay(),
              properties.getDailyLimit());
      if (!dailyDecision.allowed()) {
        result = dailyDecision;
      } else {
        YearMonth month = YearMonth.from(now);
        TokenBudgetDecision monthlyDecision =
            evaluate(
                request,
                now,
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay(),
                properties.getMonthlyLimit());
        if (!monthlyDecision.allowed()) {
          result = monthlyDecision;
        } else {
          // 同时通过日/月预算时，暴露更紧的剩余额度，避免调用方误用月度余量。
          long limit = Math.min(dailyDecision.limit(), monthlyDecision.limit());
          long used = Math.max(dailyDecision.used(), monthlyDecision.used());
          long remaining = Math.min(dailyDecision.remaining(), monthlyDecision.remaining());
          result = TokenBudgetDecision.allow(limit, used, request.estimatedTokens(), remaining);
        }
      }
    }
    return result;
  }

  private long usedTokens(TokenBudgetRequest request, LocalDateTime start, LocalDateTime end) {
    LambdaQueryWrapper<PlatformTokenUsageEntity> query =
        new LambdaQueryWrapper<PlatformTokenUsageEntity>()
            .ge(PlatformTokenUsageEntity::getCreateTime, start)
            .lt(PlatformTokenUsageEntity::getCreateTime, end);
    switch (request.scope()) {
      case USER -> query.eq(PlatformTokenUsageEntity::getUserIdRef, request.scopeId());
      case SESSION -> query.eq(PlatformTokenUsageEntity::getSessionId, request.scopeId());
      case KNOWLEDGE_BASE ->
          query.eq(
              PlatformTokenUsageEntity::getKnowledgeBaseId,
              parseKnowledgeBaseId(request.scopeId()));
      case GLOBAL -> {
        // 全局预算不加 scope 过滤。
      }
    }
    if (request.usageType() != null) {
      query.eq(PlatformTokenUsageEntity::getUsageType, request.usageType());
    }
    List<PlatformTokenUsageEntity> usages = tokenUsageMapper.selectList(query);
    return usages.stream()
        .mapToLong(
            usage ->
                usage.getTotalTokens() == null || usage.getTotalTokens() < 0
                    ? 0
                    : usage.getTotalTokens())
        .sum();
  }

  private Long parseKnowledgeBaseId(String scopeId) {
    try {
      return Long.parseLong(scopeId);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("知识库预算 scopeId 必须是数字ID", exception);
    }
  }

  private TokenBudgetDecision evaluate(
      TokenBudgetRequest request,
      LocalDateTime now,
      LocalDateTime start,
      LocalDateTime end,
      long limit) {
    long used = usedTokens(request, start, end);
    // remaining 表示扣除本次预估后的余量，调用方能直接判断还能否继续发起后续调用。
    long remaining = Math.max(0, limit - used - request.estimatedTokens());
    TokenBudgetDecision result;
    if (used >= limit || request.estimatedTokens() > remaining) {
      long retryAfterSeconds = Math.max(1, Duration.between(now, end).getSeconds());
      meterRegistry
          .counter(
              "platform.token.budget.rejected",
              "scope",
              request.scope().name(),
              "type",
              request.usageType() == null ? "all" : request.usageType().wireName())
          .increment();
      result =
          new TokenBudgetDecision(
              false,
              limit,
              used,
              request.estimatedTokens(),
              remaining,
              retryAfterSeconds,
              "TOKEN_BUDGET_EXCEEDED");
    } else {
      result = TokenBudgetDecision.allow(limit, used, request.estimatedTokens(), remaining);
    }
    return result;
  }
}
