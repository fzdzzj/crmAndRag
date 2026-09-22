package com.slz.crm.knowledge.vector;

import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.CrmVectorStoreHealth;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Qdrant 向量库实现；生产默认。 */
public class QdrantVectorStore implements CrmVectorStore, CrmVectorStoreHealth {
  private static final Logger LOG = LoggerFactory.getLogger(QdrantVectorStore.class);
  private static final int MAX_RETRIES = 3;
  private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
  private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(10);

  private static final Class<?> SEARCH_POINTS_CLASS =
      classFor("io.qdrant.client.grpc.Points$SearchPoints");
  private static final Class<?> WITH_PAYLOAD_CLASS =
      classFor("io.qdrant.client.grpc.Points$WithPayloadSelector");
  private static final Class<?> VECTOR_PARAMS_CLASS =
      classFor("io.qdrant.client.grpc.Collections$VectorParams");
  private static final Class<?> DISTANCE_CLASS =
      classFor("io.qdrant.client.grpc.Collections$Distance");
  private static final Class<?> FILTER_CLASS = classFor("io.qdrant.client.grpc.Points$Filter");
  private static final Class<?> SCORED_POINT_CLASS =
      classFor("io.qdrant.client.grpc.Points$ScoredPoint");
  private static final Class<?> VALUE_CLASS = classFor("io.qdrant.client.grpc.JsonWithInt$Value");

  private final QdrantProperties properties;
  private final QdrantClient client;
  private final Object collectionLock = new Object();
  private volatile boolean collectionChecked;

