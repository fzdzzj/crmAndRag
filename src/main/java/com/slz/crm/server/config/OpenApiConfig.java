package com.slz.crm.server.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * OpenAPI 契约自定义（简化版：multipart 自动检测 + 叶子字段展开）
 *
 * <p>1. 清空 operationId：前端 SDK 按 method+path 生成函数名， springdoc 默认按方法名生成 operationId 会导致 SDK
 * 全量改名，清空后与既有契约保持一致。
 *
 * <p>2. 固定 info 头、清空 servers：保证导出契约稳定可 diff，且不写入本地实例地址。
 *
 * <p>3. multipart 自动修正：springdoc 在 default-flat-param-object 下会把 @ModelAttribute 上传 DTO 拍平成 query
 * 参数。检测到参数 schema（含 $ref/数组递归解析）带 format=binary， 即把整个接口重写为 multipart/form-data；数组 DTO 参数按
 * name[0].属性 展开为叶子字段， 与前端 SDK 的 FormData 拍平用法保持一致。新增上传接口只要用 @ModelAttribute + MultipartFile
 * 即自动正确契约化，无需维护手写字段表。
 */
@Configuration
public class OpenApiConfig {

  /** 兜底接口：无 binary 字段但前端以 FormData 提交 */
  private static final Set<String> EXTRA_MULTIPART = Set.of("/sales/stage|put");

