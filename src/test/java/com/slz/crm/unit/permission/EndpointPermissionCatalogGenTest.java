package com.slz.crm.unit.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.integration.permission.EndpointCoverage;
import com.slz.crm.integration.permission.PermissionCoverageScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 端点权限类型生成物校验（evidence-to-operations G2 任务 2.1.1~2.1.4）。
 *
 * <p>生成物 {@code frontend/src/types/endpointPermission.gen.ts} 必须与 {@code frontend/openapi.yaml}
 * 的权限扩展逐字一致： 遍历 {@code paths.*.<method>}，键 = {@code 'METHOD /path'}（方法大写、path 原样——契约 {@code
 * operationId} 实测 0 个， 端点唯一标识只能是方法 + 路径）；有 {@code x-required-permission} → {@code { code: N; name:
 * 'X' }}，有 {@code x-permission-status: OPEN} → {@code { status: 'OPEN' }}，两者都无 → 不生成。
 *
 * <p>默认（surefire）<b>校验</b>：不一致即失败并打印首个差异行；{@code -Dpermission.endpoints.update=true} 时<b>重写</b>该文件。
 * P1 双口径：生成 key 数 = 带任一 {@code x-} 权限标记的 operation 数 = 权威扫描器（{@link PermissionCoverageScanner}）导出的
 * SECURED+OPEN 计数，两口径任一不等即红。
 *
 * <p>只生成 {@code type}，零运行时代码：前端包体预算（{@code frontend/bundle-budget.json}）不受影响。
 */
@DisplayName("端点权限类型生成物校验（endpointPermission.gen.ts）")
class EndpointPermissionCatalogGenTest {

  /** 重写开关：只有显式 -Dpermission.endpoints.update=true 才写文件。 */
  private static final String UPDATE_PROP = "permission.endpoints.update";

  /** 生成物路径（相对项目根；surefire 的工作目录 = 模块根）。 */
  private static final Path GENERATED =
      Path.of("frontend", "src", "types", "endpointPermission.gen.ts");

  /** 契约真相源（冻结契约，本测试只读）。 */
  private static final Path CONTRACT = Path.of("frontend", "openapi.yaml");

  /** springdoc 会导出的全部 operation 方法名（小写，Spec 侧键名）。 */
  private static final List<String> OPERATION_METHODS =
      List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

  /** 扫描器对"方法级 mapping 未声明方法"的占位值。 */
  private static final String METHOD_ANY = "ANY";

  /** springdoc 对无方法级 mapping 的 operation 的展开集（实测 7 个；TRACE 不导出）。 */
  private static final List<String> SPRINGDOC_EXPANDED_METHODS =
      List.of("GET", "PUT", "POST", "DELETE", "PATCH", "HEAD", "OPTIONS");

  /** 权威扫描器判定的开放端点在映射类型里的取值。 */
  private static final String OPEN_MARKER = "OPEN";

  @Test
  @DisplayName("生成物与契约权限扩展逐字一致（或按开关重写）")
  void generatedFileMatchesContract() throws IOException {
    Map<String, String> expectedByKey = contractPermissionByKey();
    String expected = render(expectedByKey);
    boolean update = "true".equalsIgnoreCase(System.getProperty(UPDATE_PROP, "false").strip());
    if (update) {
      Files.createDirectories(GENERATED.getParent());
      Files.writeString(GENERATED, expected, StandardCharsets.UTF_8);
      System.out.println("[endpoint-permission] 已重写 " + GENERATED.toAbsolutePath());
      return;
    }
    assertTrue(
        Files.isRegularFile(GENERATED),
        "生成物缺失：" + GENERATED + "（用 -Dpermission.endpoints.update=true 生成）");
    String actual = Files.readString(GENERATED, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertEquals(expected, actual, () -> firstDifference(expected, actual));
  }

  @Test
  @DisplayName("P1：生成 key 集合与权威扫描器 SECURED+OPEN 集合相等（两条独立路径）")
  void generatedKeysMatchAuthoritativeScanner() throws IOException {
    Map<String, String> contract = contractPermissionByKey();
    Map<String, String> scanner = scannerPermissionByKey();
    assertEquals(
        scanner,
        contract,
        "契约权限映射与权威扫描器不等（左=扫描器，右=契约）；" + "差异样例: " + difference(scanner, contract));
    System.out.println(
        "[endpoint-permission] P1 双口径相等: keys=" + contract.size() + "（scanner 独立计数一致）");
  }

  @Test
  @DisplayName("生成物只含 type，零运行时代码")
  void generatedFileHasNoRuntimeCode() throws IOException {
    boolean update = "true".equalsIgnoreCase(System.getProperty(UPDATE_PROP, "false").strip());
    // 写模式首轮（生成物尚未落盘）跳过本用例；默认模式下生成物必须存在且零运行时代码
    assumeTrue(!update || Files.isRegularFile(GENERATED), "写模式首轮：生成物尚未重写，先跑用例 1");
    String actual = Files.readString(GENERATED, StandardCharsets.UTF_8).replace("\r\n", "\n");
    List<String> offenders = new ArrayList<>();
    for (String line : actual.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()
          || trimmed.startsWith("//")
          || trimmed.startsWith("/*")
          || trimmed.startsWith("*")) {
        continue;
      }
      if (!trimmed.startsWith("export type ")
          && !trimmed.startsWith("'")
          && !trimmed.startsWith("};")
          && !trimmed.startsWith("{ code:")
          && !trimmed.startsWith("{ status:")) {
        offenders.add(trimmed);
      }
    }
    assertTrue(offenders.isEmpty(), "生成物含非 type 内容（违反零运行时代码）：" + offenders);
  }

