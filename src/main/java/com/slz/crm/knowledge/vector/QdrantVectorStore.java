package com.slz.crm.knowledge.vector;

import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.CrmVectorStoreHealth;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 向量库实现；生产默认。
 */
public class QdrantVectorStore implements CrmVectorStore, CrmVectorStoreHealth {
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);

    private static final Class<?> SEARCH_POINTS_CLASS = classFor("io.qdrant.client.grpc.Points$SearchPoints");
    private static final Class<?> WITH_PAYLOAD_CLASS = classFor("io.qdrant.client.grpc.Points$WithPayloadSelector");
    private static final Class<?> FILTER_CLASS = classFor("io.qdrant.client.grpc.Points$Filter");
    private static final Class<?> CONDITION_CLASS = classFor("io.qdrant.client.grpc.Points$Condition");
    private static final Class<?> SCORED_POINT_CLASS = classFor("io.qdrant.client.grpc.Points$ScoredPoint");
    private static final Class<?> VECTOR_PARAMS_CLASS = classFor("io.qdrant.client.grpc.Collections$VectorParams");
    private static final Class<?> DISTANCE_CLASS = classFor("io.qdrant.client.grpc.Collections$Distance");
    private static final Class<?> POINT_STRUCT_CLASS = classFor("io.qdrant.client.grpc.Points$PointStruct");
    private static final Class<?> POINT_ID_CLASS = classFor("io.qdrant.client.grpc.Points$PointId");
    private static final Class<?> VECTORS_CLASS = classFor("io.qdrant.client.grpc.Points$Vectors");
    private static final Class<?> VALUE_CLASS = classFor("io.qdrant.client.grpc.JsonWithInt$Value");
    private static final Class<?> POINT_ID_FACTORY_CLASS = classFor("io.qdrant.client.PointIdFactory");
    private static final Class<?> VECTORS_FACTORY_CLASS = classFor("io.qdrant.client.VectorsFactory");
    private static final Class<?> VALUE_FACTORY_CLASS = classFor("io.qdrant.client.ValueFactory");

    private final QdrantProperties properties;
    private final QdrantClient client;
    private final Object collectionLock = new Object();
    private volatile boolean collectionChecked;

    /** 构造 gRPC 客户端；连接错误延迟到实际请求时暴露。 */
    public QdrantVectorStore(QdrantProperties properties) {
        this.properties = properties;
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                properties.getHost(), properties.getPort(), properties.isUseTls(), false);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.withApiKey(properties.getApiKey());
        }
        this.client = new QdrantClient(builder.withTimeout(RPC_TIMEOUT).build());
    }

    /** 启动检查集合，但失败不阻断应用，健康检查会继续暴露。 */
    @PostConstruct
    public void initialize() {
        if (!properties.isInitializeOnStartup()) {
            return;
        }
        try {
            ensureCollection();
        } catch (Exception exception) {
            log.warn("Qdrant 集合初始化失败，将在读写时重试: {}", exception.getMessage());
        }
    }

    @Override
    public void upsert(VectorRecord record) {
        upsertAll(List.of(record));
    }

    @Override
    public void upsertAll(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            ensureCollection();
            List<Object> points = records.stream().map(QdrantVectorStore::toPoint).toList();
            ((java.util.concurrent.Future<?>) QdrantClient.class
                    .getMethod("upsertAsync", String.class, List.class)
                    .invoke(client, properties.getCollection(), points))
                    .get(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant upsert 被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Qdrant upsert 失败", exception);
        }
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
        if (request.queryVector().length != properties.getDimensions()) {
            throw new IllegalArgumentException("查询向量维度 " + request.queryVector().length
                    + " 与集合维度 " + properties.getDimensions() + " 不一致");
        }
        try {
            ensureCollection();
            Object builder = SEARCH_POINTS_CLASS.getMethod("newBuilder").invoke(null);
            SEARCH_POINTS_CLASS.getMethod("setCollectionName", String.class).invoke(builder, properties.getCollection());
            SEARCH_POINTS_CLASS.getMethod("addAllVector", Iterable.class).invoke(builder, toFloatList(request.queryVector()));
            SEARCH_POINTS_CLASS.getMethod("setLimit", long.class).invoke(builder, (long) request.topK());
            Object payloadBuilder = WITH_PAYLOAD_CLASS.getMethod("newBuilder").invoke(null);
            WITH_PAYLOAD_CLASS.getMethod("setEnable", boolean.class).invoke(payloadBuilder, true);
            Object payloadSelector = WITH_PAYLOAD_CLASS.getMethod("build").invoke(payloadBuilder);
            SEARCH_POINTS_CLASS.getMethod("setWithPayload", WITH_PAYLOAD_CLASS).invoke(builder, payloadSelector);
            SEARCH_POINTS_CLASS.getMethod("setScoreThreshold", float.class).invoke(builder,
                    request.minScore() == null ? 0f : request.minScore().floatValue());
            Object filter = toFilter(request.filter());
            SEARCH_POINTS_CLASS.getMethod("setFilter", FILTER_CLASS).invoke(builder, filter);
            Object searchRequest = SEARCH_POINTS_CLASS.getMethod("build").invoke(builder);
            Object searchFuture = QdrantClient.class
                    .getMethod("searchAsync", SEARCH_POINTS_CLASS)
                    .invoke(client, searchRequest)
                    ;
            List<?> points = (List<?>) ((java.util.concurrent.Future<?>) searchFuture)
                    .get(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            List<VectorSearchHit> hits = new ArrayList<>(points.size());
            for (Object point : points) {
                @SuppressWarnings("unchecked")
                Map<?, ?> payload = (Map<?, ?>) SCORED_POINT_CLASS
                        .getMethod("getPayloadMap").invoke(point);
                Map<String, Object> metadata = toMetadata(payload);
                String text = (String) VALUE_CLASS
                        .getMethod("getStringValue")
                        .invoke(SCORED_POINT_CLASS
                        .getMethod("getPayloadOrDefault", String.class, VALUE_CLASS)
                        .invoke(point, "text", valueOf("")))
                        ;
                hits.add(new VectorSearchHit(
                        metadata.getOrDefault("chunkId", "").toString(),
                        metadata.getOrDefault("documentId", "").toString(),
                        (Double) SCORED_POINT_CLASS.getMethod("getScore").invoke(point),
                        text,
                        metadata));
            }
            return hits;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant search 被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Qdrant search 失败", exception);
        }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        try {
            ensureCollection();
            Object filter = documentFilter(documentId);
            ((java.util.concurrent.Future<?>) QdrantClient.class
                    .getMethod("deleteAsync", String.class, FILTER_CLASS)
                    .invoke(client, properties.getCollection(), filter))
                    .get(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant delete 被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Qdrant delete 失败", exception);
        }
    }

    @Override
    public String componentName() {
        return "vectorStoreQdrant";
    }

    @Override
    public boolean inMemoryFallback() {
        return false;
    }

    @Override
    public String collectionName() {
        return properties.getCollection();
    }

    @Override
    public boolean probe() {
        try {
            return Boolean.TRUE.equals(client.collectionExistsAsync(properties.getCollection())
                    .get(2, TimeUnit.SECONDS));
        } catch (Exception exception) {
            return false;
        }
    }

    /** 集合存在性缓存只减少启动检查；真实读写前仍会在同一锁内确认。 */
    private void ensureCollection() throws Exception {
        if (collectionChecked) {
            return;
        }
        synchronized (collectionLock) {
            if (collectionChecked) {
                return;
            }
            Boolean exists = client.collectionExistsAsync(properties.getCollection())
                    .get(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(exists)) {
                Object paramsBuilder = VECTOR_PARAMS_CLASS.getMethod("newBuilder").invoke(null);
                VECTOR_PARAMS_CLASS.getMethod("setSize", long.class)
                        .invoke(paramsBuilder, (long) properties.getDimensions());
                VECTOR_PARAMS_CLASS.getMethod("setDistance", DISTANCE_CLASS).invoke(paramsBuilder,
                        Enum.valueOf((Class<? extends Enum>) DISTANCE_CLASS.asSubclass(Enum.class), "Cosine"));
                Object params = VECTOR_PARAMS_CLASS.getMethod("build").invoke(paramsBuilder);
                ((java.util.concurrent.Future<?>) QdrantClient.class
                        .getMethod("createCollectionAsync", String.class, VECTOR_PARAMS_CLASS)
                        .invoke(client, properties.getCollection(), params))
                        .get(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
            collectionChecked = true;
        }
    }

    /** metadata 统一遵循 String 类目/ID、Long 0/1 布尔约定。 */
    private static Object toPoint(VectorRecord record) {
        try {
            Map<String, Object> payload = new HashMap<>();
            record.metadata().forEach((key, value) -> payload.put(key, valueOf(value)));
            payload.put("documentId", valueOf(record.documentId()));
            payload.put("chunkId", valueOf(record.chunkId()));
            payload.put("chunkIndex", valueOf(record.chunkIndex() == null ? 0L : record.chunkIndex().longValue()));
            payload.put("text", valueOf(record.text()));
            Object pointId = POINT_ID_FACTORY_CLASS.getMethod("id", UUID.class)
                    .invoke(null, UUID.fromString(record.id()));
            Object vectors = VECTORS_FACTORY_CLASS.getMethod("vectors", float[].class)
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

    private static Object valueOf(Object value) {
        if (value instanceof String stringValue) {
            return invokeStatic(VALUE_FACTORY_CLASS, "value", String.class, stringValue);
        }
        if (value instanceof Boolean boolValue) {
            return invokeStatic(VALUE_FACTORY_CLASS, "value", long.class, boolValue ? 1L : 0L);
        }
        if (value instanceof Double doubleValue) {
            return invokeStatic(VALUE_FACTORY_CLASS, "value", double.class, doubleValue);
        }
        if (value instanceof Number numberValue) {
            return invokeStatic(VALUE_FACTORY_CLASS, "value", long.class, numberValue.longValue());
        }
        return invokeStatic(VALUE_FACTORY_CLASS, "value", String.class, String.valueOf(value));
    }

    private static Object toFilter(Map<String, Object> filter) {
        try {
            Object builder = FILTER_CLASS.getMethod("newBuilder").invoke(null);
            filter.forEach((key, value) -> {
                try {
                    FILTER_CLASS.getMethod("addMust", CONDITION_CLASS)
                            .invoke(builder, toCondition(key, value));
                } catch (Exception exception) {
                    throw new IllegalStateException("构建 Qdrant filter 失败", exception);
                }
            });
            return FILTER_CLASS.getMethod("build").invoke(builder);
        } catch (Exception exception) {
            throw new IllegalStateException("构建 Qdrant filter 失败", exception);
        }
    }

    private static Object documentFilter(String documentId) {
        try {
            Object builder = FILTER_CLASS.getMethod("newBuilder").invoke(null);
            Object condition = ConditionFactory.matchKeyword("documentId", documentId);
            FILTER_CLASS.getMethod("addMust", CONDITION_CLASS).invoke(builder, condition);
            return FILTER_CLASS.getMethod("build").invoke(builder);
        } catch (Exception exception) {
            throw new IllegalStateException("构建 Qdrant document filter 失败", exception);
        }
    }

    private static Object toCondition(String key, Object value) {
        if (value instanceof String stringValue) {
            return ConditionFactory.matchKeyword(key, stringValue);
        }
        if (value instanceof Boolean boolValue) {
            return ConditionFactory.match(key, boolValue ? 1L : 0L);
        }
        if (value instanceof Number numberValue) {
            return ConditionFactory.match(key, numberValue.longValue());
        }
        return ConditionFactory.matchKeyword(key, String.valueOf(value));
    }

    private static Map<String, Object> toMetadata(Map<?, ?> payload) {
        Map<String, Object> metadata = new HashMap<>();
        payload.forEach((key, value) -> {
            try {
                if ((Boolean) VALUE_CLASS.getMethod("hasStringValue").invoke(value)) {
                    metadata.put(String.valueOf(key), VALUE_CLASS.getMethod("getStringValue").invoke(value));
                } else if ((Boolean) VALUE_CLASS.getMethod("hasIntegerValue").invoke(value)) {
                    metadata.put(String.valueOf(key), VALUE_CLASS.getMethod("getIntegerValue").invoke(value));
                } else if ((Boolean) VALUE_CLASS.getMethod("hasDoubleValue").invoke(value)) {
                    metadata.put(String.valueOf(key), VALUE_CLASS.getMethod("getDoubleValue").invoke(value));
                } else if ((Boolean) VALUE_CLASS.getMethod("hasBoolValue").invoke(value)) {
                    metadata.put(String.valueOf(key),
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

    private static Object invokeStatic(Class<?> type, String methodName, Class<?> parameterType, Object argument) {
        try {
            return type.getMethod(methodName, parameterType).invoke(null, argument);
        } catch (Exception exception) {
            throw new IllegalStateException("调用 Qdrant 工厂方法失败: " + methodName, exception);
        }
    }

    private static List<Float> toFloatList(float[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return result;
    }

    private static Class<?> classFor(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Qdrant gRPC 类缺失: " + className, exception);
        }
    }

    /** 应用关闭时释放 gRPC 连接。 */
    @PreDestroy
    public void close() {
        client.close();
    }
}
