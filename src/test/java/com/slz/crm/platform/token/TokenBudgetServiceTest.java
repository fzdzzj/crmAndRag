package com.slz.crm.platform.token;

import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.mapper.PlatformTokenUsageMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Token 请求前预算边界测试。
 */
@ExtendWith(MockitoExtension.class)
class TokenBudgetServiceTest {

    @Mock
    private PlatformTokenUsageMapper mapper;

    private TokenBudgetProperties properties;
    private SimpleMeterRegistry registry;

    private TokenBudgetService service;

    @BeforeEach
    void setUp() {
        properties = new TokenBudgetProperties();
        properties.setDailyLimit(100);
        properties.setMonthlyLimit(1000);
        registry = new SimpleMeterRegistry();
        service = new TokenBudgetService(mapper, properties, registry);
    }

    @Test
    void shouldRejectDailyBudgetBoundary() {
        TokenBudgetRequest request = new TokenBudgetRequest(
                TokenBudgetScope.USER, "user:1", TokenUsageType.CHAT, 20);
        when(mapper.selectList(any())).thenReturn(List.of(usage(90L)));

        TokenBudgetDecision decision = service.check(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.limit()).isEqualTo(100);
        assertThat(decision.used()).isEqualTo(90);
        assertThat(decision.remaining()).isZero();
        assertThat(decision.retryAfterSeconds()).isPositive();
        assertThat(registry.get("platform.token.budget.rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldAllowWithinDailyAndMonthlyBudget() {
        TokenBudgetRequest request = new TokenBudgetRequest(
                TokenBudgetScope.USER, "user:1", TokenUsageType.CHAT, 20);
        when(mapper.selectList(any())).thenReturn(List.of(usage(50L)));

        TokenBudgetDecision decision = service.check(request);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(30);
    }

    @Test
    void shouldBypassWhenDisabled() {
        properties.setEnabled(false);
        TokenBudgetRequest request = new TokenBudgetRequest(
                TokenBudgetScope.GLOBAL, "GLOBAL", TokenUsageType.EMBEDDING, 100000);

        TokenBudgetDecision decision = service.check(request);

        assertThat(decision.allowed()).isTrue();
    }

    private PlatformTokenUsageEntity usage(long totalTokens) {
        PlatformTokenUsageEntity entity = new PlatformTokenUsageEntity();
        entity.setTotalTokens(totalTokens);
        return entity;
    }
}
