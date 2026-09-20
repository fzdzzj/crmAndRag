package com.slz.crm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.slz.crm.CrmApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.yaml.snakeyaml.Yaml;

/**
 * 前后端 API 契约一致性门禁：把 {@code frontend/openapi.yaml} 钉死为后端 springdoc 的<b>当前</b>导出。
 *
 * <p>做法是启动完整上下文（H2 + test profile，不依赖 Docker/Qdrant/MinIO），用 MockMvc 打 {@code /v3/api-docs.yaml}
 * 拿到后端真实契约，落盘到 {@code target/openapi/openapi.yaml} 后与仓库里 提交的前端契约逐字比对。控制器新增/改名/改结构都会在此变红。
 *
 * <p>漂移修复：{@code bash scripts/check-openapi-consistency.sh --update}（覆盖前端契约后需 {@code pnpm gen:api}
 * 重新生成 SDK）。比较前统一换行符，因为 Windows 工作树是 CRLF、仓库内是 LF。
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
// dev 叠在 test 之后：JWT/权限拦截器只在 dev/local profile 放行 /v3/api-docs（见
// WebMvcConfiguration#exposesOpenApi，生产不匿名暴露接口结构），而导出契约走的正是这条本地路径。
// 数据源仍是 test 的 H2（dev 不改 datasource），Flyway 由上面的 properties 关掉。
@ActiveProfiles({"test", "dev"})
class OpenApiContractExportTest {

  private static final Path COMMITTED_CONTRACT = ContractFiles.find("frontend/openapi.yaml");
  private static final Path EXPORTED_CONTRACT =
      COMMITTED_CONTRACT
          .getParent()
          .getParent()
          .resolve(Path.of("target", "openapi", "openapi.yaml"));

  /** 用裸 {@code @RequestParam("file") MultipartFile} 声明的上传接口，契约必须给出 multipart 请求体。 */
  private static final Map<String, List<String>> UPLOAD_ENDPOINTS =
      Map.of(
          "/company/list", List.of("file"),
          "/contact/list", List.of("file"),
          "/knowledge/files", List.of("file", "kbId"),
          "/ai/sessions/{sessionId}/images", List.of("file"));

  @Autowired private MockMvc mockMvc;

  @Test
  void backendSwaggerSpecMatchesCommittedFrontendContract() throws IOException {
    String exported = fetchSpec();
    writeExported(exported);

    String committed = normalize(Files.readString(COMMITTED_CONTRACT, StandardCharsets.UTF_8));
    assertEquals(
        committed,
        exported,
        "前后端契约漂移：前端 openapi.yaml 落后于后端 swagger。"
            + " 请跑 `bash scripts/check-openapi-consistency.sh --update` 覆盖契约，"
            + " 再在 frontend/ 跑 `pnpm gen:api` 重新生成 SDK，两边同一次提交。");
  }

  /**
   * 上传接口的请求体形状：springdoc 在 {@code default-flat-param-object} 下会把裸 MultipartFile 参数整个吞掉， 生成出来的 SDK
   * 就会把 body 判成 {@code undefined}，前端 {@code {body: {file}}} 直接编译不过。 {@code OpenApiConfig} 负责补回
   * binary 参数并改写成 multipart/form-data，本用例守住这条补偿。
   */
  @Test
  void uploadEndpointsDescribeMultipartRequestBody() {
    Map<String, Object> spec = new Yaml().load(fetchSpec());
    Map<?, ?> paths = section(spec, "paths");
    UPLOAD_ENDPOINTS.forEach(
        (path, expectedFields) -> {
          Map<?, ?> operation = section(section(paths, path), "post");
          Map<?, ?> content = section(section(operation, "requestBody"), "content");
          Map<?, ?> multipart = section(content, "multipart/form-data");
          Map<?, ?> properties = section(section(multipart, "schema"), "properties");
          for (String field : expectedFields) {
            assertNotNull(properties.get(field), path + " 缺少 multipart 字段 " + field);
          }
          Map<?, ?> fileSchema = section(properties, "file");
          assertEquals("binary", fileSchema.get("format"), path + " 的 file 字段应为 format: binary");
        });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<?, ?> parent, String key) {
    Object value = parent.get(key);
    assertTrue(value instanceof Map, "契约里缺少节点 " + key + "，结构变了要同步本用例");
    return (Map<String, Object>) value;
  }

  /** 取后端当前 OpenAPI 规范文本（已统一换行符）。 */
  private String fetchSpec() {
    try {
      MvcResult result = mockMvc.perform(get("/v3/api-docs.yaml")).andReturn();
      assertEquals(200, result.getResponse().getStatus(), "后端未导出 OpenAPI，检查 springdoc.api-docs 配置");
      String yaml = normalize(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
      assertFalse(yaml.isBlank(), "springdoc 返回了空规范");
      assertTrue(yaml.startsWith("openapi: 3."), "导出规范不是 OpenAPI 3.x: " + yaml.split("\n", 2)[0]);
      return yaml;
    } catch (Exception e) {
      throw new IllegalStateException("调用 /v3/api-docs.yaml 失败", e);
    }
  }

  /** 统一换行符并收敛到单个结尾换行，避免 CRLF/LF 造成假漂移。 */
  private static String normalize(String yaml) {
    String unified = yaml.replace("\r\n", "\n").replace("\r", "\n");
    return unified.replaceAll("\n+$", "") + "\n";
  }

  private static void writeExported(String yaml) {
    try {
      Files.createDirectories(EXPORTED_CONTRACT.getParent());
      Files.writeString(EXPORTED_CONTRACT, yaml, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("写出导出契约失败: " + EXPORTED_CONTRACT, e);
    }
  }
}
