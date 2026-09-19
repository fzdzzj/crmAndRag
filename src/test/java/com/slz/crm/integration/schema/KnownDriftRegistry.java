package com.slz.crm.integration.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已定夺 schema 漂移登记清单（drift-disposition 任务 1.1，显式登记制 + KNOWN/NEW 二分）。
 *
 * <p>登记 = 已通过豁免定夺的既有漂移（proposal.md 定夺表，2026-09-14 拍板"登记豁免，不动表结构"）。 审计比对 WARN/INFO 漂移时先查本清单：命中 →
 * <b>KNOWN</b>（已定夺豁免，仅计数）；未命中 → <b>NEW</b> （新出现，待定夺，打印明细行）——由此"已定夺的漂移"与"新出现的漂移"彻底分离，门禁信号不再被噪声淹没。
 *
 * <ul>
 *   <li>W1-W5：类型不亲和，语义兼容/隐式转换现状可用，豁免；
 *   <li>I1：{@code customer_company.dept_unique_key} 为生成列（V1 L112 {@code GENERATED ALWAYS AS …
 *       STORED}， 软删自动失效唯一键），实体不映射是正确设计，豁免；
 *   <li>I2：{@code platform_token_budget} 为 V5 治理域预留表（主代码零引用），删除属破坏性 DDL，豁免。
 * </ul>
 *
 * <p>匹配键 = 表.列；表级登记（{@link Entry#column()} 为 {@code null}，如 I2 有表无实体）仅按表匹配。 防呆：{@link
 * SchemaDriftComparator#unmatchedKnownDrifts} 校验每项登记必须仍产出对应漂移 finding——
 * 登记过期（登记项在实体与迁移链中已不存在）即报错，防止登寄存器写错/漂移已根修却不更新登记。
 */
public final class KnownDriftRegistry {

  /** 单条登记：表 + 列（表级 drift 为 {@code null}）+ 差异描述 + 豁免理由 + 定夺日期。 */
  public record Entry(
      String table, String column, String driftDescription, String reason, String decisionDate) {}

  /** 7 项已定夺豁免漂移（proposal.md 定夺表，拍板口径 2026-09-14）。 */
  public static final List<Entry> KNOWN_DRIFTS = buildKnownDrifts();

  private KnownDriftRegistry() {}

  /**
   * 判断某 WARN/INFO finding（{@code table.column}）是否命中豁免登记。
   *
   * <p>列级登记按 "表 + 列" 精确匹配；表级登记（{@code column == null}，如"有表无实体"）仅按表匹配， 遇同表任意表级 finding（含 {@code
   * null} 列）即命中。同表同列的类型变化会重新落 NEW，不会误豁免。
   *
   * @param table 表名
   * @param column 列名（表级 finding 为 {@code null}）
   * @return true 表示命中已定夺登记（计入 KNOWN）
   */
  public static boolean isKnownDrift(String table, String column) {
    for (Entry entry : KNOWN_DRIFTS) {
      if (!entry.table().equals(table)) {
        continue;
      }
      if (entry.column() == null) {
        return column == null;
      }
      if (entry.column().equals(column)) {
        return true;
      }
    }
    return false;
  }

  private static List<Entry> buildKnownDrifts() {
    List<Entry> list = new ArrayList<>();
    String date = "2026-09-14";
    // W1-W5：类型不亲和，语义兼容/隐式转换现状可用
    list.add(
        new Entry(
            "ai_chat_image",
            "key_entities",
            "类型不亲和 String↔json",
            "MyBatis 以 String 承载 JSON 文本，语义兼容；长度/JSON 校验由应用层负责",
            date));
    list.add(
        new Entry(
            "data_share",
            "resource_type",
            "类型不亲和 String↔tinyint",
            "活象字符串 vs 整数编码，mapper 隐式转换现状可用；改写 SQL 注意编码对应",
            date));
    list.add(
        new Entry(
            "tage_resource_binding",
            "resource_type",
            "类型不亲和 String↔tinyint",
            "同 data_share.resource_type 模式",
            date));
    list.add(
        new Entry(
            "sales_stage_approval",
            "current_stage",
            "类型不亲和 Integer↔varchar",
            "MyBatis 隐式转换现状可用；数值语义稳定",
            date));
    list.add(
        new Entry(
            "sales_stage_approval",
            "target_stage",
            "类型不亲和 Integer↔varchar",
            "同 sales_stage_approval.current_stage",
            date));
    // I1：生成列冗余（实体不映射是正确设计）
    list.add(
        new Entry(
            "customer_company",
            "dept_unique_key",
            "冗余列（生成列）",
            "V1 L112 GENERATED ALWAYS AS … STORED，软删自动失效唯一键；实体不映射是正确设计",
            date));
    // I2：治理域预留表（有表无实体）
    list.add(
        new Entry(
            "platform_token_budget", null, "有表无实体", "V5 治理域预留表，主代码零引用；待治理域启用，删除属破坏性 DDL", date));
    return Collections.unmodifiableList(list);
  }
}
