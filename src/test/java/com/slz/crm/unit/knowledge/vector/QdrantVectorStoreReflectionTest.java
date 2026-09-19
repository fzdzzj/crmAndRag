package com.slz.crm.unit.knowledge.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.slz.crm.knowledge.vector.QdrantVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Qdrant 反射调用契约测试：protobuf 消息类只暴露 getter 与静态 newBuilder， setter/build 全在 Builder 上。历史缺陷即 Builder
 * 方法错打到消息类导致 NoSuchMethodException（集合初始化失败、readiness DOWN），此测试钉死该契约。
 */
class QdrantVectorStoreReflectionTest {

  /** 代码反射用到的全部 (消息类, Builder 方法, 参数) —— 与 QdrantVectorStore 逐一对应。 */
  private static final List<String> BUILDER_METHODS =
      List.of(
          "Collections$VectorParams|setSize|long.class",
          "Collections$VectorParams|setDistance|io.qdrant.client.grpc.Collections$Distance",
          "Points$SearchPoints|setCollectionName|java.lang.String",
          "Points$SearchPoints|addAllVector|java.lang.Iterable",
          "Points$SearchPoints|setLimit|long.class",
          "Points$SearchPoints|setWithPayload|io.qdrant.client.grpc.Points$WithPayloadSelector",
          "Points$SearchPoints|setScoreThreshold|float.class",
          "Points$SearchPoints|setFilter|io.qdrant.client.grpc.Points$Filter",
          "Points$WithPayloadSelector|setEnable|boolean.class");

  @Test
  void builderMethodsMustLiveOnBuilderClassNotMessageClass() throws Exception {
    for (String entry : BUILDER_METHODS) {
      String[] parts = entry.split("\\|");
      Class<?> messageClass = Class.forName("io.qdrant.client.grpc." + parts[0]);
      Method newBuilder = messageClass.getMethod("newBuilder");
      Object builder = newBuilder.invoke(null);
      Class<?>[] parameterTypes = parameterTypesOf(parts[2]);

      assertThrows(
          NoSuchMethodException.class,
          () -> messageClass.getMethod(parts[1], parameterTypes),
          parts[0] + " 不应暴露 " + parts[1]);
      builder.getClass().getMethod(parts[1], parameterTypes);
    }
  }

  @Test
  void filterBuildingShouldRoundTripMustConditions() throws Exception {
    Object filter = privateStatic("toFilter", Map.class).invoke(null, Map.of("category", "crm"));
    Filter built = assertInstanceOf(Filter.class, filter);
    assertEquals(1, built.getMustCount());
    assertEquals("category", built.getMust(0).getField().getKey());

    Object documentFilter = privateStatic("documentFilter", String.class).invoke(null, "doc-42");
    Filter docBuilt = assertInstanceOf(Filter.class, documentFilter);
    assertEquals(1, docBuilt.getMustCount());
    assertEquals("documentId", docBuilt.getMust(0).getField().getKey());
  }

  @Test
  void pointStructBuildingShouldRoundTripPayload() throws Exception {
    UUID pointId = UUID.randomUUID();
    VectorRecord record =
        new VectorRecord(
            pointId.toString(),
            "doc-1",
            "chunk-1",
            3,
            "文本内容",
            new float[] {0.5f, 0.25f},
            Map.of("category", "crm"));
    Object point = privateStatic("toPoint", VectorRecord.class).invoke(null, record);
    PointStruct struct = assertInstanceOf(PointStruct.class, point);
    assertEquals("文本内容", struct.getPayloadMap().get("text").getStringValue());
    assertEquals("crm", struct.getPayloadMap().get("category").getStringValue());
    assertEquals("doc-1", struct.getPayloadMap().get("documentId").getStringValue());
    assertEquals(2, struct.getVectors().getVector().getDataCount());
  }

  @Test
  void metadataParsingShouldReadValueTypes() throws Exception {
    Map<?, ?> payload =
        Map.of(
            "text",
            io.qdrant.client.ValueFactory.value("值"),
            "count",
            io.qdrant.client.ValueFactory.value(7L));
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        (Map<String, Object>) privateStatic("toMetadata", Map.class).invoke(null, payload);
    assertEquals("值", metadata.get("text"));
    assertEquals(7L, metadata.get("count"));
  }

  @Test
  void vectorParamsShouldBuildThroughBuilderRuntimeClass() throws Exception {
    // 与 ensureCollection 修复后的路径同构：运行时 Builder 类上查找 setSize/setDistance/build
    Object builder = VectorParams.newBuilder();
    builder.getClass().getMethod("setSize", long.class).invoke(builder, 1024L);
    builder
        .getClass()
        .getMethod("setDistance", io.qdrant.client.grpc.Collections.Distance.class)
        .invoke(builder, io.qdrant.client.grpc.Collections.Distance.Cosine);
    VectorParams params = (VectorParams) builder.getClass().getMethod("build").invoke(builder);
    assertEquals(1024, params.getSize());
    assertEquals(io.qdrant.client.grpc.Collections.Distance.Cosine, params.getDistance());
  }

  private static Class<?>[] parameterTypesOf(String spec) throws ClassNotFoundException {
    return switch (spec) {
      case "long.class" -> new Class<?>[] {long.class};
      case "float.class" -> new Class<?>[] {float.class};
      case "boolean.class" -> new Class<?>[] {boolean.class};
      default -> new Class<?>[] {Class.forName(spec)};
    };
  }

  private static Method privateStatic(String name, Class<?> parameterType)
      throws NoSuchMethodException {
    Method method = QdrantVectorStore.class.getDeclaredMethod(name, parameterType);
    method.setAccessible(true);
    return method;
  }
}