  // ==================== 契约侧 ====================

  /** 契约解析：key = 'METHOD /path' → 权限名（SECURED）或 OPEN（登记开放）。 */
  private Map<String, String> contractPermissionByKey() throws IOException {
    String yaml = Files.readString(CONTRACT, StandardCharsets.UTF_8);
    Object root = new Yaml().load(yaml);
    assertTrue(root instanceof Map, "openapi.yaml 不是映射，结构变了？");
    Object paths = asMap(root).get("paths");
    assertTrue(paths instanceof Map, "openapi.yaml 缺 paths 节点");
    Map<String, String> byKey = new TreeMap<>();
    for (Map.Entry<String, Object> pathEntry : asMap(paths).entrySet()) {
      String path = String.valueOf(pathEntry.getKey());
      Map<String, Object> pathItem = asMap(pathEntry.getValue());
      for (String method : OPERATION_METHODS) {
        Object operation = pathItem.get(method);
        if (!(operation instanceof Map)) {
          continue;
        }
        Map<String, Object> operationMap = asMap(operation);
        Object required = operationMap.get("x-required-permission");
        Object status = operationMap.get("x-permission-status");
        if (required != null && status != null) {
          throw new IllegalStateException(
              "operation 同时带 (a)(b): " + method.toUpperCase() + " " + path);
        }
        String key = method.toUpperCase() + " " + path;
        if (required != null) {
          Object name = asMap(required).get("name");
          assertTrue(
              name != null && !String.valueOf(name).isBlank(),
              "x-required-permission 缺 name: " + key);
          byKey.put(key, String.valueOf(name));
        } else if (status != null) {
          assertEquals(OPEN_MARKER, String.valueOf(status), "x-permission-status 取值非法: " + key);
          byKey.put(key, OPEN_MARKER);
        }
      }
    }
    assertTrue(byKey.size() > 0, "契约里没有带权限标记的 operation");
    return byKey;
  }

  // ==================== 扫描器侧（独立路径，不读契约） ====================

  private static Map<String, String> scannerPermissionByKey() {
    Map<String, String> byKey = new TreeMap<>();
    for (EndpointCoverage endpoint : PermissionCoverageScanner.scanEndpoints()) {
      String name = endpoint.secured() ? endpoint.permissionName() : OPEN_MARKER;
      for (String method : httpMethods(endpoint)) {
        byKey.put(method + " " + normalize(endpoint.path()), name);
      }
    }
    return byKey;
  }

  /** 扫描器给出的 HTTP 方法集合：方法级 mapping 未声明方法时为 {@code ANY}，按 springdoc 展开集（7 方法）展开。 */
  private static List<String> httpMethods(EndpointCoverage endpoint) {
    if (METHOD_ANY.equalsIgnoreCase(endpoint.httpMethod())) {
      return SPRINGDOC_EXPANDED_METHODS;
    }
    return List.of(endpoint.httpMethod().toUpperCase());
  }

  // ==================== 渲染 ====================

