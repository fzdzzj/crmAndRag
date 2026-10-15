package com.slz.crm.server.ai.insight;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 客户健康确定性规则评分引擎（add-ai-customer-360-insight 任务 1.2）。
 *
 * <p>量化口径（与提案 §2 逐条对齐）：四维度加权总分 0~100 —— 跟进活跃度 30%（间隔 ≤7 天满分，&gt;15 天扣分，&gt;30 天扣重分）；商机健康度
 * 25%（阶段停滞过久或频繁驳回扣分）；合同回款履约 25%（逾期未回款或款项未结清扣分）；任务与联系人 20%（无关键联系人或高优任务超期扣分）。等级映射
 * EXCELLENT(85-100)/HEALTHY(70-84)/WARNING(50-69)/CRITICAL(&lt;50)。
 *
 * <p>规则风险预警为确定性文本，供 LLM 缺失/异常时安全回退（红线：绝不抛 500）。
 */
@Component
public class CustomerInsightRuleEngine {

  /** 跟进活跃度维度权重（30%）。 */
  public static final int ACTIVE_WEIGHT = 30;

  /** 商机健康度维度权重（25%）。 */
  public static final int OPPORTUNITY_WEIGHT = 25;

  /** 合同回款履约维度权重（25%）。 */
  public static final int CONTRACT_WEIGHT = 25;

  /** 任务与联系人维度权重（20%）。 */
  public static final int TASK_WEIGHT = 20;

  /** 维度名称：跟进活跃度。 */
  public static final String DIMENSION_ACTIVE = "跟进活跃度";

  /** 维度名称：商机健康度。 */
  public static final String DIMENSION_OPPORTUNITY = "商机健康度";

  /** 维度名称：合同回款履约。 */
  public static final String DIMENSION_CONTRACT = "合同回款履约";

  /** 维度名称：任务与联系人。 */
  public static final String DIMENSION_TASK = "任务与联系人";

  /**
   * 对多维快照做确定性评分与规则风险提取。
   *
   * @param snapshot 客户多维快照（不允许为 null）
   * @return 健康分、等级、维度明细与规则风险预警清单
   */
  public RuleResult evaluate(CustomerInsightSnapshot snapshot) {
    int activeScore = activeScore(snapshot);
    int opportunityScore = opportunityScore(snapshot);
    int contractScore = contractScore(snapshot);
    int taskScore = taskScore(snapshot);
    int healthScore =
        Math.round(
            (activeScore * ACTIVE_WEIGHT
                    + opportunityScore * OPPORTUNITY_WEIGHT
                    + contractScore * CONTRACT_WEIGHT
                    + taskScore * TASK_WEIGHT)
                / 100f);

    List<CustomerInsightDimensionScore> dimensions =
        List.of(
            new CustomerInsightDimensionScore(DIMENSION_ACTIVE, activeScore, ACTIVE_WEIGHT),
            new CustomerInsightDimensionScore(
                DIMENSION_OPPORTUNITY, opportunityScore, OPPORTUNITY_WEIGHT),
            new CustomerInsightDimensionScore(DIMENSION_CONTRACT, contractScore, CONTRACT_WEIGHT),
            new CustomerInsightDimensionScore(DIMENSION_TASK, taskScore, TASK_WEIGHT));
    return new RuleResult(
        healthScore,
        CustomerInsightHealthLevel.of(healthScore),
        dimensions,
        buildWarnings(snapshot));
  }

  /** 跟进活跃度：无记录重扣；间隔 ≤7 天满分，>15 天扣分，>30 天扣重分。 */
  private int activeScore(CustomerInsightSnapshot snapshot) {
    int score;
    if (!snapshot.activity().hasActivity()) {
      score = 40;
    } else {
      long days = snapshot.activity().daysSinceLastActivity();
      if (days <= 7) {
        score = 100;
      } else if (days <= 15) {
        score = 80;
      } else if (days <= 30) {
        score = 55;
      } else {
        score = 30;
      }
    }
    return score;
  }

  /** 商机健康度：无商机取中性 80；阶段停滞与审批驳回逐级扣分。 */
  private int opportunityScore(CustomerInsightSnapshot snapshot) {
    int score;
    if (snapshot.opportunity().count() == 0) {
      score = 80;
    } else {
      score = 100;
      int stagnation = snapshot.opportunity().maxStageStagnationDays();
      if (stagnation > 30) {
        score -= 40;
      } else if (stagnation > 15) {
        score -= 25;
      } else if (stagnation > 7) {
        score -= 15;
      }
      int rejected = snapshot.opportunity().approvalRejectedCount();
      if (rejected >= 3) {
        score -= 30;
      } else if (rejected == 2) {
        score -= 20;
      } else if (rejected == 1) {
        score -= 10;
      }
      score = clamp(score);
    }
    return score;
  }

