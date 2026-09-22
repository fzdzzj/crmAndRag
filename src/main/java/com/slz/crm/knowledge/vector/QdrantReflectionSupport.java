package com.slz.crm.knowledge.vector;

import com.slz.crm.platform.contract.VectorRecord;
import io.qdrant.client.ConditionFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant gRPC protobuf 的反射装配支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * QdrantVectorStore，行为等价）。纯静态、无状态：PointStruct/Filter 构建与 payload 解析 均经反射落到 builder 实例的运行时类。
 */
final class QdrantReflectionSupport {

  private static final Class<?> FILTER_CLASS = classFor("io.qdrant.client.grpc.Points$Filter");
  private static final Class<?> CONDITION_CLASS =
      classFor("io.qdrant.client.grpc.Points$Condition");
  private static final Class<?> SCORED_POINT_CLASS =
      classFor("io.qdrant.client.grpc.Points$ScoredPoint");
  private static final Class<?> POINT_STRUCT_CLASS =
      classFor("io.qdrant.client.grpc.Points$PointStruct");
  private static final Class<?> POINT_ID_CLASS = classFor("io.qdrant.client.grpc.Points$PointId");
  private static final Class<?> VECTORS_CLASS = classFor("io.qdrant.client.grpc.Points$Vectors");
  private static final Class<?> VALUE_CLASS = classFor("io.qdrant.client.grpc.JsonWithInt$Value");
  private static final Class<?> POINT_ID_FACTORY_CLASS =
      classFor("io.qdrant.client.PointIdFactory");
  private static final Class<?> VECTORS_FACTORY_CLASS = classFor("io.qdrant.client.VectorsFactory");
  private static final Class<?> VALUE_FACTORY_CLASS = classFor("io.qdrant.client.ValueFactory");

  private QdrantReflectionSupport() {}

