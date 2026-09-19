package com.slz.crm.integration.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * schema 漂移永久门禁 IT（audit-entity-table-drift 任务 3.1）。
 *
 * <p><b>价值</b>：单测全跑 H2 auto-table（按实体建表），列漂移在单测层结构性不可见；唯一暴露面是真库 （Testcontainers MySQL + Flyway
 * 全链）。本 IT 对 53 个实体 ↔ 迁移链真库做单向比对： <b>CRITICAL（实体表/列在真库缺失）非空即 fail</b>——今后"改实体忘配套迁移"在 CI 直接红，而不是等生产
 * 90004。
 *
 * <p><b>Docker 门禁</b>：无 Docker {@code assumeTrue} 优雅跳过（本地开发机）；CI（ubuntu-latest 自带 Docker）真跑。与
 * {@link com.slz.crm.integration.FlywayMigrationIT} 共用 mysql:8.0.36 镜像口径。
 *
 * <p>INFO/WARN 只报告不失败；CRITICAL 修复受限至 additive 迁移，见提案授权边界。
 */
class SchemaDriftAuditIT {

  /** 本容器库名（与 FlywayMigrationIT 一致，分开起以求自包含）。 */
  private static final String DB_NAME = "crm_schema_audit_it";

  private static MySQLContainer<?> mysql;

  @BeforeAll
  static void startContainer() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过 schema 漂移审计 IT（在 CI 环境执行）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName(DB_NAME)
            .withUsername("crm")
            .withPassword("crm_it_pwd");
    mysql.start();

    // 与 production 一致：全链 Flyway 迁移（V1..V24），审计迁移链定义的 schema，而非 auto-table 猜测
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .validateOnMigrate(true)
        .load()
        .migrate();
  }

  @AfterAll
  static void stopContainer() {
    if (mysql != null) {
      mysql.stop();
    }
  }

  @Test
  void noCriticalSchemaDriftOnRealMySql() throws Exception {
    // 实体侧：ClassPath 扫描 + MP 同一套映射
    List<EntityTable> entities = SchemaDriftAuditor.scanAndExtract();

    // 库侧：information_schema.columns
    Map<String, Map<String, DbColumn>> dbSide = introspectDatabase();

    // 比对（纯函数）
    List<DriftFinding> findings = SchemaDriftComparator.compare(toMap(entities), dbSide);

    List<DriftFinding> critical =
        findings.stream().filter(f -> f.severity() == Severity.CRITICAL).toList();

    // KNOWN/NEW 二分（drift-disposition 任务 1.3）：WARN/INFO 命中豁免登记（KnownDriftRegistry）为 KNOWN，
    // 未命中为 NEW（新出现待定夺）。门禁语义不变：CRITICAL 非空 fail；NEW 不失败但显式打印提醒定夺。
    SchemaDriftComparator.DriftDispositionReport disposition =
        SchemaDriftComparator.classify(findings);

    // 全量 findings 打印到 stdout，无论成败都可见——落盘 docs/schema-drift-audit.md 以本输出为准
    StringBuilder report = new StringBuilder();
    report
        .append("\n===== schema 漂移审计：实体数=")
        .append(entities.size())
        .append("，库表数=")
        .append(dbSide.size())
        .append("，总 finding=")
        .append(findings.size())
        .append(" =====\n");
    report.append("-- CRITICAL (").append(critical.size()).append(") --\n");
    findings.stream()
        .filter(f -> f.severity() == Severity.CRITICAL)
        .forEach(f -> report.append(format(f)).append('\n'));
    report
        .append("-- KNOWN (")
        .append(disposition.knownCount())
        .append(") 已定夺豁免（见 KnownDriftRegistry），仅计数 --\n");
    disposition.known().forEach(f -> report.append(format(f)).append('\n'));
    report.append("-- NEW (").append(disposition.newCount()).append(") 新出现待定夺（需登记豁免或处置）--\n");
    disposition.newFindings().forEach(f -> report.append(format(f)).append('\n'));
    report.append("-- WARN --\n");
    findings.stream()
        .filter(f -> f.severity() == Severity.WARN)
        .forEach(f -> report.append(format(f)).append('\n'));
    report.append("-- INFO --\n");
    findings.stream()
        .filter(f -> f.severity() == Severity.INFO)
        .forEach(f -> report.append(format(f)).append('\n'));
    System.out.println(report);

    if (disposition.newCount() > 0) {
      System.out.println(
          "[提醒] 发现 "
              + disposition.newCount()
              + " 处新出现的 schema 漂移（NEW），未在 KnownDriftRegistry 登记——请按 drift-disposition "
              + "契约定夺：登记豁免或处置，勿任其累积。");
    }

    assertTrue(
        critical.isEmpty(),
        "CRITICAL schema 漂移必须清零（实体表/列在真库缺失 = 运行期必炸类），共 "
            + critical.size()
            + " 处：\n"
            + critical.stream().map(this::format).reduce("", (a, b) -> a + "  - " + b + "\n"));
  }

  private String format(DriftFinding finding) {
    return String.format(
        "[%s] %s.%s：%s",
        finding.severity(),
        finding.table(),
        finding.column() == null ? "*" : finding.column(),
        finding.message());
  }

  private Map<String, EntityTable> toMap(List<EntityTable> entities) {
    Map<String, EntityTable> map = new LinkedHashMap<>();
    for (EntityTable table : entities) {
      map.put(table.tableName(), table);
    }
    return map;
  }

  /** 从 information_schema.columns 读取迁移链真库列元数据（按 table 过滤，排除其他库表）。 */
  private Map<String, Map<String, DbColumn>> introspectDatabase() throws Exception {
    Map<String, Map<String, DbColumn>> result = new LinkedHashMap<>();
    String sql =
        "SELECT table_name, column_name, data_type, is_nullable "
            + "FROM information_schema.columns WHERE table_schema = '"
            + DB_NAME
            + "' order by table_name, column_name";
    try (Connection connection =
            DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        String table = rs.getString("table_name");
        String column = rs.getString("column_name");
        String dataType = rs.getString("data_type");
        boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
        result
            .computeIfAbsent(table, k -> new LinkedHashMap<>())
            .put(column, new DbColumn(dataType, nullable));
      }
    }
    return result;
  }
}
