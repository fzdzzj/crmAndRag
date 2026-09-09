package com.slz.crm.server.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 契约自定义（简化版：multipart 自动检测 + 叶子字段展开）
 *
 * <p>1. 清空 operationId：前端 SDK 按 method+path 生成函数名，
 * springdoc 默认按方法名生成 operationId 会导致 SDK 全量改名，清空后与既有契约保持一致。
 * <p>2. 固定 info 头、清空 servers：保证导出契约稳定可 diff，且不写入本地实例地址。
 * <p>3. multipart 自动修正：springdoc 在 default-flat-param-object 下会把 @ModelAttribute
 * 上传 DTO 拍平成 query 参数。检测到参数 schema（含 $ref/数组递归解析）带 format=binary，
 * 即把整个接口重写为 multipart/form-data；数组 DTO 参数按 name[0].属性 展开为叶子字段，
 * 与前端 SDK 的 FormData 拍平用法保持一致。新增上传接口只要用 @ModelAttribute + MultipartFile
 * 即自动正确契约化，无需维护手写字段表。
 */
@Configuration
public class OpenApiConfig {

    /** 兜底接口：无 binary 字段但前端以 FormData 提交 */
    private static final Set<String> EXTRA_MULTIPART = Set.of("/sales/stage|put");

    @Bean
    public GlobalOpenApiCustomizer openApiCustomizer() {
        return openApi -> {
            Map<String, Schema> components = openApi.getComponents() != null
                    && openApi.getComponents().getSchemas() != null
                    ? openApi.getComponents().getSchemas() : Map.of();
            if (openApi.getPaths() != null) {
                openApi.getPaths().forEach((path, pathItem) -> {
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
    private static void rewriteMultipart(Operation operation, String path, String method,
                                         Map<String, Schema> components) {
        if (operation == null || operation.getParameters() == null) {
            return;
        }
        boolean multipart = EXTRA_MULTIPART.contains(path + "|" + method)
                || operation.getParameters().stream()
                        .filter(parameter -> "query".equals(parameter.getIn()))
                        .anyMatch(parameter -> containsBinary(parameter.getSchema(), components, new HashSet<>()));
        if (!multipart) {
            return;
        }
        // 只保留 path 参数；query 拍平参数转为 multipart 表单字段（数组 DTO 展开为叶子字段）
        Map<String, Schema> properties = new LinkedHashMap<>();
        List<Parameter> kept = new ArrayList<>();
        for (Parameter parameter : operation.getParameters()) {
            if ("path".equals(parameter.getIn())) {
                kept.add(parameter);
            } else if ("query".equals(parameter.getIn())) {
                expandFlattened(parameter.getName(), parameter.getSchema(), components, properties, new HashSet<>());
            }
        }
        operation.setParameters(kept.isEmpty() ? null : kept);
        ObjectSchema schema = new ObjectSchema();
        schema.properties(properties);
        operation.setRequestBody(new RequestBody()
                .content(new Content().addMediaType(MediaType.MULTIPART_FORM_DATA_VALUE,
                        new io.swagger.v3.oas.models.media.MediaType().schema(schema)))
                .required(false));
    }

    /**
     * 展开拍平的 query 参数为 multipart 表单字段。
     * 数组 DTO 参数（如 approvalAttachment）按 name[0].属性 展开叶子字段，
     * 保留 DTO 属性的 type/format/description，与前端 FormData 用法一致。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void expandFlattened(String name, Schema schema, Map<String, Schema> components,
                                        Map<String, Schema> properties, Set<String> visited) {
        if (schema == null) {
            return;
        }
        Schema items = schema.getItems();
        if (items != null) {
            Schema itemResolved = resolveRef(items, components, visited);
            if (itemResolved != null && itemResolved.getProperties() != null) {
                itemResolved.getProperties().forEach((propName, propSchema) ->
                        properties.put(name + "[0]." + propName, (Schema) propSchema));
                return;
            }
        }
        properties.put(name, schema);
    }

    /** 深度检测 schema（递归解析 $ref/数组/对象属性）是否含 format=binary 字段 */
    @SuppressWarnings("rawtypes")
    private static boolean containsBinary(Schema schema, Map<String, Schema> components, Set<String> visited) {
        if (schema == null) {
            return false;
        }
        if ("binary".equals(schema.getFormat())) {
            return true;
        }
        Schema resolved = resolveRef(schema, components, visited);
        if (resolved != schema) {
            return containsBinary(resolved, components, visited);
        }
        if (schema.getItems() != null) {
            return containsBinary(schema.getItems(), components, visited);
        }
        if (schema.getProperties() != null) {
            for (Object child : schema.getProperties().values()) {
                if (containsBinary((Schema) child, components, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 解析 $ref 到 components 中的实际 schema；无法解析或循环引用时返回原 schema */
    @SuppressWarnings("rawtypes")
    private static Schema resolveRef(Schema schema, Map<String, Schema> components, Set<String> visited) {
        String ref = schema.get$ref();
        if (ref == null) {
            return schema;
        }
        String refName = ref.substring(ref.lastIndexOf('/') + 1);
        if (!visited.add(refName)) {
            return schema;
        }
        Schema target = components.get(refName);
        return target != null ? target : schema;
    }
}
