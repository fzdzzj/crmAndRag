package com.slz.crm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.slz.crm.CrmApplication;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.integration.permission.EndpointCoverage;
import com.slz.crm.integration.permission.OpenEndpointRegistry;
import com.slz.crm.integration.permission.PermissionCoverageScanner;
import com.slz.crm.server.config.OpenApiPermissionCustomizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约权限维守卫（retrieval-and-contract-hardening G3 任务 3.4）：P1 完整性 + P2 与权威扫描器集合相等。
 *
 * <p><b>P1</b>：每个 operation 恰有 {@code x-required-permission} 与 {@code x-permission-status: OPEN}
 * 之一，**0 例外**。
 *
 * <p><b>P2 的两条独立路径</b>（这是本用例的全部价值，不许拿同一份反射结果比两次）：
 *
 * <ul>
 *   <li><b>路径 A（契约）</b>：MockMvc 打 {@code /v3/api-docs.yaml} → SnakeYAML 解析 → 从 {@code
 *       paths.*.<method>} 的 {@code x-} 扩展反推出 {@code SECURED(path|METHOD)→code} 与 {@code OPEN 集合}；
 *   <li><b>路径 B（源码事实）</b>：{@link PermissionCoverageScanner#scanEndpoints()} 反射扫方法级
 *       {@code @RequirePermission} + {@link OpenEndpointRegistry} 显式登记表——**完全不读契约**。
 * </ul>
 *
 * <p>两条路径唯一的归一化 = **去尾部 `/`**（Spring 路由与 springdoc 对根路径的写法差异），其他任何归一化都不允许。
 */
@SpringBootTest(
    classes = CrmApplication.class,
    properties = {
      "rag.vector-store.provider=in-memory",
      "knowledge.storage.provider=in-memory",
      "crm.ai.knowledge-retrieval.mock-enabled=false",
      "spring.ai.dashscope.api-key=sk-openapi-contract-placeholder",
      "spring.flyway.enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class OpenApiPermissionContractParityTest {

  /** 扫描器对"方法级 mapping 未声明方法"的占位值。 */
  private static final String METHOD_ANY = "ANY";

  /** springdoc 对无方法级 mapping 的 operation 的展开集（实测 7 个；TRACE 不导出）。 */
  private static final List<String> SPRINGDOC_EXPANDED_METHODS =
      List.of("GET", "PUT", "POST", "DELETE", "PATCH", "HEAD", "OPTIONS");

  /** springdoc 会导出的全部 operation 方法名（小写，Spec 侧键名）。 */
  private static final List<String> OPERATION_METHODS =
      List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("P1：每个 operation 恰有 (a) x-required-permission 或 (b) x-permission-status 之一（0 例外）")
  void everyOperationDeclaresExactlyOnePermissionDimension() {
    Map<?, ?> paths = section(spec(), "paths");
    List<String> violations = new ArrayList<>();
    int total = 0;
    int secured = 0;
    int open = 0;
    for (Map.Entry<?, ?> entry : paths.entrySet()) {
      String path = String.valueOf(entry.getKey());
      Map<?, ?> pathItem = asMap(entry.getValue());
      for (String method : OPERATION_METHODS) {
        Map<?, ?> operation = asMapOrNull(pathItem.get(method));
        if (operation == null) {
          continue;
        }
        total++;
        Object required = operation.get(OpenApiPermissionCustomizer.EXTENSION_REQUIRED_PERMISSION);
        Object status = operation.get(OpenApiPermissionCustomizer.EXTENSION_PERMISSION_STATUS);
        if (required != null && status != null) {
          violations.add(path + " " + method + " 同时带 (a)(b)");
        } else if (required == null && status == null) {
          violations.add(path + " " + method + " 两者都缺");
        } else if (required != null) {
          secured++;
          Map<?, ?> permission = asMap(required);
          if (!(permission.get(OpenApiPermissionCustomizer.FIELD_CODE) instanceof Number)) {
            violations.add(path + " " + method + " 的 code 不是数字: " + permission.get("code"));
          }
          String name = String.valueOf(permission.get(OpenApiPermissionCustomizer.FIELD_NAME));
          if (!name.equals(name.toUpperCase())) {
            violations.add(path + " " + method + " 的 name 不是全大写: " + name);
          }
        } else {
          open++;
          if (!OpenApiPermissionCustomizer.STATUS_OPEN.equals(String.valueOf(status))) {
            violations.add(path + " " + method + " 的 OPEN 取值非法: " + status);
          }
        }
      }
    }
    assertEquals(List.of(), violations, "P1 必须 0 例外");
    assertTrue(total > 0, "没解析到任何 operation，契约结构变了？");
    System.out.println("[G3][P1] operations=" + total + " SECURED=" + secured + " OPEN=" + open);
  }

  @Test
  @DisplayName("P2：契约反推的 SECURED/OPEN 与扫描器+登记表结果集合相等（两条独立路径）")
  void contractPermissionSetsMatchScannerAndRegistry() {
    Map<String, Long> contractSecured = contractSecured();
    Set<String> contractOpen = contractOpen();
    Map<String, Long> scannerSecured = scannerSecured();
    Set<String> scannerOpen = scannerOpen();

    assertSizeSane(contractSecured, contractOpen);
    assertEquals(
        scannerSecured,
        contractSecured,
        "SECURED 集合不等（左=扫描器，右=契约）；差异：" + difference(scannerSecured, contractSecured));
    assertEquals(
        scannerOpen,
        contractOpen,
        "OPEN 集合不等（左=登记表+扫描器，右=契约）；差异：" + difference(scannerOpen, contractOpen));
    System.out.println(
        "[G3][P2] SECURED="
            + contractSecured.size()
            + " OPEN="
            + contractOpen.size()
            + "（两路径集合相等）");
  }

  // ==================== 路径 A：契约 ====================

  private Map<String, Long> contractSecured() {
    Map<String, Long> secured = new TreeMap<>();
    for (Operation4Test operation : contractOperations()) {
      Object required =
          operation.operation().get(OpenApiPermissionCustomizer.EXTENSION_REQUIRED_PERMISSION);
      if (required != null) {
        Object code = asMap(required).get(OpenApiPermissionCustomizer.FIELD_CODE);
        secured.put(operation.key(), ((Number) code).longValue());
      }
    }
    return secured;
  }

  private Set<String> contractOpen() {
    Set<String> open = new TreeSet<>();
    for (Operation4Test operation : contractOperations()) {
      if (operation
          .operation()
          .containsKey(OpenApiPermissionCustomizer.EXTENSION_PERMISSION_STATUS)) {
        open.add(operation.key());
      }
    }
    return open;
  }

  private List<Operation4Test> contractOperations() {
    Map<?, ?> paths = section(spec(), "paths");
    List<Operation4Test> operations = new ArrayList<>();
    for (Map.Entry<?, ?> entry : paths.entrySet()) {
      String path = normalize(String.valueOf(entry.getKey()));
      Map<?, ?> pathItem = asMap(entry.getValue());
      for (String method : OPERATION_METHODS) {
        Map<?, ?> operation = asMapOrNull(pathItem.get(method));
        if (operation != null) {
          operations.add(new Operation4Test(path + "|" + method.toUpperCase(), operation));
        }
      }
    }
    return operations;
  }

  private record Operation4Test(String key, Map<?, ?> operation) {}

  // ==================== 路径 B：扫描器 + 登记表（不读契约） ====================

  private static Map<String, Long> scannerSecured() {
    Map<String, Long> secured = new TreeMap<>();
    for (EndpointCoverage endpoint : PermissionCoverageScanner.scanEndpoints()) {
      if (!endpoint.secured()) {
        continue;
      }
      PermissionOperates operate = PermissionOperates.valueOf(endpoint.permissionName());
      for (String method : httpMethods(endpoint)) {
        secured.put(normalize(endpoint.path()) + "|" + method, operate.getId());
      }
    }
    return secured;
  }

  private static Set<String> scannerOpen() {
    Set<String> open = new TreeSet<>();
    for (EndpointCoverage endpoint : PermissionCoverageScanner.scanEndpoints()) {
      if (endpoint.secured()) {
        continue;
      }
      String path = normalize(endpoint.path());
      for (String method : httpMethods(endpoint)) {
        assertTrue(
            OpenEndpointRegistry.isIntentionalOpen(method, path)
                || OpenEndpointRegistry.isPendingDecision(method, path),
            "扫描器发现零注解端点但登记表没有: " + method + " " + path);
        open.add(path + "|" + method);
      }
    }
    return open;
  }

  /**
   * 扫描器给出的 HTTP 方法集合。
   *
   * <p>方法级 mapping 未声明方法时扫描器给 {@code ANY}；springdoc 对这类 operation 会把**全部 7 个方法**都导出成独立
   * operation（TRACE 不导出）。这是 springdoc 的导出形状，不是契约与源码的分歧，故此处按常量展开 7 个方法（**不读契约**， 保持两条路径独立）；若将来
   * springdoc 改了展开集，本用例会以集合差异如实变红。
   */
  private static List<String> httpMethods(EndpointCoverage endpoint) {
    if (METHOD_ANY.equalsIgnoreCase(endpoint.httpMethod())) {
      return SPRINGDOC_EXPANDED_METHODS;
    }
    return List.of(endpoint.httpMethod().toUpperCase());
  }

  // ==================== 工具 ====================

  private Map<?, ?> spec() {
    try {
      String yaml =
          mockMvc
              .perform(get("/v3/api-docs.yaml"))
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);
      assertTrue(yaml.startsWith("openapi: 3."), "导出规范不是 OpenAPI 3.x");
      return asMap(new Yaml().load(yaml));
    } catch (Exception exception) {
      throw new IllegalStateException("调用 /v3/api-docs.yaml 失败", exception);
    }
  }

  /** 唯一允许的归一化：去尾部 {@code /}（根路径除外）。 */
  private static String normalize(String path) {
    String normalized = path;
    while (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static void assertSizeSane(Map<String, Long> secured, Set<String> open) {
    assertTrue(secured.size() > 0, "契约里没有 SECURED operation");
    assertTrue(open.size() > 0, "契约里没有 OPEN operation");
    Set<String> overlap = new LinkedHashSet<>(secured.keySet());
    overlap.retainAll(open);
    assertEquals(Set.of(), Set.copyOf(overlap), "同一 operation 不得既 SECURED 又 OPEN");
  }

  private static String difference(Map<String, Long> left, Map<String, Long> right) {
    List<String> diffs = new ArrayList<>();
    for (Map.Entry<String, Long> entry : left.entrySet()) {
      Long other = right.get(entry.getKey());
      if (other == null) {
        diffs.add("仅左有 " + entry.getKey() + "=" + entry.getValue());
      } else if (!other.equals(entry.getValue())) {
        diffs.add("码不同 " + entry.getKey() + " 左=" + entry.getValue() + " 右=" + other);
      }
    }
    for (String key : right.keySet()) {
      if (!left.containsKey(key)) {
        diffs.add("仅右有 " + key + "=" + right.get(key));
      }
    }
    return String.join("; ", diffs.subList(0, Math.min(10, diffs.size())));
  }

  private static String difference(Set<String> left, Set<String> right) {
    List<String> diffs = new ArrayList<>();
    for (String key : left) {
      if (!right.contains(key)) {
        diffs.add("仅左有 " + key);
      }
    }
    for (String key : right) {
      if (!left.contains(key)) {
        diffs.add("仅右有 " + key);
      }
    }
    return String.join("; ", diffs.subList(0, Math.min(10, diffs.size())));
  }

  @SuppressWarnings("unchecked")
  private static Map<?, ?> section(Map<?, ?> parent, String key) {
    Object value = parent.get(key);
    assertTrue(value instanceof Map, "契约里缺少节点 " + key + "，结构变了要同步本用例");
    return (Map<?, ?>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<?, ?> asMap(Object value) {
    return new LinkedHashMap<>((Map<Object, Object>) value);
  }

  private static Map<?, ?> asMapOrNull(Object value) {
    return value instanceof Map ? asMap(value) : null;
  }
}