  static Class<?> classFor(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException exception) {
      throw new IllegalStateException("Qdrant gRPC 类缺失: " + className, exception);
    }
  }

  /** metadata 统一遵循 String 类目/ID、Long 0/1 布尔约定。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+工厂外呼多源，统一包装上抛
  static Object toPoint(VectorRecord record) {
    try {
      Map<String, Object> payload = new HashMap<>();
      record.metadata().forEach((key, value) -> payload.put(key, valueOf(value)));
      payload.put("documentId", valueOf(record.documentId()));
      payload.put("chunkId", valueOf(record.chunkId()));
      payload.put(
          "chunkIndex",
          valueOf(record.chunkIndex() == null ? 0L : record.chunkIndex().longValue()));
      payload.put("text", valueOf(record.text()));
      Object pointId =
          POINT_ID_FACTORY_CLASS
              .getMethod("id", UUID.class)
              .invoke(null, UUID.fromString(record.id()));
      Object vectors =
          VECTORS_FACTORY_CLASS
              .getMethod("vectors", float[].class)
              .invoke(null, (Object) record.embedding());
      Object builder = POINT_STRUCT_CLASS.getMethod("newBuilder").invoke(null);
      Class<?> builderClass = builder.getClass();
      builderClass.getMethod("setId", POINT_ID_CLASS).invoke(builder, pointId);
      builderClass.getMethod("setVectors", VECTORS_CLASS).invoke(builder, vectors);
      builderClass.getMethod("putAllPayload", Map.class).invoke(builder, payload);
      return builderClass.getMethod("build").invoke(builder);
    } catch (Exception exception) {
      throw new IllegalStateException("构建 Qdrant PointStruct 失败", exception);
    }
  }

  static Object valueOf(Object value) {
    Object result;
    if (value instanceof String stringValue) {
      result = invokeStatic(VALUE_FACTORY_CLASS, "value", String.class, stringValue);
    } else if (value instanceof Boolean boolValue) {
      result = invokeStatic(VALUE_FACTORY_CLASS, "value", long.class, boolValue ? 1L : 0L);
    } else if (value instanceof Double doubleValue) {
      result = invokeStatic(VALUE_FACTORY_CLASS, "value", double.class, doubleValue);
    } else if (value instanceof Number numberValue) {
      result = invokeStatic(VALUE_FACTORY_CLASS, "value", long.class, numberValue.longValue());
    } else {
      result = invokeStatic(VALUE_FACTORY_CLASS, "value", String.class, String.valueOf(value));
    }
    return result;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+toCondition多源，含lambda内catch，统一包装上抛
  static Object toFilter(Map<String, Object> filter) {
    try {
      Object builder = FILTER_CLASS.getMethod("newBuilder").invoke(null);
      Class<?> filterBuilderClass = builder.getClass();
      filter.forEach(
          (key, value) -> {
            try {
              filterBuilderClass
                  .getMethod("addMust", CONDITION_CLASS)
                  .invoke(builder, toCondition(key, value));
            } catch (Exception exception) {
              throw new IllegalStateException("构建 Qdrant filter 失败", exception);
            }
          });
      return filterBuilderClass.getMethod("build").invoke(builder);
    } catch (Exception exception) {
      throw new IllegalStateException("构建 Qdrant filter 失败", exception);
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+ConditionFactory外呼多源，统一包装上抛
  static Object documentFilter(String documentId) {
    try {
      Object builder = FILTER_CLASS.getMethod("newBuilder").invoke(null);
      Class<?> filterBuilderClass = builder.getClass();
      Object condition = ConditionFactory.matchKeyword("documentId", documentId);
      filterBuilderClass.getMethod("addMust", CONDITION_CLASS).invoke(builder, condition);
      return filterBuilderClass.getMethod("build").invoke(builder);
    } catch (Exception exception) {
      throw new IllegalStateException("构建 Qdrant document filter 失败", exception);
    }
  }

  private static Object toCondition(String key, Object value) {
    Object result;
    if (value instanceof String stringValue) {
      result = ConditionFactory.matchKeyword(key, stringValue);
    } else if (value instanceof Boolean boolValue) {
      result = ConditionFactory.match(key, boolValue ? 1L : 0L);
    } else if (value instanceof Number numberValue) {
      result = ConditionFactory.match(key, numberValue.longValue());
    } else {
      result = ConditionFactory.matchKeyword(key, String.valueOf(value));
    }
    return result;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射getter多源，含lambda内catch，统一包装上抛
  static Map<String, Object> toMetadata(Map<?, ?> payload) {
    Map<String, Object> metadata = new HashMap<>();
    payload.forEach(
        (key, value) -> {
          try {
            if ((Boolean) VALUE_CLASS.getMethod("hasStringValue").invoke(value)) {
              metadata.put(
                  String.valueOf(key), VALUE_CLASS.getMethod("getStringValue").invoke(value));
            } else if ((Boolean) VALUE_CLASS.getMethod("hasIntegerValue").invoke(value)) {
              metadata.put(
                  String.valueOf(key), VALUE_CLASS.getMethod("getIntegerValue").invoke(value));
            } else if ((Boolean) VALUE_CLASS.getMethod("hasDoubleValue").invoke(value)) {
              metadata.put(
                  String.valueOf(key), VALUE_CLASS.getMethod("getDoubleValue").invoke(value));
            } else if ((Boolean) VALUE_CLASS.getMethod("hasBoolValue").invoke(value)) {
              metadata.put(
                  String.valueOf(key),
                  (Boolean) VALUE_CLASS.getMethod("getBoolValue").invoke(value) ? 1L : 0L);
            } else {
              metadata.put(String.valueOf(key), String.valueOf(value));
            }
          } catch (Exception exception) {
            throw new IllegalStateException("解析 Qdrant payload 失败", exception);
          }
        });
    return metadata;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射getMethod/invoke多源，统一包装上抛
  private static Object invokeStatic(
      Class<?> type, String methodName, Class<?> parameterType, Object argument) {
    try {
      return type.getMethod(methodName, parameterType).invoke(null, argument);
    } catch (Exception exception) {
      throw new IllegalStateException("调用 Qdrant 工厂方法失败: " + methodName, exception);
    }
  }

  static List<Float> toFloatList(float[] values) {
    List<Float> result = new ArrayList<>(values.length);
    for (float value : values) {
      result.add(value);
    }
    return result;
  }
}
