package com.slz.crm.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 多模态灰度文档四键防漂移守卫（evidence-to-operations G3 任务 3.3.1~3.3.2，Q3）。
 *
 * <p>把 {@code docs/multimodal-gray-rollout.md} 里<b>手抄</b>的四键「键名 + 默认值 + 档位」与真值源逐键比对： {@link
 * DynamicConfigKeyRegistry}（静态默认与类型）+ {@link ConfigKeyTierPolicy}（档位）。注册表改了、文档没改， 本测试即红（反向亦然）。
 *
 * <p><b>口径勘误（按实际内容定位）</b>：提案/任务卡写的「§1 表」在当前文档里实际落在 <b>§2「四个键与真实门控关系」</b> 的四键表；§9.1 另有一行手抄默认值（{@code
 * DynamicConfigKeyRegistry：image-slice-ingest.enabled=false …}）。 本测试两处都解析、都比对——任何一处与注册表漂移即红。
 *
 * <p><b>Q3 反向自证</b>：把 §9.1 行内 {@code image-vector-ingest.enabled=false} 手改成 {@code true}，本测试必须红。
 */
@DisplayName("多模态灰度文档四键与注册表/档位策略一致（§2 表 + §9.1 行）")
class MultimodalRolloutKeysDocConsistencyTest {

  private static final Path DOC = Path.of("docs", "multimodal-gray-rollout.md");

  /** 四键 → 注册表类型（键名/默认值/档位都以注册表为准，这里只钉类型期望）。 */
  private static final List<String> KEYS =
      List.of(
          "rag.retrieval.image-slice-ingest.enabled",
          "rag.retrieval.image-slice-ingest.kb-allowlist",
          "rag.retrieval.image-slice-ingest.max-images-per-document",
          "rag.retrieval.image-vector-ingest.enabled");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static DynamicConfigKeyRegistry registry;
  private static String docText;