  /** 按契约映射渲染生成物全文（末尾带换行）。 */
  private static String render(Map<String, String> byKey) {
    long secured = byKey.values().stream().filter(value -> !OPEN_MARKER.equals(value)).count();
    long open = byKey.size() - secured;
    StringBuilder out = new StringBuilder();
    out.append("// 本文件由 EndpointPermissionCatalogGenTest 从 frontend/openapi.yaml 生成，请勿手改。\n");
    out.append("// 键 = 'METHOD /path'（方法大写、path 原样；契约 operationId 实测 0 个，端点唯一标识只能是方法 + 路径）。\n");
    out.append(
        "// 取值：x-required-permission → { code: N; name: 'X' }；x-permission-status: OPEN → { status: 'OPEN' }；两者都无 → 不生成。\n");
    out.append("// 默认（mvn verify 内的 surefire）逐字校验：不一致即失败并打印首个差异。\n");
    out.append("// 重写只由测试触发：\n");
    out.append(
        "//   mvn -B -ntp test -Dtest=EndpointPermissionCatalogGenTest -Dpermission.endpoints.update=true\n");
    out.append("// 只生成 type，零运行时代码（不增加前端包体预算）。\n");
    out.append("// 实测口径：SECURED ")
        .append(secured)
        .append(" + OPEN ")
        .append(open)
        .append(" = ")
        .append(byKey.size())
        .append(" 个 operation。\n");
    out.append('\n');
    out.append("/** 契约端点 → 权限要求映射（SECURED 带 code/name，OPEN 带 status）。 */\n");
    out.append("export type EndpointPermissionMap = {\n");
    for (Map.Entry<String, String> entry : byKey.entrySet()) {
      if (OPEN_MARKER.equals(entry.getValue())) {
        out.append("  '").append(entry.getKey()).append("': { status: 'OPEN' };\n");
      } else {
        long code = PermissionOperates.valueOf(entry.getValue()).getId();
        out.append("  '")
            .append(entry.getKey())
            .append("': { code: ")
            .append(code)
            .append("; name: '")
            .append(entry.getValue())
            .append("' };\n");
      }
    }
    out.append("};\n");
    out.append('\n');
    out.append("/** 全部带权限标记的契约端点键联合。 */\n");
    out.append("export type EndpointKey = keyof EndpointPermissionMap;\n");
    return out.toString();
  }

  // ==================== 工具 ====================

  /** 唯一允许的归一化：去尾部 {@code /}（根路径除外）。 */
  private static String normalize(String path) {
    String normalized = path;
    while (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return new LinkedHashMap<>((Map<String, Object>) value);
  }

  private static String firstDifference(String expected, String actual) {
    String[] wantLines = expected.split("\n", -1);
    String[] gotLines = actual.split("\n", -1);
    int max = Math.max(wantLines.length, gotLines.length);
    for (int i = 0; i < max; i++) {
      String want = i < wantLines.length ? wantLines[i] : "<文件已结束>";
      String got = i < gotLines.length ? gotLines[i] : "<文件已结束>";
      if (!want.equals(got)) {
        return "生成物与契约不一致，首个差异在第 "
            + (i + 1)
            + " 行：\n  期望: "
            + want
            + "\n  实际: "
            + got
            + "\n（重写：-Dpermission.endpoints.update=true）";
      }
    }
    return "生成物与契约不一致（行内容全同但字节不同，检查行尾/编码）";
  }

  /** 集合差异样例（最多 8 条，防消息爆炸）。 */
  private static String difference(Map<String, String> left, Map<String, String> right) {
    List<String> diffs = new ArrayList<>();
    for (Map.Entry<String, String> entry : left.entrySet()) {
      String other = right.get(entry.getKey());
      if (other == null) {
        diffs.add("仅扫描器有 " + entry.getKey() + "=" + entry.getValue());
      } else if (!other.equals(entry.getValue())) {
        diffs.add("值不同 " + entry.getKey() + " 扫描器=" + entry.getValue() + " 契约=" + other);
      }
    }
    for (Map.Entry<String, String> entry : right.entrySet()) {
      if (!left.containsKey(entry.getKey())) {
        diffs.add("仅契约有 " + entry.getKey() + "=" + entry.getValue());
      }
    }
    return String.join("; ", diffs.subList(0, Math.min(8, diffs.size())));
  }
}
