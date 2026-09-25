package com.slz.crm.knowledge.vector;

import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 代表性热路径度量专用 Qdrant 读写 shim（add-representative-hotpath-measurement，仅测试侧，不改 src/main）。
 *
 * <p><b>为什么存在</b>：生产 {@link QdrantVectorStore#search} 把 gRPC {@code
 * ScoredPoint.getScore()}（float→Float 装箱） 强转 {@code Double}，对真实 Qdrant 的任何非空命中都抛 {@code
 * ClassCastException}（本度量首轮实测： {@code java.lang.Float cannot be cast to java.lang.Double}，重试 3
 * 次后失败）。该缺陷在旧合成基线（内存桩返回 Double 分数） 下不可见，属本案真实存储测量挖出的生产侧发现；修复 src/main 不在本案授权范围，需另案处理。
 *
 * <p><b>边界（哪些仍是生产原样）</b>：向量写入 {@code upsertAll} / 单条 {@code upsert} / 按文档删除全部委托生产 {@link
 * QdrantVectorStore}（含集合创建与重试）；search 的过滤条件经生产 {@link QdrantReflectionSupport#toFilter} 构建、 payload
 * 读回经生产 {@link QdrantReflectionSupport#toMetadata} 解析、向量经 {@link
 * QdrantReflectionSupport#toFloatList}； gRPC 客户端与生产同类同构（同 builder/超时）。<b>仅有的偏差</b>：分数装箱修正（float →
 * double）与命中映射循环在本类完成。 证据级别仍是「真实 Qdrant + 生产过滤条件」，文档与报告必须披露该 shim。
 */
public class RepresentativeHotpathQdrantSearchShim implements CrmVectorStore {

  private final QdrantVectorStore writeDelegate;
  private final QdrantProperties properties;
  private final QdrantClient client;

  public RepresentativeHotpathQdrantSearchShim(
      QdrantProperties properties, QdrantVectorStore writeDelegate) {
    this.properties = properties;
    this.writeDelegate = writeDelegate;
    Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(
            properties.getHost(), properties.getPort(), properties.isUseTls(), false);
    if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
      builder.withApiKey(properties.getApiKey());
    }
    this.client = new QdrantClient(builder.withTimeout(timeout).build());
  }

  @Override
  public void upsert(VectorRecord record) {
    writeDelegate.upsert(record);
  }

  @Override
  public void upsertAll(List<VectorRecord> records) {
    writeDelegate.upsertAll(records);
  }

  /**
   * 真实 Qdrant 向量检索：生产过滤构建器 + 生产 payload 读回，仅分数装箱修正。 形状与生产 {@link QdrantVectorStore#search}
   * 的意图一致（集合/limit/阈值/payload 语义）。
   */
  @Override
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
      SearchPoints.Builder searchBuilder =
          SearchPoints.newBuilder()
              .setCollectionName(properties.getCollection())
              .addAllVector(QdrantReflectionSupport.toFloatList(request.queryVector()))
              .setLimit(request.topK())
              .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
              .setScoreThreshold(request.minScore() == null ? 0f : request.minScore().floatValue());
      // 生产过滤器构建器原样使用（knowledgeBaseId 逐库 keyword 过滤，授权不能被 metadata 放大）
      searchBuilder.setFilter(
          (io.qdrant.client.grpc.Points.Filter) QdrantReflectionSupport.toFilter(request.filter()));
      List<ScoredPoint> points =
          client
              .searchAsync(searchBuilder.build())
              .get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
      List<VectorSearchHit> hits = new ArrayList<>(points.size());
      for (ScoredPoint point : points) {
        Map<String, Value> payload = point.getPayloadMap();
        Map<String, Object> metadata = QdrantReflectionSupport.toMetadata(payload);
        String text =
            point.getPayloadOrDefault("text", Value.getDefaultInstance()).getStringValue();
        hits.add(
            new VectorSearchHit(
                metadata.getOrDefault("chunkId", "").toString(),
                metadata.getOrDefault("documentId", "").toString(),
                (double) point.getScore(),
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
    writeDelegate.deleteByDocumentId(documentId);
  }

  /** 释放 shim 自身的 gRPC 连接（写入连接由生产 store 负责）。 */
  public void close() {
    client.close();
  }
}