  /**
   * 裸 {@code MultipartFile} 参数补偿：{@code default-flat-param-object: true} 下 springdoc 把 {@code
   * &#64;RequestParam("file") MultipartFile file} 当成待拍平的复杂对象，既不进 parameters 也不进请求体，
   * 导出的契约里上传接口就成了"没有请求体"，生成的 SDK 随之把 body 判成 undefined。
   *
   * <p>这里把这类参数补成 {@code format=binary} 的 query 参数，剩下的交给 {@link #openApiCustomizer()} 里已有的 multipart
   * 改写逻辑，保证与普通上传接口走同一条契约化路径。
   */
  @Bean
  public OperationCustomizer multipartFileParameterCustomizer() {
    return (operation, handlerMethod) -> {
      for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
        if (!MultipartFile.class.isAssignableFrom(parameter.getParameterType())) {
          continue;
        }
        String name = multipartParameterName(parameter);
        if (name != null && !hasParameterNamed(operation, name)) {
          operation.addParametersItem(
              new Parameter()
                  .name(name)
                  .in("query")
                  .required(true)
                  .schema(new StringSchema().format("binary")));
        }
      }
      return operation;
    };
  }

  /** 只认显式命名的注解；未命名时退回参数名（需要 -parameters 编译，取不到就跳过该参数） */
  private static String multipartParameterName(MethodParameter parameter) {
    String named = null;
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    if (requestParam != null) {
      String rp = requestParam.value().isBlank() ? requestParam.name() : requestParam.value();
      if (!rp.isBlank()) {
        named = rp;
      }
    }
    if (named == null) {
      RequestPart requestPart = parameter.getParameterAnnotation(RequestPart.class);
      if (requestPart != null) {
        String rp = requestPart.value().isBlank() ? requestPart.name() : requestPart.value();
        if (!rp.isBlank()) {
          named = rp;
        }
      }
    }
    return named == null ? parameter.getParameterName() : named;
  }

  private static boolean hasParameterNamed(Operation operation, String name) {
    return operation.getParameters() != null
        && operation.getParameters().stream()
            .anyMatch(parameter -> name.equals(parameter.getName()));
  }

  @Bean
  public GlobalOpenApiCustomizer openApiCustomizer() {
    return openApi -> {
      Map<String, Schema> components =
          openApi.getComponents() != null && openApi.getComponents().getSchemas() != null
              ? openApi.getComponents().getSchemas()
              : Map.of();
      if (openApi.getPaths() != null) {
        openApi
            .getPaths()
            .forEach(
                (path, pathItem) -> {
                  if (pathItem == null) {
                    return;
                  }
                  rewriteMultipart(pathItem.getPost(), path, "post", components);
                  rewriteMultipart(pathItem.getPut(), path, "put", components);
                  pathItem.readOperations().forEach(operation -> operation.setOperationId(null));
                });
      }
      if (openApi.getInfo() != null) {
        openApi.getInfo().setTitle("API Documentation");
        openApi.getInfo().setVersion("1.0");
      }
      openApi.setServers(null);
    };
  }

  /** 拍平参数含 binary（或命中兜底名单）时，重写为 multipart/form-data 请求体 */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void rewriteMultipart(
      Operation operation, String path, String method, Map<String, Schema> components) {
    if (operation != null && operation.getParameters() != null) {
      boolean multipart =
          EXTRA_MULTIPART.contains(path + "|" + method)
              || operation.getParameters().stream()
                  .filter(parameter -> "query".equals(parameter.getIn()))
                  .anyMatch(
                      parameter ->
                          containsBinary(parameter.getSchema(), components, new HashSet<>()));
      if (multipart) {
        // 只保留 path 参数；query 拍平参数转为 multipart 表单字段（数组 DTO 展开为叶子字段）
        Map<String, Schema> properties = new LinkedHashMap<>();
        List<Parameter> kept = new ArrayList<>();
        for (Parameter parameter : operation.getParameters()) {
          if ("path".equals(parameter.getIn())) {
            kept.add(parameter);
          } else if ("query".equals(parameter.getIn())) {
            expandFlattened(
                parameter.getName(),
                parameter.getSchema(),
                components,
                properties,
                new HashSet<>());
          }
        }
        operation.setParameters(kept.isEmpty() ? null : kept);
        ObjectSchema schema = new ObjectSchema();
        schema.properties(properties);
        operation.setRequestBody(
            new RequestBody()
                .content(
                    new Content()
                        .addMediaType(
                            MediaType.MULTIPART_FORM_DATA_VALUE,
                            new io.swagger.v3.oas.models.media.MediaType().schema(schema)))
                .required(false));
      }
    }
  }

  /**
   * 展开拍平的 query 参数为 multipart 表单字段。 数组 DTO 参数（如 approvalAttachment）按 name[0].属性 展开叶子字段， 保留 DTO 属性的
   * type/format/description，与前端 FormData 用法一致。
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void expandFlattened(
      String name,
      Schema schema,
      Map<String, Schema> components,
      Map<String, Schema> properties,
      Set<String> visited) {
    if (schema != null) {
      Schema items = schema.getItems();
      if (items != null) {
        Schema itemResolved = resolveRef(items, components, visited);
        if (itemResolved != null && itemResolved.getProperties() != null) {
          itemResolved
              .getProperties()
              .forEach(
                  (propName, propSchema) ->
                      properties.put(name + "[0]." + propName, (Schema) propSchema));
        } else {
          properties.put(name, schema);
        }
      } else {
        properties.put(name, schema);
      }
    }
  }

  /** 深度检测 schema（递归解析 $ref/数组/对象属性）是否含 format=binary 字段 */
  @SuppressWarnings("rawtypes")
  private static boolean containsBinary(
      Schema schema, Map<String, Schema> components, Set<String> visited) {
    boolean result;
    if (schema == null) {
      result = false;
    } else if ("binary".equals(schema.getFormat())) {
      result = true;
    } else {
      Schema resolved = resolveRef(schema, components, visited);
      if (resolved != schema) {
        result = containsBinary(resolved, components, visited);
      } else if (schema.getItems() != null) {
        result = containsBinary(schema.getItems(), components, visited);
      } else if (schema.getProperties() != null) {
        result = false;
        for (Object child : schema.getProperties().values()) {
          if (containsBinary((Schema) child, components, visited)) {
            result = true;
            break;
          }
        }
      } else {
        result = false;
      }
    }
    return result;
  }

  /** 解析 $ref 到 components 中的实际 schema；无法解析或循环引用时返回原 schema */
  @SuppressWarnings("rawtypes")
  private static Schema resolveRef(
      Schema schema, Map<String, Schema> components, Set<String> visited) {
    final Schema result;
    String ref = schema.get$ref();
    if (ref == null) {
      result = schema;
    } else {
      String refName = ref.substring(ref.lastIndexOf('/') + 1);
      if (!visited.add(refName)) {
        result = schema;
      } else {
        Schema target = components.get(refName);
        result = target != null ? target : schema;
      }
    }
    return result;
  }
}
