package com.slz.crm.server.ai.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 客户健康确定性规则评分引擎单测（add-ai-customer-360-insight 任务 5.1）。
 *
 * <p>覆盖不同跟进间隔、商机阶段停滞/审批驳回、逾期未回款/待确认款项、关键联系人与高优任务超期 的健康分确定性量化与评级边界。
 */
class CustomerInsightRuleEngineTest {

  private final CustomerInsightRuleEngine engine = new CustomerInsightRuleEngine();

  @Test
  void frequentFollowUp_noRisks_scores90Excellent() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(3, 2, LocalDateTime.now().minusDays(3), 3, "产品演示会"),
            opportunity(0, BigDecimal.ZERO, 0, 0),
            contractPayment(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
            taskContact(0, 0, 2, 1));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    assertThat(result.healthScore()).isEqualTo(90);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.EXCELLENT);
    assertThat(result.ruleWarnings()).isEmpty();
  }

  @Test
  void followUpOver30Days_scores69Warning() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(5, 0, LocalDateTime.now().minusDays(35), 35, "电话沟通"),
            opportunity(0, BigDecimal.ZERO, 0, 0),
            contractPayment(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
            taskContact(0, 0, 2, 1));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    assertThat(result.healthScore()).isEqualTo(69);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.WARNING);
    assertThat(result.ruleWarnings()).contains("已超过30天未跟进该客户，流失风险高");
  }

  @Test
  void followUp15To30Days_scores77Healthy() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(3, 0, LocalDateTime.now().minusDays(20), 20, "邮件往来"),
            opportunity(0, BigDecimal.ZERO, 0, 0),
            contractPayment(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
            taskContact(0, 0, 2, 1));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    assertThat(result.healthScore()).isEqualTo(77);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.HEALTHY);
    assertThat(result.ruleWarnings()).contains("已超过15天未跟进该客户，活跃度明显下降");
  }

  @Test
  void stagnantOpportunityWithRejection_scores83Healthy() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(2, 2, LocalDateTime.now().minusDays(5), 5, "线下拜访"),
            opportunity(1, new BigDecimal("100000"), 40, 1),
            contractPayment(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
            taskContact(0, 0, 2, 1));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    // 活跃 100*0.3 + 商机 50*0.25 + 合同 80*0.25 + 任务 100*0.2 = 30+12.5+20+20 = 82.5 → 83
    assertThat(result.healthScore()).isEqualTo(83);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.HEALTHY);
    assertThat(result.ruleWarnings()).contains("存在商机阶段停滞超过30天，推进严重受阻");
    assertThat(result.ruleWarnings()).contains("商机阶段审批被驳回/退回修改 1 次");
  }

  @Test
  void overdueAndPendingPayment_scores80Healthy() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(2, 2, LocalDateTime.now().minusDays(3), 3, "方案交流"),
            opportunity(0, BigDecimal.ZERO, 0, 0),
            contractPayment(
                1,
                new BigDecimal("10000"),
                BigDecimal.ZERO,
                new BigDecimal("500"),
                new BigDecimal("3000"),
                1),
            taskContact(0, 0, 2, 1));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    // 活跃 100*0.3 + 商机 80*0.25 + 合同 40*0.25 + 任务 100*0.2 = 30+20+10+20 = 80
    assertThat(result.healthScore()).isEqualTo(80);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.HEALTHY);
    assertThat(result.ruleWarnings()).contains("存在逾期未回款款项，资金回笼风险");
    assertThat(result.ruleWarnings()).contains("存在待确认回款款项");
  }

  @Test
  void missingKeyContactWithOverdueHighTask_scores82Healthy() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(2, 2, LocalDateTime.now().minusDays(3), 3, "会议"),
            opportunity(0, BigDecimal.ZERO, 0, 0),
            contractPayment(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
            taskContact(1, 1, 2, 0));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    // 活跃 100*0.3 + 商机 80*0.25 + 合同 80*0.25 + 任务 60*0.2 = 30+20+20+12 = 82
    assertThat(result.healthScore()).isEqualTo(82);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.HEALTHY);
    assertThat(result.ruleWarnings()).contains("客户缺少关键决策人联系人");
    assertThat(result.ruleWarnings()).contains("存在高优跟进任务超期未完成，客户跟进可能断档");
  }

  @Test
  void allDimensionsCritical_scores42Critical() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(0, 0, null, -1, ""),
            opportunity(2, new BigDecimal("50000"), 40, 3),
            contractPayment(
                2,
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                new BigDecimal("2000"),
                new BigDecimal("18000"),
                2),
            taskContact(0, 0, 0, 0));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    // 活跃 40*0.3 + 商机 30*0.25 + 合同 40*0.25 + 任务 60*0.2 = 12+7.5+10+12 = 41.5 → 42
    assertThat(result.healthScore()).isEqualTo(42);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.CRITICAL);
    assertThat(result.ruleWarnings()).contains("该客户暂无跟进活动记录，客户关系处于空白期");
    assertThat(result.ruleWarnings()).contains("客户无联系人覆盖，关系网络薄弱");
  }

  @Test
  void dimensionScoresCarryWeightsAndNames() {
    CustomerInsightSnapshot snapshot =
        snapshot(
            activity(2, 2, LocalDateTime.now().minusDays(3), 3, "拜访"),
            opportunity(0, BigDecimal.ZERO, 0, 0),
            contractPayment(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
            taskContact(0, 0, 2, 1));

    CustomerInsightRuleEngine.RuleResult result = engine.evaluate(snapshot);

    assertThat(result.dimensionScores())
        .containsExactlyInAnyOrder(
            new CustomerInsightDimensionScore(
                CustomerInsightRuleEngine.DIMENSION_ACTIVE,
                100,
                CustomerInsightRuleEngine.ACTIVE_WEIGHT),
            new CustomerInsightDimensionScore(
                CustomerInsightRuleEngine.DIMENSION_OPPORTUNITY,
                80,
                CustomerInsightRuleEngine.OPPORTUNITY_WEIGHT),
            new CustomerInsightDimensionScore(
                CustomerInsightRuleEngine.DIMENSION_CONTRACT,
                80,
                CustomerInsightRuleEngine.CONTRACT_WEIGHT),
            new CustomerInsightDimensionScore(
                CustomerInsightRuleEngine.DIMENSION_TASK,
                100,
                CustomerInsightRuleEngine.TASK_WEIGHT));
  }

  private CustomerInsightSnapshot snapshot(
      CustomerInsightSnapshot.ActivityFacts activity,
      CustomerInsightSnapshot.OpportunityFacts opportunity,
      CustomerInsightSnapshot.ContractPaymentFacts contractPayment,
      CustomerInsightSnapshot.TaskContactFacts taskContact) {
    return new CustomerInsightSnapshot(
        1001L,
        "北京测试科技有限公司",
        "软件",
        "正常",
        7L,
        9L,
        activity,
        opportunity,
        contractPayment,
        taskContact);
  }

  private CustomerInsightSnapshot.ActivityFacts activity(
      int count,
      int countLast30Days,
      LocalDateTime lastActivityTime,
      long daysSince,
      String title) {
    return new CustomerInsightSnapshot.ActivityFacts(
        count, countLast30Days, lastActivityTime, daysSince, title);
  }

  private CustomerInsightSnapshot.OpportunityFacts opportunity(
      int count, BigDecimal totalAmount, int maxStagnationDays, int rejectedCount) {
    return new CustomerInsightSnapshot.OpportunityFacts(
        count, totalAmount, maxStagnationDays, rejectedCount);
  }

  private CustomerInsightSnapshot.ContractPaymentFacts contractPayment(
      int contractCount,
      BigDecimal totalAmount,
      BigDecimal paid,
      BigDecimal pending,
      BigDecimal overdue,
      int unpaidCount) {
    return new CustomerInsightSnapshot.ContractPaymentFacts(
        contractCount, totalAmount, paid, pending, overdue, unpaidCount);
  }

  private CustomerInsightSnapshot.TaskContactFacts taskContact(
      int highPriorityOpen, int overdueHighPriority, int contactCount, int keyContactCount) {
    return new CustomerInsightSnapshot.TaskContactFacts(
        highPriorityOpen, overdueHighPriority, contactCount, keyContactCount);
  }
}
