package com.slz.crm.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * schema 漂移比对器纯函数单测（audit-entity-table-drift 任务 2.3，≥4 条）。
 *
 * <p>夹具覆盖：零差异、实体表缺失（CRITICAL）、实体列缺失（CRITICAL）、类型不亲和（WARN）、 冗余列/有表无实体（INFO）+ flyway
 * 豁免、Boolean↔tinyint 亲和。
 */
class SchemaDriftComparatorTest {

  private static EntityTable entity(String table, Map<String, String> columnTypes) {
    return new EntityTable(table, Object.class, columnTypes);
  }

  private static Map<String, DbColumn> dbColumns(Object... colTypePairs) {
    Map<String, DbColumn> columns = new LinkedHashMap<>();
    for (int i = 0; i < colTypePairs.length; i += 2) {
      columns.put((String) colTypePairs[i], new DbColumn((String) colTypePairs[i + 1], true));
    }
    return columns;
  }

  private static List<DriftFinding> bySeverity(List<DriftFinding> findings, Severity severity) {
    return findings.stream().filter(f -> f.severity() == severity).toList();
  }

  @Test
  void zeroDiffProducesNoFindings() {
    Map<String, EntityTable> entitySide =
        Map.of(
            "customer_company",
                entity(
                    "customer_company", Map.of("id", "java.lang.Long", "name", "java.lang.String")),
            "sys_dept",
                entity("sys_dept", Map.of("id", "java.lang.Long", "leader_id", "java.lang.Long")));
    Map<String, Map<String, DbColumn>> dbSide =
        Map.of(
            "customer_company", dbColumns("id", "bigint", "name", "varchar"),
            "sys_dept", dbColumns("id", "bigint", "leader_id", "bigint"));

    List<DriftFinding> findings = SchemaDriftComparator.compare(entitySide, dbSide);

    assertTrue(findings.isEmpty(), "零差异夹具应产出空 finding，实际=" + findings);
  }

  @Test
  void missingEntityTableIsCritical() {
    Map<String, EntityTable> entitySide =
        Map.of(
            "approval_attachment", entity("approval_attachment", Map.of("id", "java.lang.Long")));
    Map<String, Map<String, DbColumn>> dbSide = Map.of();

    List<DriftFinding> findings = SchemaDriftComparator.compare(entitySide, dbSide);

    List<DriftFinding> critical = bySeverity(findings, Severity.CRITICAL);
    assertEquals(1, critical.size());
    assertEquals("approval_attachment", critical.get(0).table());
    assertEquals(Severity.CRITICAL, critical.get(0).severity());
  }

  @Test
  void missingEntityColumnIsCritical() {
    Map<String, EntityTable> entitySide =
        Map.of(
            "approval_attachment",
            entity(
                "approval_attachment",
                Map.of("id", "java.lang.Long", "uploader_id", "java.lang.Long")));
    Map<String, Map<String, DbColumn>> dbSide =
        Map.of("approval_attachment", dbColumns("id", "bigint"));

    List<DriftFinding> findings = SchemaDriftComparator.compare(entitySide, dbSide);

    List<DriftFinding> critical = bySeverity(findings, Severity.CRITICAL);
    assertEquals(1, critical.size());
    assertEquals("uploader_id", critical.get(0).column());
    assertTrue(critical.get(0).message().contains("V24"), "提示应关联 V24 同类漂移语义");
  }

  @Test
  void incompatibleTypeIsWarn() {
    Map<String, EntityTable> entitySide =
        Map.of("contract", entity("contract", Map.of("amount", "java.lang.Long")));
    Map<String, Map<String, DbColumn>> dbSide = Map.of("contract", dbColumns("amount", "varchar"));

    List<DriftFinding> findings = SchemaDriftComparator.compare(entitySide, dbSide);

    assertTrue(bySeverity(findings, Severity.CRITICAL).isEmpty(), "类型不亲和不是 CRITICAL");
    List<DriftFinding> warns = bySeverity(findings, Severity.WARN);
    assertEquals(1, warns.size());
    assertEquals("contract", warns.get(0).table());
    assertEquals("amount", warns.get(0).column());
  }

  @Test
  void redundantColumnAndOrphanTableAreInfoButFlywayExempt() {
    Map<String, EntityTable> entitySide =
        Map.of("contract", entity("contract", Map.of("id", "java.lang.Long")));
    Map<String, Map<String, DbColumn>> dbSide =
        Map.of(
            "contract",
            dbColumns("id", "bigint", "legacy_col", "varchar"),
            "orphan_table",
            dbColumns("id", "bigint"),
            SchemaDriftComparator.FLYWAY_HISTORY_TABLE,
            dbColumns("version", "varchar"));

    List<DriftFinding> findings = SchemaDriftComparator.compare(entitySide, dbSide);

    assertEquals(0, bySeverity(findings, Severity.CRITICAL).size());
    assertEquals(0, bySeverity(findings, Severity.WARN).size());
    List<DriftFinding> infos = bySeverity(findings, Severity.INFO);
    assertEquals(2, infos.size(), "contract.legacy_col（冗余列）+ orphan_table（有表无实体）；flyway 豁免");
    assertFalse(
        infos.stream().anyMatch(f -> SchemaDriftComparator.FLYWAY_HISTORY_TABLE.equals(f.table())),
        "flyway_schema_history 必须豁免");
  }

  @Test
  void booleanEntityAcceptsTinyintAndBit() {
    Map<String, EntityTable> entitySide =
        Map.of(
            "customer_company",
            entity(
                "customer_company",
                Map.of("is_deleted", "java.lang.Boolean", "flag_bit", "java.lang.Boolean")));
    Map<String, Map<String, DbColumn>> dbSide =
        Map.of("customer_company", dbColumns("is_deleted", "tinyint", "flag_bit", "bit"));

    List<DriftFinding> findings = SchemaDriftComparator.compare(entitySide, dbSide);

    assertTrue(
        bySeverity(findings, Severity.WARN).isEmpty(),
        "Boolean 实体与 tinyint/bit 亲和，不应 WARN，实际=" + findings);
  }

  @Test
  void enumEntityTreatsAsString() {
    Map<String, EntityTable> entitySide =
        Map.of(
            "knowledge_base",
            entity(
                "knowledge_base",
                Map.of("visibility", "com.slz.crm.knowledge.entity.KnowledgeBaseVisibility")));
    Map<String, Map<String, DbColumn>> dbSide =
        Map.of("knowledge_base", dbColumns("visibility", "varchar"));

    List<DriftFinding> findings = SchemaDriftComparator.compare(entitySide, dbSide);

    assertEquals(
        0,
        bySeverity(findings, Severity.WARN).size(),
        "@EnumValue String 枚举与 varchar 亲和，不应 WARN，实际=" + findings);
  }
}
