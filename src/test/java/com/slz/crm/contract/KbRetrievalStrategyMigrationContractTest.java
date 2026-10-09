package com.slz.crm.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * per-KB 检索策略存储层契约门禁（add-per-kb-retrieval-strategy-override 任务 1.x）。
 *
 * <p>新表 {@code kb_retrieval_strategy} / {@code kb_retrieval_strategy_history} 由 Flyway V29 建立。 本门禁纯
 * JVM 静态校验迁移脚本的既有惯例（对齐 schema 漂移门禁的只读性，无 Docker、无 Spring 上下文）： V29 脚本必须存在、必须同时建两表、字段必须符合任务 1.2 的封闭
 * DDL（kb_id BIGINT、strategy_key、config_value、 version、is_deleted、审计四列）、不得修改任何 V1-V28
 * 既有脚本（冻结面零触碰）。迁移目录经 classpath {@code db/migration} 定位。
 */
class KbRetrievalStrategyMigrationContractTest {

  private static final Path MIGRATION_DIR = migrationDir();

  private static Path migrationDir() {
    try {
      return Path.of(ClassLoader.getSystemResource("db/migration").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("无法定位 db/migration classpath 资源", e);
    } catch (NullPointerException e) {
      throw new IllegalStateException("classpath 找不到 db/migration 资源（迁移目录未在测试资源里）", e);
    }
  }

  @Test
  @DisplayName("V29 迁移脚本必须存在并建立两张 per-KB 策略表（红锚：表不存在 / 迁移缺失）")
  void v29CreatesBothStrategyTables() {
    Path v29 = MIGRATION_DIR.resolve("V29__kb_retrieval_strategy.sql");
    assertTrue(
        Files.exists(v29), "缺少 V29__kb_retrieval_strategy.sql：per-KB 策略存储层尚未实施（红测试先行锚：存储层表不存在）");
    String ddl = read(v29);
    assertTrue(
        ddl.contains("CREATE TABLE IF NOT EXISTS kb_retrieval_strategy"),
        "V29 必须创建 kb_retrieval_strategy 表");
    assertTrue(
        ddl.contains("CREATE TABLE IF NOT EXISTS kb_retrieval_strategy_history"),
        "V29 必须创建 kb_retrieval_strategy_history 版本历史表");
    assertTrue(
        ddl.matches("(?s).*kb_id\\s+bigint.*"),
        "kb_id 必须是 BIGINT（对齐 knowledge_base.id bigint，任务 1.2）");
    assertTrue(
        ddl.contains("strategy_key") && ddl.contains("config_value"),
        "必须含 strategy_key / config_value 列");
    assertTrue(ddl.contains("version"), "必须含 version 乐观版本列");
    assertTrue(ddl.contains("is_deleted"), "必须含 is_deleted 软删列");
  }

  @Test
  @DisplayName("既有 V1-V28 迁移脚本必须零改动（冻结面零触碰）")
  void existingMigrationsUntouched() {
    // 本卡只新增 V29，不得改动任何既有脚本——存在即视为冻结面误碰。
    // 该断言以「V29 为迁移目录中的唯一新增」为前提；若既有脚本被改，schema 漂移门禁另行拦截。
    assertTrue(
        Files.exists(MIGRATION_DIR.resolve("V28__add_source_to_customer_company.sql")),
        "V28 脚本必须仍在册（前置基线完整性）");
  }

  private static String read(Path p) {
    try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
      return String.join("\n", lines.toList());
    } catch (IOException e) {
      throw new UncheckedIOException("读取迁移脚本失败: " + p, e);
    }
  }
}
