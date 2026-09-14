package com.slz.crm.integration.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已定夺漂移登记（KNOWN/NEW 二分）单测（drift-disposition 任务 1.4，3 条）。
 *
 * <p>三条覆盖：① 7 项登记漂移全部落 KNOWN 不落 NEW；② 未登记（新出现）漂移落 NEW；
 * ③ 防呆——登记项在实体与迁移链中已不存在（漂移被根修）时 {@link SchemaDriftComparator#unmatchedKnownDrifts}
 * 应报该登记过期。</p>
 */
class KnownDriftRegistryTest {

    /** 构造当前 7 项真实漂移 finding（表.列与 KnownDriftRegistry 登记一致）。 */
    private static List<DriftFinding> allSevenFindings() {
        return List.of(
                DriftFinding.warn("ai_chat_image", "key_entities", "类型不亲和：实体 java.lang.String ↔ 库 json"),
                DriftFinding.warn("data_share", "resource_type", "类型不亲和：实体 java.lang.String ↔ 库 tinyint"),
                DriftFinding.warn("tage_resource_binding", "resource_type", "类型不亲和：实体 java.lang.String ↔ 库 tinyint"),
                DriftFinding.warn("sales_stage_approval", "current_stage", "类型不亲和：实体 java.lang.Integer ↔ 库 varchar"),
                DriftFinding.warn("sales_stage_approval", "target_stage", "类型不亲和：实体 java.lang.Integer ↔ 库 varchar"),
                DriftFinding.info("customer_company", "dept_unique_key", "冗余列——库侧存在但实体未映射（生成列）"),
                DriftFinding.info("platform_token_budget", null, "有表无实体——库侧存在但无 @TableName 实体映射"));
    }

    @Test
    void allSevenRegisteredDriftsClassifyKnown() {
        SchemaDriftComparator.DriftDispositionReport report =
                SchemaDriftComparator.classify(allSevenFindings());

        assertEquals(7, report.knownCount(), "7 项已定夺漂移必须全部落 KNOWN");
        assertEquals(0, report.newCount(), "当前不应有新漂移落 NEW");
        assertTrue(SchemaDriftComparator.unmatchedKnownDrifts(allSevenFindings()).isEmpty(),
                "全量登记漂移 present 时防呆应全部命中，不报过期");
    }

    @Test
    void unregisteredDriftClassifiesNew() {
        List<DriftFinding> mixed = List.of(
                allSevenFindings().get(0), // W1 命中登记 → KNOWN
                DriftFinding.warn("brand_new_table", "brand_new_col", "类型不亲和：实体 Long ↔ 库 varchar"),
                DriftFinding.info("orphan_new_table", null, "有表无实体——未登记"));

        SchemaDriftComparator.DriftDispositionReport report = SchemaDriftComparator.classify(mixed);

        assertEquals(1, report.knownCount(), "命中登记的 W1 计入 KNOWN");
        assertEquals(2, report.newCount(), "两个未登记漂移（列级 + 表级）计入 NEW");
    }

    @Test
    void staleOrWrongRegistrationFailsValidation() {
        // 模拟 W1 已被根修：ai_chat_image.key_entities 不再产出 WARN finding → 该登记应被判"登记过期"
        List<DriftFinding> afterFix = allSevenFindings().stream()
                .filter(f -> !(f.table().equals("ai_chat_image") && "key_entities".equals(f.column())))
                .toList();

        List<KnownDriftRegistry.Entry> unmatched = SchemaDriftComparator.unmatchedKnownDrifts(afterFix);

        assertEquals(1, unmatched.size(), "仅 W1 登记过期：其余 6 项仍命中真实漂移");
        assertEquals("ai_chat_image", unmatched.get(0).table());
        assertEquals("key_entities", unmatched.get(0).column());
        assertTrue(unmatched.get(0).driftDescription().contains("json"),
                "过期登记应携带原始差异描述，" + unmatched.get(0));
    }
}