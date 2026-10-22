package com.slz.crm.platform.token.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.result.Result;
import com.slz.crm.platform.mapper.PlatformTokenUsageMapper;
import com.slz.crm.platform.token.TokenUsageByUserPageVO;
import com.slz.crm.platform.token.TokenUsageByUserVO;
import com.slz.crm.platform.token.TokenUsageSummaryVO;
import com.slz.crm.platform.token.TokenUsageTotals;
import com.slz.crm.platform.token.TokenUsageTypeAggregate;
import com.slz.crm.platform.token.TokenUsageTypeShareVO;
import com.slz.crm.platform.token.TokenUsageUserAggregate;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 「AI 用量」只读接口单测（token-usage-admin-view，零 Spring / 零 DB）。
 *
 * <p>锁死：用户维度还原（真实名/系统/未知来源/已删除用户）、占比、分页偏移、usage_type 分组归并与排序、参数校验拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AiTokenUsageControllerTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 12, 0);

  @Mock private PlatformTokenUsageMapper tokenUsageMapper;

  @Mock private UserMapper userMapper;

  private AiTokenUsageController controller;

  @BeforeEach
  void setUp() {
    controller =
        new AiTokenUsageController(
            tokenUsageMapper,
            userMapper,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  @Test
  @DisplayName("by-user：还原用户名/系统/未知来源/已删除用户，并算出占比与分页偏移")
  void byUserRestoresDisplayDimensionsAndShares() {
    List<TokenUsageUserAggregate> rows =
        List.of(
            row("user:901", 100L, 50L, 150L, 2L, 0L),
            row("user:system", 10L, 5L, 15L, 1L, 1L),
            row("user:999", 5L, 5L, 10L, 1L, 0L),
            row("user:unknown", 1L, 1L, 2L, 1L, 1L));
    when(tokenUsageMapper.countUsers(any(), any(), isNull())).thenReturn(4L);
    when(tokenUsageMapper.selectUserAggregates(any(), any(), isNull(), eq(2), eq(0L)))
        .thenReturn(rows);
    when(tokenUsageMapper.selectTotals(any(), any(), isNull()))
        .thenReturn(totals(116L, 61L, 177L, 5L, 2L, 4L));
    UserEntity user = new UserEntity();
    user.setId(901L);
    user.setRealName("张三");
    when(userMapper.selectBatchIds(any())).thenReturn(List.of(user));

    Result<TokenUsageByUserPageVO> result = controller.byUser(null, null, null, 1, 2);

    assertThat(result.getCode()).isEqualTo(1);
    TokenUsageByUserPageVO page = result.getData();
    assertThat(page.getTotal()).isEqualTo(4L);
    assertThat(page.getRecords()).hasSize(4);
    TokenUsageByUserVO first = page.getRecords().get(0);
    assertThat(first.getUserId()).isEqualTo(901L);
    assertThat(first.getUserName()).isEqualTo("张三");
    assertThat(first.getSharePct()).isEqualTo(84.75);
    assertThat(page.getRecords().get(1).getUserName()).isEqualTo("系统");
    assertThat(page.getRecords().get(2).getUserName()).isEqualTo("已删除用户(#999)");
    assertThat(page.getRecords().get(3).getUserName()).isEqualTo("未知来源");
    verify(tokenUsageMapper).selectUserAggregates(any(), any(), isNull(), eq(2), eq(0L));
  }

  @Test
  @DisplayName("分页：pageNum=3/pageSize=20 → SQL 偏移 40，total 为去重用户数")
  void paginationUsesZeroBasedOffset() {
    when(tokenUsageMapper.countUsers(any(), any(), isNull())).thenReturn(40L);
    when(tokenUsageMapper.selectUserAggregates(any(), any(), isNull(), eq(20), eq(40L)))
        .thenReturn(List.of());
    when(tokenUsageMapper.selectTotals(any(), any(), isNull()))
        .thenReturn(totals(0L, 0L, 0L, 0L, 0L, 0L));

    Result<TokenUsageByUserPageVO> result = controller.byUser(null, null, null, 3, 20);

    assertThat(result.getData().getTotal()).isEqualTo(40L);
    assertThat(result.getData().getRecords()).isEmpty();
    verify(tokenUsageMapper).selectUserAggregates(any(), any(), isNull(), eq(20), eq(40L));
  }

  @Test
  @DisplayName("summary：CHAT/chat 两种库内口径归并为一组，按总 token 降序并算占比")
  void summaryMergesUsageTypeCasingAndOrdersByTotal() {
    when(tokenUsageMapper.selectTotals(any(), any(), isNull()))
        .thenReturn(totals(60L, 40L, 100L, 4L, 1L, 2L));
    when(tokenUsageMapper.selectTypeAggregates(any(), any(), isNull()))
        .thenReturn(
            List.of(
                type("CHAT", 60L, 2L, 0L),
                type("chat", 40L, 1L, 1L),
                type("EMBEDDING", 0L, 1L, 0L)));

    Result<TokenUsageSummaryVO> result = controller.summary(null, null, null);

    TokenUsageSummaryVO summary = result.getData();
    assertThat(summary.getTotalTokens()).isEqualTo(100L);
    assertThat(summary.getCalls()).isEqualTo(4L);
    assertThat(summary.getUserCount()).isEqualTo(2L);
    assertThat(summary.getStartTime()).isEqualTo(NOW.minusDays(30).toString());
    assertThat(summary.getEndTime()).isEqualTo(NOW.toString());
    List<TokenUsageTypeShareVO> types = summary.getUsageTypes();
    assertThat(types).hasSize(2);
    assertThat(types.get(0).getUsageType()).isEqualTo("chat");
    assertThat(types.get(0).getTotalTokens()).isEqualTo(100L);
    assertThat(types.get(0).getCalls()).isEqualTo(3L);
    assertThat(types.get(0).getFailedCalls()).isEqualTo(1L);
    assertThat(types.get(0).getSharePct()).isEqualTo(100.0);
    assertThat(types.get(1).getUsageType()).isEqualTo("embedding");
    assertThat(types.get(1).getSharePct()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("参数越界一律 IllegalArgumentException：pageNum/pageSize/usageType/区间倒置")
  void rejectsInvalidParameters() {
    assertThatThrownBy(() -> controller.byUser(null, null, null, 0, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageNum");
    assertThatThrownBy(() -> controller.byUser(null, null, null, 1, 201))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageSize");
    assertThatThrownBy(() -> controller.byUser(null, null, "image", 1, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("usageType");
    assertThatThrownBy(() -> controller.summary("2026-09-10T00:00:00", "2026-09-01T00:00:00", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须早于");
  }

  @Test
  @DisplayName("校验异常统一映射 VALIDATION(96007)，不新增错误码")
  void validationExceptionMapsTo96007() {
    Result<Void> result = controller.handleValidation(new IllegalArgumentException("pageSize 越界"));
    assertThat(result.getCode()).isEqualTo(96007);
    assertThat(result.getMsg()).isEqualTo("pageSize 越界");
  }

  private static TokenUsageUserAggregate row(
      String ref, Long prompt, Long completion, Long total, Long calls, Long failed) {
    TokenUsageUserAggregate aggregate = new TokenUsageUserAggregate();
    aggregate.setUserRef(ref);
    aggregate.setPromptTokens(prompt);
    aggregate.setCompletionTokens(completion);
    aggregate.setTotalTokens(total);
    aggregate.setCalls(calls);
    aggregate.setFailedCalls(failed);
    aggregate.setLastCallTime(NOW.minusHours(1));
    return aggregate;
  }

  private static TokenUsageTotals totals(
      Long prompt, Long completion, Long total, Long calls, Long failed, Long users) {
    TokenUsageTotals totals = new TokenUsageTotals();
    totals.setPromptTokens(prompt);
    totals.setCompletionTokens(completion);
    totals.setTotalTokens(total);
    totals.setCalls(calls);
    totals.setFailedCalls(failed);
    totals.setUserCount(users);
    return totals;
  }

  private static TokenUsageTypeAggregate type(String name, Long total, Long calls, Long failed) {
    TokenUsageTypeAggregate aggregate = new TokenUsageTypeAggregate();
    aggregate.setUsageType(name);
    aggregate.setTotalTokens(total);
    aggregate.setCalls(calls);
    aggregate.setFailedCalls(failed);
    return aggregate;
  }
}
