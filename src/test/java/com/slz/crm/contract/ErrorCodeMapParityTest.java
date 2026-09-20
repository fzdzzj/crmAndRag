package com.slz.crm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.platform.contract.PlatformErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 前后端错误码契约对齐门禁（CI 阶段 1 常驻，纯 JVM 无外部依赖）。
 *
 * <p>真相源是后端 {@link ErrorCode}（业务域）与 {@link PlatformErrorCode}（平台域）两个枚举； 前端映射层 {@code
 * frontend/src/constants/error-code-map.ts} 是它们的手工镜像。任何一侧改动（新增/删除/改名/改文案）若没有同步另一侧， 本测试即失败。
 *
 * <p>前端侧还有一层编译期保护：映射表声明为 {@code Record<ApiErrorCodeValue, ErrorCodeMeta>}， 漏码/多码在 {@code pnpm
 * type-check:check} 就报错（TS2741 / TS2353）。本测试补上编译期看不到的那一半—— <b>与 Java 枚举</b>的码值、名称、文案、i18n 键逐项一致。
 */
class ErrorCodeMapParityTest {

  private static final Path TS_MAP = ContractFiles.find("frontend/src/constants/error-code-map.ts");

  private static final Pattern CODE_BY_NAME =
      Pattern.compile("^\\s{2}([A-Z][A-Z0-9_]*)\\s*:\\s*(\\d{5})\\s*,\\s*$", Pattern.MULTILINE);

  private static final Pattern META_ENTRY =
      Pattern.compile(
          "name\\s*:\\s*'([A-Z][A-Z0-9_]*)'"
              + "\\s*,\\s*code\\s*:\\s*(\\d{5})"
              + "\\s*,\\s*message\\s*:\\s*'([^']*)'"
              + "(?:\\s*,\\s*messageKey\\s*:\\s*'([^']*)')?");

  /** 后端两个枚举的全集：名 → 码值/文案/i18n 键。 */
  private static Map<String, ExpectedEntry> backendEntries() {
    Map<String, ExpectedEntry> entries = new LinkedHashMap<>();
    for (ErrorCode code : ErrorCode.values()) {
      ExpectedEntry previous =
          entries.put(
              code.name(),
              new ExpectedEntry(code.getCode(), code.getMessage(), code.getMessageKey()));
      assertNullDuplicate(previous, code.name());
    }
    for (PlatformErrorCode code : PlatformErrorCode.values()) {
      ExpectedEntry previous =
          entries.put(code.name(), new ExpectedEntry(code.getCode(), code.getMessage(), null));
      assertNullDuplicate(previous, code.name());
    }
    return entries;
  }

  private static void assertNullDuplicate(ExpectedEntry previous, String name) {
    assertTrue(previous == null, "后端两个枚举撞名: " + name);
  }

  /** 解析前端映射层的 {@code ApiErrorCode} 常量表（名 → 码值）。 */
  private static Map<String, Integer> frontendCodesByName() {
    String section = section("export const ApiErrorCode = {", "} as const;");
    Map<String, Integer> entries = new LinkedHashMap<>();
    Matcher matcher = CODE_BY_NAME.matcher(section);
    while (matcher.find()) {
      entries.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
    }
    return entries;
  }

  /** 解析前端映射层的 {@code errorCodeMap}（名 → 码值/文案/i18n 键）。 */
  private static Map<String, ExpectedEntry> frontendMetaByName() {
    String section = section("export const errorCodeMap", "\n};");
    Map<String, ExpectedEntry> entries = new LinkedHashMap<>();
    Matcher matcher = META_ENTRY.matcher(section);
    while (matcher.find()) {
      String name = matcher.group(1);
      ExpectedEntry entry =
          new ExpectedEntry(Integer.parseInt(matcher.group(2)), matcher.group(3), matcher.group(4));
      ExpectedEntry previous = entries.put(name, entry);
      assertTrue(previous == null, "前端映射层同一错误名出现两次: " + name);
    }
    return entries;
  }

  private static String section(String beginMarker, String endMarker) {
    String content;
    try {
      content = Files.readString(TS_MAP, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读取前端错误码映射层失败: " + TS_MAP.toAbsolutePath(), e);
    }
    int begin = content.indexOf(beginMarker);
    assertTrue(begin >= 0, "前端映射层缺少段首标记: " + beginMarker);
    int end = content.indexOf(endMarker, begin);
    assertTrue(end > begin, "前端映射层缺少段尾标记: " + endMarker);
    return content.substring(begin, end);
  }

  @Test
  void frontendCodeTableMatchesBackendEnums() {
    Map<String, ExpectedEntry> backend = backendEntries();
    Map<String, Integer> frontend = frontendCodesByName();

    assertEquals(
        new TreeSet<>(backend.keySet()),
        new TreeSet<>(frontend.keySet()),
        "前端 ApiErrorCode 与后端枚举的名单不一致（新增/删除/改名都要两侧同步）");
    for (Map.Entry<String, Integer> entry : frontend.entrySet()) {
      ExpectedEntry expected = backend.get(entry.getKey());
      assertNotNull(expected, "前端多出后端不存在的错误名: " + entry.getKey());
      assertEquals(expected.code(), entry.getValue(), "码值漂移: " + entry.getKey());
    }
  }

  @Test
  void frontendMetaMatchesBackendMessagesAndI18nKeys() {
    Map<String, ExpectedEntry> backend = backendEntries();
    Map<String, ExpectedEntry> frontend = frontendMetaByName();

    assertEquals(
        new TreeSet<>(backend.keySet()),
        new TreeSet<>(frontend.keySet()),
        "前端 errorCodeMap 的覆盖面与后端枚举不一致");
    for (Map.Entry<String, ExpectedEntry> entry : frontend.entrySet()) {
      String name = entry.getKey();
      ExpectedEntry expected = backend.get(name);
      ExpectedEntry actual = entry.getValue();
      assertNotNull(expected, "errorCodeMap 含后端未定义的错误名: " + name);
      assertEquals(expected.code(), actual.code(), "元数据 code 漂移: " + name);
      assertEquals(expected.message(), actual.message(), "提示文案漂移: " + name);
      assertEquals(expected.messageKey(), actual.messageKey(), "i18n messageKey 漂移: " + name);
    }
  }

  @Test
  void bothBackendEnumsShareOneCodeSpace() {
    Map<String, Integer> codesByName = frontendCodesByName();
    assertEquals(
        new TreeSet<>(codesByName.values()).size(),
        codesByName.size(),
        "错误码值撞号：同一数值被两个枚举占用，前端无法按码值反查");
  }

  /** 一条错误码在前端/后端的共同形状。{@code messageKey} 仅业务域有。 */
  private record ExpectedEntry(Integer code, String message, String messageKey) {}
}