  /** 合同回款履约：无合同取中性 80；待确认、逾期未回款、未结清逐级扣分。 */
  private int contractScore(CustomerInsightSnapshot snapshot) {
    int score;
    if (snapshot.contractPayment().contractCount() == 0) {
      score = 80;
    } else {
      score = 100;
      if (isPositive(snapshot.contractPayment().pendingPaymentAmount())) {
        score -= 20;
      }
      if (isPositive(snapshot.contractPayment().overdueUnpaidAmount())) {
        score -= 30;
      }
      if (snapshot.contractPayment().unpaidContractCount() > 0) {
        score -= 10;
      }
      score = clamp(score);
    }
    return score;
  }

  /** 任务与联系人：无联系人重扣、无关键决策人扣分、高优任务超期逐级扣分。 */
  private int taskScore(CustomerInsightSnapshot snapshot) {
    int score = 100;
    if (snapshot.taskContact().contactCount() == 0) {
      score -= 40;
    } else if (snapshot.taskContact().keyContactCount() == 0) {
      score -= 25;
    }
    if (snapshot.taskContact().overdueHighPriorityTaskCount() > 0) {
      score -= Math.min(snapshot.taskContact().overdueHighPriorityTaskCount() * 15, 40);
    }
    return clamp(score);
  }

  private List<String> buildWarnings(CustomerInsightSnapshot snapshot) {
    List<String> warnings = new ArrayList<>();
    addActivityWarnings(warnings, snapshot);
    addOpportunityWarnings(warnings, snapshot);
    addContractWarnings(warnings, snapshot);
    addTaskContactWarnings(warnings, snapshot);
    return warnings;
  }

  private void addActivityWarnings(List<String> warnings, CustomerInsightSnapshot snapshot) {
    if (!snapshot.activity().hasActivity()) {
      warnings.add("该客户暂无跟进活动记录，客户关系处于空白期");
      return;
    }
    long days = snapshot.activity().daysSinceLastActivity();
    if (days > 30) {
      warnings.add("已超过30天未跟进该客户，流失风险高");
    } else if (days > 15) {
      warnings.add("已超过15天未跟进该客户，活跃度明显下降");
    } else if (days > 7) {
      warnings.add("已超过7天未跟进该客户，建议尽快安排跟进");
    }
  }

  private void addOpportunityWarnings(List<String> warnings, CustomerInsightSnapshot snapshot) {
    if (snapshot.opportunity().count() == 0) {
      return;
    }
    int stagnation = snapshot.opportunity().maxStageStagnationDays();
    if (stagnation > 30) {
      warnings.add("存在商机阶段停滞超过30天，推进严重受阻");
    } else if (stagnation > 15) {
      warnings.add("存在商机阶段停滞超过15天，需推动阶段流转");
    }
    if (snapshot.opportunity().approvalRejectedCount() > 0) {
      warnings.add(
          String.format("商机阶段审批被驳回/退回修改 %d 次", snapshot.opportunity().approvalRejectedCount()));
    }
  }

  private void addContractWarnings(List<String> warnings, CustomerInsightSnapshot snapshot) {
    if (snapshot.contractPayment().contractCount() == 0) {
      return;
    }
    if (isPositive(snapshot.contractPayment().overdueUnpaidAmount())) {
      warnings.add("存在逾期未回款款项，资金回笼风险");
    }
    if (isPositive(snapshot.contractPayment().pendingPaymentAmount())) {
      warnings.add("存在待确认回款款项");
    }
    if (snapshot.contractPayment().unpaidContractCount() > 0) {
      warnings.add("存在款项未结清的合同");
    }
  }

  private void addTaskContactWarnings(List<String> warnings, CustomerInsightSnapshot snapshot) {
    if (snapshot.taskContact().overdueHighPriorityTaskCount() > 0) {
      warnings.add("存在高优跟进任务超期未完成，客户跟进可能断档");
    }
    if (snapshot.taskContact().contactCount() == 0) {
      warnings.add("客户无联系人覆盖，关系网络薄弱");
    } else if (snapshot.taskContact().keyContactCount() == 0) {
      warnings.add("客户缺少关键决策人联系人");
    }
  }

  private boolean isPositive(java.math.BigDecimal value) {
    return value != null && value.signum() > 0;
  }

  private int clamp(int score) {
    return Math.max(0, Math.min(100, score));
  }

  /** 规则引擎确定性输出：健康分、等级、维度明细与规则风险预警。 */
  public record RuleResult(
      int healthScore,
      CustomerInsightHealthLevel healthLevel,
      List<CustomerInsightDimensionScore> dimensionScores,
      List<String> ruleWarnings) {}
}
