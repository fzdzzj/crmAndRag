package com.slz.crm.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.exception.NotFoundException;
import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 生产 {@link QdrantVectorStore#search} 非空命中端到端回归（fix-qdrant-search-score-conversion）。
 *
 * <p><b>为什么存在</b>：旧实现把反射返回的 {@code ScoredPoint.getScore()}（client 1.13.0 原始 float，装箱 Float）直接
 * {@code (Double)} 强转——真实 Qdrant 非空命中必抛 ClassCastException 且被重试 3 次包装上抛；内存桩与 mock 单测均不覆盖该路径。 本 IT
 * 在<b>一次性本地 Qdrant</b> 上经生产 upsert 写入、生产 search 读出（不经任何测试侧 shim），锁定： 非空命中分数为 float→double
 * 数值转换、chunkId/documentId/text/metadata 与 payload 对齐、空结果保持空列表、 KB 过滤/topK/阈值传递不变、命中顺序保留。
 *
 * <p><b>门禁</b>：Docker 或本地镜像（{@code qdrant/qdrant:v1.18.3}）缺失时 assumeTrue 跳过并留名——按规格不以假端口绿
 * 替代真实回归结论，不自动拉取镜像。零模型调用（向量直接写死），零外发。
 */
class QdrantVectorStoreSearchRealIT {

  /** 本地预存在、禁止自动拉取的镜像钉扎（与度量入口一致）。 */
  private static final String QDRANT_IMAGE = "qdrant/qdrant:v1.18.3";

  private static final int DIMS = 4;

  private static GenericContainer<?> qdrant;
  private static QdrantVectorStore store;

  @BeforeAll
  static void startOneOffQdrant() throws Exception {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：生产 search 非空回归未验证（端到端结论保持未测，不以内存桩替代）");
    assumeTrue(imagePresent(QDRANT_IMAGE), "本地镜像缺失 " + QDRANT_IMAGE + "：不自动拉取，生产 search 非空回归未验证");
    qdrant =
        new GenericContainer<>(DockerImageName.parse(QDRANT_IMAGE))
            .withExposedPorts(6333, 6334)
            .waitingFor(Wait.forListeningPort());
    qdrant.start();
    awaitQdrantReady(qdrant);

    QdrantProperties properties = new QdrantProperties();
    properties.setHost(qdrant.getHost());
    properties.setPort(qdrant.getMappedPort(6334));
    properties.setCollection("fix_score_it");
    properties.setDimensions(DIMS);
    properties.setTimeoutMs(10_000);
    properties.setInitializeOnStartup(true);
    store = new QdrantVectorStore(properties);
    store.initialize();
    // 一次性种子（@BeforeAll 单次）：upsertAll 以随机 UUID 为点位，重复种子会累积同值向量
    seedFixedVectors();
  }

  @AfterAll
  static void stopOneOffQdrant() {
    if (store != null) {
      store.close();
    }
    if (qdrant != null) {
      qdrant.stop();
    }
  }

  // ---------------------------------------------------------------- 非空命中（修复前的红灯路径）

  /** 非空命中：分数 float→double 转换、payload 对齐、KB 过滤、顺序保留——修复前本测试因 ClassCastException 变红。 */
  @Test
  void nonEmptyHitsMapScorePayloadFilterAndOrder() {
    List<VectorSearchHit> hits =
        store.search(
            new VectorSearchRequest(unitVectorX(), 3, null, Map.of("knowledgeBaseId", "7")));

    // minScore=null 时生产传 score_threshold=0f，Qdrant 服务端按严格大于排除 cos=0 的正交向量，
    // 故 KB7 的 3 条中返回 2 条（cos=1.0 与 0.75）——这是既有阈值语义，本案不改
    assertEquals(2, hits.size(), "KB7 非空命中应返回 cos>0 的两条");
    for (VectorSearchHit hit : hits) {
      assertTrue(hit.score() > 0.0d && hit.score() <= 1.0d, "分数应为契约 0~1 的 double：" + hit.score());
    }
    // 顺序保留：Qdrant 客户端按分数降序返回，映射不得打乱
    for (int i = 1; i < hits.size(); i++) {
      assertTrue(
          hits.get(i - 1).score() >= hits.get(i).score(),
          "命中顺序必须保留客户端降序：" + hits.get(i - 1).score() + " < " + hits.get(i).score());
    }
    // 已知夹角：v=[0.75, sqrt(1-0.5625), 0, 0] 与查询 [1,0,0,0] 的余弦 = 0.75（float32 精度内）
    VectorSearchHit known = hitWithText(hits, "kb7-doc-chunk-0");
    assertEquals(
        0.75d, known.score(), 1e-4, "反射 score 必须经数值转换得到 double 0.75，不得因装箱抛 ClassCastException");
    assertEquals("fix-score-it-doc-a", known.documentId(), "documentId 应与 payload 对齐");
    assertEquals("1001", known.chunkId(), "chunkId 应与 payload 对齐");
    assertEquals("kb7-doc-chunk-0", known.text(), "text 应与 payload 原文对齐");
    assertEquals(
        "7", String.valueOf(known.metadata().get("knowledgeBaseId")), "metadata 应携带 KB 过滤键");
    // KB8 的向量不得进入 KB7 视图
    assertTrue(
        hits.stream()
            .allMatch(hit -> "7".equals(String.valueOf(hit.metadata().get("knowledgeBaseId")))),
        "KB 过滤不得放大：命中必须全部属于授权 KB");
  }

  /** 空命中：过滤无匹配时返回空列表，不虚构分数、不异常。 */
  @Test
  void emptyResultStaysEmptyForNonMatchingFilter() {
    List<VectorSearchHit> none =
        store.search(
            new VectorSearchRequest(unitVectorX(), 3, null, Map.of("knowledgeBaseId", "404")));
    assertTrue(none.isEmpty(), "无匹配 KB 必须空列表");
  }

  /** topK 与阈值传递不变：minScore=0.5 只留 1.0/0.75 两条；topK=1 只留最高分。 */
  @Test
  void topKAndMinScorePassThroughUnchanged() {
    List<VectorSearchHit> thresholded =
        store.search(
            new VectorSearchRequest(unitVectorX(), 3, 0.5d, Map.of("knowledgeBaseId", "7")));
    assertEquals(2, thresholded.size(), "minScore=0.5 应过滤掉 cos=0 的命中");
    assertEquals(1.0d, thresholded.get(0).score(), 1e-4, "最高分命中应为完全同向向量");
    List<VectorSearchHit> top1 =
        store.search(
            new VectorSearchRequest(unitVectorX(), 1, null, Map.of("knowledgeBaseId", "7")));
    assertEquals(1, top1.size(), "topK=1 只返回一条");
    assertEquals(1.0d, top1.get(0).score(), 1e-4, "topK=1 应保留最高分");
  }

  // ---------------------------------------------------------------- 种子与工具

  /** 生产 upsert 写入固定向量：KB7 三条（同向/0.75 夹角/正交）+ KB8 一条（过滤对照）。 */
  private static void seedFixedVectors() {
    List<VectorRecord> records = new ArrayList<>();
    records.add(
        record(
            "fix-score-it-doc-a",
            "1001",
            0,
            "kb7-doc-chunk-0",
            "7",
            new float[] {0.75f, 0.6614378f, 0f, 0f}));
    records.add(
        record(
            "fix-score-it-doc-a", "1002", 1, "kb7-doc-chunk-1", "7", new float[] {0f, 1f, 0f, 0f}));
    records.add(
        record(
            "fix-score-it-doc-b", "1003", 0, "kb7-doc-chunk-2", "7", new float[] {1f, 0f, 0f, 0f}));
    records.add(
        record(
            "fix-score-it-doc-c", "1004", 0, "kb8-doc-chunk-0", "8", new float[] {1f, 0f, 0f, 0f}));
    store.upsertAll(records);
  }

  private static VectorRecord record(
      String documentId,
      String chunkId,
      int chunkIndex,
      String text,
      String kbId,
      float[] embedding) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", kbId);
    metadata.put("filename", documentId + ".txt");
    metadata.put("category", "contract");
    return new VectorRecord(
        UUID.randomUUID().toString(), documentId, chunkId, chunkIndex, text, embedding, metadata);
  }

  private static float[] unitVectorX() {
    return new float[] {1f, 0f, 0f, 0f};
  }

  private static VectorSearchHit hitWithText(List<VectorSearchHit> hits, String text) {
    return hits.stream()
        .filter(hit -> text.equals(hit.text()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到 text=" + text + " 的命中，实际=" + hits));
  }

  /** 只读镜像预检：缺失即跳过（不拉取）。 */
  private static boolean imagePresent(String image) {
    try {
      DockerClientFactory.instance().client().inspectImageCmd(image).exec();
      return true;
    } catch (NotFoundException exception) {
      return false;
    }
  }

  private static void awaitQdrantReady(GenericContainer<?> container) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String url = "http://" + container.getHost() + ":" + container.getMappedPort(6333) + "/readyz";
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    Exception last = null;
    while (System.nanoTime() < deadline) {
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return;
        }
      } catch (Exception exception) {
        last = exception;
      }
      Thread.sleep(300);
    }
    throw new IllegalStateException("Qdrant 容器 30s 内未就绪（readyz 不通过）", last);
  }
}