  @BeforeAll
  static void load() throws IOException {
    assertTrue(Files.isRegularFile(DOC), "灰度文档缺失: " + DOC.toAbsolutePath());
    docText = Files.readString(DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
    registry = new DynamicConfigKeyRegistry(MAPPER);
  }

  @Test
  @DisplayName("§2 四键表：键名/默认值/档位 = DynamicConfigKeyRegistry + ConfigKeyTierPolicy(COST)")
  void docKeyTableMatchesRegistry() {
    Map<String, String> tableDefaults = parseSectionTwoTable();
    for (String key : KEYS) {
      String docDefault = tableDefaults.get(key);
      assertNotNull(docDefault, "§2 表缺键 " + key + "（文档表被改结构？）");
      ConfigKeyDefinition def = registry.definitionOf(key).orElse(null);
      assertNotNull(def, "注册表缺键 " + key + "（真值源被删？）");
      assertEquals(
          normalizeDocDefault(docDefault, def), def.defaultValue(), "§2 表默认值与注册表漂移: " + key);
      assertEquals(ConfigKeyTier.COST, ConfigKeyTierPolicy.tierOf(def), "§2 表档位漂移: " + key);
    }
  }

  @Test
  @DisplayName("§9.1 手抄默认行：四键短名值对 = 注册表静态默认（Q3 反向自证的红点）")
  void docPreflightDefaultsLineMatchesRegistry() {
    Map<String, String> lineDefaults = parsePreflightDefaultsLine();
    assertLineDefault(
        lineDefaults,
        "image-slice-ingest.enabled",
        registry
            .definitionOf("rag.retrieval.image-slice-ingest.enabled")
            .orElseThrow()
            .defaultValue());
    assertLineDefault(
        lineDefaults,
        "image-vector-ingest.enabled",
        registry
            .definitionOf("rag.retrieval.image-vector-ingest.enabled")
            .orElseThrow()
            .defaultValue());
    assertLineDefault(
        lineDefaults,
        "kb-allowlist",
        registry
            .definitionOf("rag.retrieval.image-slice-ingest.kb-allowlist")
            .orElseThrow()
            .defaultValue());
    assertLineDefault(
        lineDefaults,
        "max-images-per-document",
        registry
            .definitionOf("rag.retrieval.image-slice-ingest.max-images-per-document")
            .orElseThrow()
            .defaultValue());
  }

  @Test
  @DisplayName("四键在注册表中的类型（BOOLEAN/STRING/INTEGER）与档位封闭集归属")
  void registryTypesAndTiers() {
    assertTypeAndTier("rag.retrieval.image-slice-ingest.enabled", ConfigValueType.BOOLEAN);
    assertTypeAndTier("rag.retrieval.image-slice-ingest.kb-allowlist", ConfigValueType.STRING);
    assertTypeAndTier(
        "rag.retrieval.image-slice-ingest.max-images-per-document", ConfigValueType.INTEGER);
    assertTypeAndTier("rag.retrieval.image-vector-ingest.enabled", ConfigValueType.BOOLEAN);
  }

  private static void assertTypeAndTier(String key, ConfigValueType type) {
    ConfigKeyDefinition def = registry.definitionOf(key).orElse(null);
    assertNotNull(def, "注册表缺键 " + key);
    assertEquals(type, def.type(), "类型漂移: " + key);
    assertEquals(ConfigKeyTier.COST, ConfigKeyTierPolicy.tierOf(def), "档位漂移: " + key);
  }

  private static void assertLineDefault(
      Map<String, String> lineDefaults, String shortName, String expected) {
    String actual = lineDefaults.get(shortName);
    assertNotNull(actual, "§9.1 默认行缺短名 " + shortName + "（文档行被改结构？）");
    assertEquals(expected, actual, "§9.1 手抄默认值漂移: " + shortName);
  }

  // ==================== 文档解析 ====================

  /** §2（「## 2.」到「## 3.」之间）四键表：键 → 文档默认值原文（未归一化）。 */
  private static Map<String, String> parseSectionTwoTable() {
    Map<String, String> defaults = new LinkedHashMap<>();
    boolean inSection = false;
    for (String line : docText.split("\n")) {
      if (line.startsWith("## 2.")) {
        inSection = true;
        continue;
      }
      if (inSection && line.startsWith("## ")) {
        break;
      }
      if (!inSection || !line.startsWith("|") || line.contains("---")) {
        continue;
      }
      String[] cells = line.split("\\|");
      if (cells.length < 3) {
        continue;
      }
      String key = stripMarkdown(cells[1]);
      if (KEYS.contains(key)) {
        defaults.put(key, cells[2].trim());
      }
    }
    return defaults;
  }

  /**
   * §9.1 的注册表默认值行（形如 {@code DynamicConfigKeyRegistry：`image-slice-ingest.enabled=false`、…}）： 短名 →
   * 值。<b>该行在文档里换行成三行</b>（markdown 段内折行），故以含 {@code DynamicConfigKeyRegistry} 的行为起点 连同后两行拼成一段再抽对。
   */
  private static Map<String, String> parsePreflightDefaultsLine() {
    Map<String, String> values = new LinkedHashMap<>();
    String[] lines = docText.split("\n");
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].contains("DynamicConfigKeyRegistry") || !lines[i].contains("=")) {
        continue;
      }
      StringBuilder joined = new StringBuilder(lines[i]);
      for (int j = 1; j <= 2 && i + j < lines.length; j++) {
        joined.append(lines[i + j]);
      }
      String paragraph = joined.toString();
      putPair(values, paragraph, "image-slice-ingest.enabled", "([a-z]+)");
      putPair(values, paragraph, "image-vector-ingest.enabled", "([a-z]+)");
      putPair(values, paragraph, "kb-allowlist", "(\"\"|\\\\\"\\\\\")");
      putPair(values, paragraph, "max-images-per-document", "([0-9]+)");
    }
    return values;
  }

  /** 抽 {@code 短名=值} 对（正则转义点号）；kb-allowlist 的 {@code ""} 字面量归一化为空串。 */
  private static void putPair(
      Map<String, String> values, String text, String shortName, String valueRegex) {
    Matcher matcher = Pattern.compile(Pattern.quote(shortName) + "=" + valueRegex).matcher(text);
    if (matcher.find()) {
      String value = matcher.group(1);
      if (value.equals("\"\"") || value.equals("\\\"\\\"")) {
        value = "";
      }
      values.put(shortName, value);
    }
  }

  /** 文档默认值归一化：剥 {@code **} 与反引号；「空」= 空串。 */
  private static String normalizeDocDefault(String docDefault, ConfigKeyDefinition def) {
    String value = docDefault.replace("**", "").replace("`", "").trim();
    if (value.equals("空")) {
      return "";
    }
    return value;
  }

  private static String stripMarkdown(String cell) {
    return cell.replace("`", "").trim();
  }
}