  /** 构造 gRPC 客户端；连接错误延迟到实际请求时暴露。 */
  public QdrantVectorStore(QdrantProperties properties) {
    this.properties = properties;
    Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(
            properties.getHost(), properties.getPort(), properties.isUseTls(), false);
    if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
      builder.withApiKey(properties.getApiKey());
    }
    this.client = new QdrantClient(builder.withTimeout(timeout).build());
  }

  /** 启动检查集合，但失败不阻断应用，健康检查会继续暴露。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 启动兜底：集合检查为反射+外呼SDK多源抛出，失败只告警不阻断启动
  @PostConstruct
  public void initialize() {
    if (!properties.isInitializeOnStartup()) {
      return;
    }
    try {
      ensureCollection();
    } catch (Exception exception) {
      LOG.warn("Qdrant 集合初始化失败，将在读写时重试: {}", exception.getMessage());
    }
  }

  @Override
  public void upsert(VectorRecord record) {
    upsertAll(List.of(record));
  }

  @Override
  @SuppressWarnings(
      "PMD.AvoidCatchingGenericException") // 反射+Qdrant外呼混抛（NoSuchMethod/InvocationTarget/超时等），统一包装上抛
  public void upsertAll(List<VectorRecord> records) {
    if (records == null || records.isEmpty()) {
      return;
    }
    try {
      ensureCollection();
      executeWithRetry(
          () -> {
            List<Object> points = records.stream().map(QdrantVectorStore::toPoint).toList();
            ((java.util.concurrent.Future<?>)
                    QdrantClient.class
                        .getMethod("upsertAsync", String.class, List.class)
                        .invoke(client, properties.getCollection(), points))
                .get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            return null;
          },
          "Qdrant upsert");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Qdrant upsert 被中断", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Qdrant upsert 失败", exception);
    }
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+Qdrant外呼混抛，统一包装上抛
  public List<VectorSearchHit> search(VectorSearchRequest request) {
    if (request.queryVector().length != properties.getDimensions()) {
      throw new IllegalArgumentException(
          "查询向量维度 "
              + request.queryVector().length
              + " 与集合维度 "
              + properties.getDimensions()
              + " 不一致");
    }
    try {
      ensureCollection();
      return executeWithRetry(
          () -> {
            Object builder = SEARCH_POINTS_CLASS.getMethod("newBuilder").invoke(null);
            Class<?> searchBuilderClass = builder.getClass();
            searchBuilderClass
                .getMethod("setCollectionName", String.class)
                .invoke(builder, properties.getCollection());
            searchBuilderClass
                .getMethod("addAllVector", Iterable.class)
                .invoke(builder, QdrantReflectionSupport.toFloatList(request.queryVector()));
            searchBuilderClass
                .getMethod("setLimit", long.class)
                .invoke(builder, (long) request.topK());
            Object payloadBuilder = WITH_PAYLOAD_CLASS.getMethod("newBuilder").invoke(null);
            Class<?> payloadBuilderClass = payloadBuilder.getClass();
            payloadBuilderClass.getMethod("setEnable", boolean.class).invoke(payloadBuilder, true);
            Object payloadSelector = payloadBuilderClass.getMethod("build").invoke(payloadBuilder);
            searchBuilderClass
                .getMethod("setWithPayload", WITH_PAYLOAD_CLASS)
                .invoke(builder, payloadSelector);
            searchBuilderClass
                .getMethod("setScoreThreshold", float.class)
                .invoke(builder, request.minScore() == null ? 0f : request.minScore().floatValue());
            Object filter = toFilter(request.filter());
            searchBuilderClass.getMethod("setFilter", FILTER_CLASS).invoke(builder, filter);
            Object searchRequest = searchBuilderClass.getMethod("build").invoke(builder);
            Object searchFuture =
                QdrantClient.class
                    .getMethod("searchAsync", SEARCH_POINTS_CLASS)
                    .invoke(client, searchRequest);
            List<?> points =
                (List<?>)
                    ((java.util.concurrent.Future<?>) searchFuture)
                        .get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            List<VectorSearchHit> hits = new ArrayList<>(points.size());
            for (Object point : points) {
              @SuppressWarnings("unchecked")
              Map<?, ?> payload =
                  (Map<?, ?>) SCORED_POINT_CLASS.getMethod("getPayloadMap").invoke(point);
              Map<String, Object> metadata = QdrantReflectionSupport.toMetadata(payload);
              String text =
                  (String)
                      VALUE_CLASS
                          .getMethod("getStringValue")
                          .invoke(
                              SCORED_POINT_CLASS
                                  .getMethod("getPayloadOrDefault", String.class, VALUE_CLASS)
                                  .invoke(point, "text", QdrantReflectionSupport.valueOf("")));
              hits.add(
                  new VectorSearchHit(
                      metadata.getOrDefault("chunkId", "").toString(),
                      metadata.getOrDefault("documentId", "").toString(),
                      (Double) SCORED_POINT_CLASS.getMethod("getScore").invoke(point),
                      text,
                      metadata));
            }
            return hits;
          },
          "Qdrant search");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Qdrant search 被中断", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Qdrant search 失败", exception);
    }
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+Qdrant外呼混抛，统一包装上抛
  public void deleteByDocumentId(String documentId) {
    try {
      ensureCollection();
      executeWithRetry(
          () -> {
            Object filter = QdrantReflectionSupport.documentFilter(documentId);
            ((java.util.concurrent.Future<?>)
                    QdrantClient.class
                        .getMethod("deleteAsync", String.class, FILTER_CLASS)
                        .invoke(client, properties.getCollection(), filter))
                .get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            return null;
          },
          "Qdrant delete");
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
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 健康探针兜底边界：外呼失败按不可用处理，不抛
  public boolean probe() {
    boolean result = false;
    try {
      result =
          Boolean.TRUE.equals(
              client.collectionExistsAsync(properties.getCollection()).get(2, TimeUnit.SECONDS));
    } catch (Exception exception) {
      result = false;
    }
    return result;
  }

  /** 集合存在性缓存只减少启动检查；真实读写前仍会在同一锁内确认。 */
  private void ensureCollection() throws Exception {
    if (!collectionChecked) {
      synchronized (collectionLock) {
        if (!collectionChecked) {
          executeWithRetry(
              () -> {
                Boolean exists =
                    client
                        .collectionExistsAsync(properties.getCollection())
                        .get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
                if (!Boolean.TRUE.equals(exists)) {
                  // protobuf 消息类只暴露 getter 与静态 newBuilder，setter/build 全在 Builder 上，
                  // 反射查找必须落在 builder 实例的运行时类（与 toPoint 同法）
                  Object paramsBuilder = VECTOR_PARAMS_CLASS.getMethod("newBuilder").invoke(null);
                  Class<?> paramsBuilderClass = paramsBuilder.getClass();
                  paramsBuilderClass
                      .getMethod("setSize", long.class)
                      .invoke(paramsBuilder, (long) properties.getDimensions());
                  paramsBuilderClass
                      .getMethod("setDistance", DISTANCE_CLASS)
                      .invoke(
                          paramsBuilder,
                          Enum.valueOf(
                              (Class<? extends Enum>) DISTANCE_CLASS.asSubclass(Enum.class),
                              "Cosine"));
                  Object params = paramsBuilderClass.getMethod("build").invoke(paramsBuilder);
                  ((java.util.concurrent.Future<?>)
                          QdrantClient.class
                              .getMethod("createCollectionAsync", String.class, VECTOR_PARAMS_CLASS)
                              .invoke(client, properties.getCollection(), params))
                      .get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
                }
                collectionChecked = true;
                return null;
              },
              "Qdrant collection check/create");
        }
      }
    }
  }

  // ==== 反射装配委托（保留同签名入口，QdrantVectorStoreReflectionTest 经反射锁定） ====

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+工厂外呼多源，统一包装上抛
  private static Object toPoint(VectorRecord record) {
    return QdrantReflectionSupport.toPoint(record);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+toCondition多源，统一包装上抛
  private static Object toFilter(Map<String, Object> filter) {
    return QdrantReflectionSupport.toFilter(filter);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射+ConditionFactory外呼多源，统一包装上抛
  private static Object documentFilter(String documentId) {
    return QdrantReflectionSupport.documentFilter(documentId);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射getter多源，统一包装上抛
  private static Map<String, Object> toMetadata(Map<?, ?> payload) {
    return QdrantReflectionSupport.toMetadata(payload);
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

  /** 指数退避重试执行器。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 重试兜底边界：捕获action任意异常以指数退避继续尝试
  private <T> T executeWithRetry(Retryable<T> action, String operation) throws Exception {
    Exception lastException = null;
    Duration delay = INITIAL_RETRY_DELAY;

    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        return action.execute();
      } catch (Exception exception) {
        lastException = exception;
        LOG.warn(
            "{} 失败 (尝试 {}/{})，等待 {} 秒后重试：{}",
            operation,
            attempt,
            MAX_RETRIES,
            delay.getSeconds(),
            exception.getMessage());

        if (attempt < MAX_RETRIES) {
          TimeUnit.MILLISECONDS.sleep(delay.toMillis());
          delay =
              Duration.ofSeconds(Math.min(delay.getSeconds() * 2, MAX_RETRY_DELAY.getSeconds()));
        }
      }
    }

    throw new IllegalStateException(operation + " 失败，已重试 " + MAX_RETRIES + " 次", lastException);
  }

  @FunctionalInterface
  private interface Retryable<T> {
    T execute() throws Exception;
  }
}
