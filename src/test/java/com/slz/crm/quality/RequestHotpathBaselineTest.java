package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentIngestionCommand;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.knowledge.retrieval.SparseChunkRow;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * 请求级热路径基线（add-request-hotpath-baseline）：同一负载跑零延迟与注入延迟两遍，只度量、不改生产行为。
 *
 * <p><b>负载（写死，两遍一致）</b>：检索 = 4 个授权库 × 1 条不拆分查询 × topK 5；摄取 = 24 个非空子块、 0 个父块（fixed 320/40 策略下 6600
 * 字符恰好 24 片）。全部走生产实现（{@link KnowledgeRetrievalServiceImpl} / {@link DocumentIngestionService} /
 * {@link ContextBuilder} / {@link SparseRecallService}）， 假端口只替换外呼边界：假 {@link ModelProvider}（嵌入 +
 * 查询改写桩）、内存向量库加计数装饰、 内存 {@link DocumentVectorChunkMapper} double（计数 + 延迟）。不连 MySQL/Qdrant，不调真实模型。
 *
 * <p><b>延迟注入（非生产测量）</b>：第二遍只给嵌入、向量检索、SQL 三类写死延迟；查询改写 LLM 桩即时返回
 * （规格的调用形状只含嵌入/向量/SQL，改写不计入远程桶，文档须注明）。零延迟遍的墙钟仅是<b>测试侧本地路径时间</b> （含测试脚手架与 JaCoCo 开销）：既不是纯
 * CPU，也不是生产业务规则时间。
 *
 * <p><b>口径</b>：调用次数逐请求断言（嵌入、向量检索、邻居查询、摄取嵌入、upsert）；耗时只打印到 stdout（{@code HOTPATH ...} 行）抄入
 * docs/request-hotpath-baseline.md，不做 CI 门禁。 锁/GC/JFR 未观测——不采集、不断言、不作为后续改动对象。
 */
class RequestHotpathBaselineTest {

  // ---- 固定负载（tasks.json 阶段 1：两遍运行同一输入，不改生产默认值） ----

  /** 检索负载：授权库数。 */
  private static final List<Long> KB_IDS = List.of(1L, 2L, 3L, 4L);

  /** 检索负载：topK（与 RetrievalDefaults.TOP_K 一致，显式传入）。 */
  private static final int TOP_K = 5;

  /** 检索负载：不拆分查询——不含「并且|同时|后|且」分隔符，ConstraintQuerySplitter 恒返回单路。 */
  private static final String RETRIEVAL_QUERY = "客户合同续签流程";

  /** 摄取负载：非空子块数（fixed 320/40 下 6600 字符 = ceil(6600/280) = 24 片）。 */
  private static final int INGEST_CHUNK_TARGET = 24;

  /** 摄取负载文本长度：落在 (280×23, 280×24] 内，保证恰好 24 个子块。 */
  private static final int INGEST_TEXT_CHARS = 6600;

  // ---- 注入延迟（写死；合成值，不是生产测量） ----

  private static final long EMBED_DELAY_MS = 30;
  private static final long VECTOR_SEARCH_DELAY_MS = 20;
  private static final long SQL_DELAY_MS = 5;

  // ---- 迭代次数（预热 + 计量） ----

  private static final int RETRIEVAL_ZERO_WARMUP = 30;
  private static final int RETRIEVAL_ZERO_ITERS = 200;
  private static final int RETRIEVAL_INJECTED_WARMUP = 3;
  private static final int RETRIEVAL_INJECTED_ITERS = 30;
  private static final int INGEST_ZERO_WARMUP = 3;
  private static final int INGEST_ZERO_ITERS = 30;
  private static final int INGEST_INJECTED_WARMUP = 2;
  private static final int INGEST_INJECTED_ITERS = 10;

  /** 合成占比比较：仅当最高类比第二名高 ≥10 个百分点才记「单独领先」条件标签，否则记并列（tie）。 该比较只在预设注入延迟与固定负载下成立，不指定下一张生产改动提案的对象。 */
  private static final double GATE_MARGIN_POINTS = 10.0d;

  private static final int EMBED_CALLS = 0;
  private static final int EMBED_TEXTS = 1;
  private static final int CHAT_CALLS = 2;
  private static final int SEARCH_CALLS = 3;
  private static final int UPSERT_ALL_CALLS = 4;
  private static final int FULLTEXT_CALLS = 5;
  private static final int SELECT_BY_ID_CALLS = 6;
  private static final int SELECT_LIST_CALLS = 7;
  private static final int INSERT_CHILD_CALLS = 8;
  private static final int INSERT_PARENT_CALLS = 9;
  private static final int SEARCHED_KB_COUNT = 10;
  private static final int SNAPSHOT_LEN = 11;
  private static final int SELECT_BATCH_CALLS = 11;

  // ---------------------------------------------------------------- 检索基线

  /** 检索热路径：固定负载两遍测量 + 每请求调用形状断言 + 合成占比比较（条件结论）。 */
  @Test
  void retrievalHotpathCountsAndLatency() {
    Counters zeroCounters = new Counters();
    HotpathBundle zeroBundle = assembly(zeroCounters, 0L, 0L, 0L);
    ingestCorpus(zeroBundle, zeroCounters);
    UserContext user = baselineUser();

    runRetrievalWarmup(zeroBundle, user, RETRIEVAL_ZERO_WARMUP);
    long[] zeroNanos = new long[RETRIEVAL_ZERO_ITERS];
    for (int index = 0; index < RETRIEVAL_ZERO_ITERS; index++) {
      long[] before = zeroCounters.snapshot();
      long start = System.nanoTime();
      KnowledgeRetrievalPort.RetrievalResult result = retrieveOnce(zeroBundle, user);
      zeroNanos[index] = System.nanoTime() - start;
      assertRetrievalShape(zeroCounters, before, result, "零延迟遍");
    }
    double zeroP50 = percentileMillis(zeroNanos, 0.50d);
    double zeroP95 = percentileMillis(zeroNanos, 0.95d);
    printf(
        "HOTPATH retrieval zero-latency: iters=%d warmup=%d p50_ms=%.3f p95_ms=%.3f%n",
        RETRIEVAL_ZERO_ITERS, RETRIEVAL_ZERO_WARMUP, zeroP50, zeroP95);

    Counters injectedCounters = new Counters();
    HotpathBundle injectedBundle =
        assembly(injectedCounters, EMBED_DELAY_MS, VECTOR_SEARCH_DELAY_MS, SQL_DELAY_MS);
    ingestCorpus(injectedBundle, injectedCounters);

    runRetrievalWarmup(injectedBundle, user, RETRIEVAL_INJECTED_WARMUP);
    long[] injectedNanos = new long[RETRIEVAL_INJECTED_ITERS];
    injectedCounters.resetBuckets();
    for (int index = 0; index < RETRIEVAL_INJECTED_ITERS; index++) {
      long[] before = injectedCounters.snapshot();
      long start = System.nanoTime();
      KnowledgeRetrievalPort.RetrievalResult result = retrieveOnce(injectedBundle, user);
      injectedNanos[index] = System.nanoTime() - start;
      assertRetrievalShape(injectedCounters, before, result, "注入延迟遍");
    }
    double injectedP50 = percentileMillis(injectedNanos, 0.50d);
    double injectedP95 = percentileMillis(injectedNanos, 0.95d);
    double remoteMs =
        millis(injectedCounters.embedNanos.get() + injectedCounters.searchNanos.get())
            / RETRIEVAL_INJECTED_ITERS;
    double databaseMs = millis(injectedCounters.sqlNanos.get()) / RETRIEVAL_INJECTED_ITERS;
    printf(
        "HOTPATH retrieval injected: embed_ms_per_call=%d vector_ms_per_call=%d sql_ms_per_call=%d"
            + " iters=%d p50_ms=%.3f p95_ms=%.3f%n",
        EMBED_DELAY_MS,
        VECTOR_SEARCH_DELAY_MS,
        SQL_DELAY_MS,
        RETRIEVAL_INJECTED_ITERS,
        injectedP50,
        injectedP95);

    // 合成占比比较：远程（嵌入+向量检索） vs 数据库（SQL） vs 零延迟本地（测试侧本地路径墙钟），
    // 结果是注入假设下的条件结论，不是生产瓶颈证据
    double localMs = zeroP50;
    double total = remoteMs + databaseMs + localMs;
    double remoteShare = 100.0d * remoteMs / total;
    double databaseShare = 100.0d * databaseMs / total;
    double localShare = 100.0d * localMs / total;
    printf(
        "HOTPATH retrieval buckets: remote_ms=%.3f database_ms=%.3f local_zero_latency_ms=%.3f"
            + " shares remote=%.1f%% database=%.1f%% local=%.1f%%%n",
        remoteMs, databaseMs, localMs, remoteShare, databaseShare, localShare);

    String leader = gateVerdict(remoteShare, databaseShare, localShare);
    printf(
        "HOTPATH retrieval compare: leader=%s margin_points=%.1f verdict=%s%n",
        leader,
        Math.abs(topTwoDiff(remoteShare, databaseShare, localShare)),
        "tie".equals(leader)
            ? "tie-no-single-leader"
            : "conditional-leader-" + leader + "-under-injected-latency-only");
  }

  // ---------------------------------------------------------------- 摄取基线

  /** 摄取热路径：固定负载两遍测量 + 每文档调用形状断言（嵌入 24 次、upsert 1 次）。 */
  @Test
  void ingestionHotpathCountsAndLatency() {
    Counters zeroCounters = new Counters();
    HotpathBundle zeroBundle = assembly(zeroCounters, 0L, 0L, 0L);
    UserContext user = baselineUser();

    runIngestWarmup(zeroBundle, user, INGEST_ZERO_WARMUP);
    long[] zeroNanos = new long[INGEST_ZERO_ITERS];
    for (int index = 0; index < INGEST_ZERO_ITERS; index++) {
      long[] before = zeroCounters.snapshot();
      long start = System.nanoTime();
      DocumentIngestionResult result = ingestOnce(zeroBundle, user);
      zeroNanos[index] = System.nanoTime() - start;
      assertIngestionShape(zeroCounters, before, result, "零延迟遍");
    }
    printf(
        "HOTPATH ingestion zero-latency: iters=%d warmup=%d p50_ms=%.3f p95_ms=%.3f%n",
        INGEST_ZERO_ITERS,
        INGEST_ZERO_WARMUP,
        percentileMillis(zeroNanos, 0.50d),
        percentileMillis(zeroNanos, 0.95d));

    Counters injectedCounters = new Counters();
    HotpathBundle injectedBundle =
        assembly(injectedCounters, EMBED_DELAY_MS, VECTOR_SEARCH_DELAY_MS, SQL_DELAY_MS);
    runIngestWarmup(injectedBundle, user, INGEST_INJECTED_WARMUP);
    long[] injectedNanos = new long[INGEST_INJECTED_ITERS];
    injectedCounters.resetBuckets();
    for (int index = 0; index < INGEST_INJECTED_ITERS; index++) {
      long[] before = injectedCounters.snapshot();
      long start = System.nanoTime();
      DocumentIngestionResult result = ingestOnce(injectedBundle, user);
      injectedNanos[index] = System.nanoTime() - start;
      assertIngestionShape(injectedCounters, before, result, "注入延迟遍");
    }
    double injectedP50 = percentileMillis(injectedNanos, 0.50d);
    double injectedP95 = percentileMillis(injectedNanos, 0.95d);
    double embedBucketMs = millis(injectedCounters.embedNanos.get()) / INGEST_INJECTED_ITERS;
    printf(
        "HOTPATH ingestion injected: embed_ms_per_call=%d iters=%d p50_ms=%.3f p95_ms=%.3f"
            + " embed_bucket_ms=%.3f upsert_bucket_ms=0.000%n",
        EMBED_DELAY_MS, INGEST_INJECTED_ITERS, injectedP50, injectedP95, embedBucketMs);
  }

  // ---------------------------------------------------------------- 负载与断言

  /** 检索语料：4 个授权库各 1 份文档，经生产摄取路径落库（计数随后清零，不计入检索测量）。 */
  private static void ingestCorpus(HotpathBundle bundle, Counters counters) {
    for (Long kbId : KB_IDS) {
      bundle.ingestion().ingest(ingestCommand(kbId, corpusText(kbId), baselineUser()));
    }
    counters.reset();
  }

  /** 检索语料文本：重复合同续签句到 ~3000 字符（fixed 策略约 11 片），保证与查询字符高重叠。 */
  private static String corpusText(long kbId) {
    String sentence = "客户合同续签流程：客户经理需在到期前确认客户合同条款并归档记录" + kbId + "。";
    StringBuilder text = new StringBuilder();
    while (text.length() < 3000) {
      text.append(sentence);
    }
    return text.toString();
  }

  /** 摄取负载文本：10 字符句重复到 6600 字符 → fixed 320/40 恰好 24 个非空子块、0 个父块。 */
  private static String ingestText() {
    StringBuilder text = new StringBuilder();
    while (text.length() < INGEST_TEXT_CHARS) {
      text.append("客户合同续签流程要点");
    }
    return text.toString();
  }

  private static KnowledgeRetrievalPort.RetrievalResult retrieveOnce(
      HotpathBundle bundle, UserContext user) {
    return UserContextHolder.callWith(
        user,
        () ->
            bundle
                .retrieval()
                .retrieve(
                    new KnowledgeRetrievalPort.RetrievalQuery(
                        RETRIEVAL_QUERY, user.userId(), List.of(), TOP_K, null, null)));
  }

  private static DocumentIngestionResult ingestOnce(HotpathBundle bundle, UserContext user) {
    return bundle.ingestion().ingest(ingestCommand(KB_IDS.getFirst(), ingestText(), user));
  }

  private static DocumentIngestionCommand ingestCommand(long kbId, String text, UserContext user) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return new DocumentIngestionCommand(
        kbId,
        user,
        "hotpath-baseline-" + kbId + ".txt",
        "text/plain",
        (long) bytes.length,
        "合同",
        null,
        new ByteArrayInputStream(bytes));
  }

  private static UserContext baselineUser() {
    return new UserContext(10001L, 1L, 11L, DataScopeLevel.NONE, "热路径基线用户");
  }

  /** 每请求调用形状断言：次数被断言而不是只打印（spec-delta「先记录次数再解释时间」）。 */
  private static void assertRetrievalShape(
      Counters counters,
      long[] before,
      KnowledgeRetrievalPort.RetrievalResult result,
      String pass) {
    assertEquals(TOP_K, result.hitCount(), pass + "：命中数应为 topK");
    assertDelta(counters, before, EMBED_CALLS, 1L, pass + "：每请求嵌入 1 次（单路不拆分）");
    assertDelta(counters, before, EMBED_TEXTS, 1L, pass + "：嵌入文本 1 条");
    assertDelta(counters, before, CHAT_CALLS, 1L, pass + "：查询改写 LLM 桩 1 次（即时返回，不计桶）");
    assertDelta(counters, before, SEARCH_CALLS, KB_IDS.size(), pass + "：向量检索 = 授权库数");
    assertDelta(counters, before, FULLTEXT_CALLS, 1L, pass + "：稀疏检索 1 次");
    assertDelta(counters, before, SELECT_BATCH_CALLS, 1L, pass + "：子块快照批查 1 次（五命中收敛）");
    assertDelta(counters, before, SELECT_BY_ID_CALLS, 0L, pass + "：检索不再逐命中 selectById");
    assertDelta(counters, before, SELECT_LIST_CALLS, 1L, pass + "：邻居快照批查 1 次（五命中收敛）");
    assertDelta(counters, before, UPSERT_ALL_CALLS, 0L, pass + "：检索不写向量库");
    long searched = counters.snapshot()[SEARCHED_KB_COUNT] - before[SEARCHED_KB_COUNT];
    assertEquals(KB_IDS.size(), searched, pass + "：过滤条件里的库 id 个数");
    List<String> kbFilterIds =
        counters.searchedKbIds.subList(
            counters.searchedKbIds.size() - KB_IDS.size(), counters.searchedKbIds.size());
    List<String> expected = KB_IDS.stream().map(String::valueOf).toList();
    assertEquals(expected, kbFilterIds, pass + "：逐库过滤保留授权库 id");
  }

  /** 每文档调用形状断言：摄取嵌入次数 = 子块数，且只有一次 upsert。 */
  private static void assertIngestionShape(
      Counters counters, long[] before, DocumentIngestionResult result, String pass) {
    assertEquals(INGEST_CHUNK_TARGET, result.chunkCount(), pass + "：子块数 = 固定负载");
    assertDelta(counters, before, EMBED_CALLS, INGEST_CHUNK_TARGET, pass + "：每子块 1 次嵌入");
    assertDelta(counters, before, EMBED_TEXTS, INGEST_CHUNK_TARGET, pass + "：嵌入文本 = 子块数");
    assertDelta(counters, before, INSERT_CHILD_CALLS, INGEST_CHUNK_TARGET, pass + "：子块行 = 24");
    assertDelta(counters, before, INSERT_PARENT_CALLS, 0L, pass + "：父块行 = 0（fixed 无父块）");
    assertDelta(counters, before, UPSERT_ALL_CALLS, 1L, pass + "：整文档一次 upsert");
    assertDelta(counters, before, SEARCH_CALLS, 0L, pass + "：摄取不检索");
  }

  private static void runRetrievalWarmup(HotpathBundle bundle, UserContext user, int iterations) {
    for (int index = 0; index < iterations; index++) {
      retrieveOnce(bundle, user);
    }
  }

  private static void runIngestWarmup(HotpathBundle bundle, UserContext user, int iterations) {
    for (int index = 0; index < iterations; index++) {
      ingestOnce(bundle, user);
    }
  }

  // ---------------------------------------------------------------- 装配

  /** 生产服务 + 计数假端口装配；延迟参数两遍分别传 0 与写死注入值，其余装配完全一致。 */
  private static HotpathBundle assembly(
      Counters counters, long embedDelayMs, long searchDelayMs, long sqlDelayMs) {
    BaselineModelProvider provider = new BaselineModelProvider(counters, embedDelayMs);
    EmbeddingService embedding = new EmbeddingService(provider, new ModelProviderProperties());
    CountingVectorStore vectorStore = new CountingVectorStore(counters, searchDelayMs);
    DocumentVectorChunkMapper chunkMapper = CountingChunkMapper.create(counters, sqlDelayMs);
    FixedKbAuthorizationService authorization = new FixedKbAuthorizationService(KB_IDS);
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(Map.of()));
    RetrievalQueryRewriteService rewrite =
        new RetrievalQueryRewriteService(provider, configProvider);
    SparseRecallService sparse = new SparseRecallService(chunkMapper);
    DefaultWeightedReranker reranker =
        new DefaultWeightedReranker(new Bm25Scorer(), configProvider);
    ContextBuilder contextBuilder =
        new ContextBuilder(chunkMapper, configProvider, new RuleContextCompressor(), null);
    KnowledgeRetrievalPort retrieval =
        new KnowledgeRetrievalServiceImpl(
            authorization,
            embedding,
            vectorStore,
            rewrite,
            configProvider,
            sparse,
            new RrfFusion(),
            reranker,
            null,
            contextBuilder);
    KnowledgeBaseMapper knowledgeBaseMapper = KnowledgeBaseMapperDouble.create(KB_IDS);
    UploadedFileMapper uploadedFileMapper = UploadedFileMapperDouble.create();
    DocumentIngestionService ingestion =
        new DocumentIngestionService(
            knowledgeBaseMapper,
            uploadedFileMapper,
            chunkMapper,
            authorization,
            new InMemoryFileStorageService(),
            new DocumentService(),
            embedding,
            vectorStore,
            new GovernanceAuditRecorder(null, null));
    return new HotpathBundle(retrieval, ingestion, vectorStore);
  }

  /** 装配结果引用集。 */
  private record HotpathBundle(
      KnowledgeRetrievalPort retrieval,
      DocumentIngestionService ingestion,
      CountingVectorStore vectorStore) {}

  /** 授权桩：检索返回固定 4 库，摄取恒可写（授权 SQL 不在规格调用形状内，不计桶）。 */
  private static final class FixedKbAuthorizationService extends KnowledgeBaseAuthorizationService {
    private final List<Long> knowledgeBaseIds;

    FixedKbAuthorizationService(List<Long> knowledgeBaseIds) {
      super(null, null);
      this.knowledgeBaseIds = knowledgeBaseIds;
    }

    @Override
    public List<Long> authorizedKnowledgeBaseIds(UserContext user, List<String> requestedScopes) {
      return knowledgeBaseIds;
    }

    @Override
    public boolean canWrite(KnowledgeBaseEntity knowledgeBase, UserContext user) {
      return true;
    }
  }

  /** 假模型：嵌入返回字符分桶确定性向量并计数/计时；改写桩即时返回空串（回退原查询）。 */
  private static final class BaselineModelProvider implements ModelProvider {
    private static final int VECTOR_DIM = 32;

    private final Counters counters;
    private final long embedDelayMs;

    BaselineModelProvider(Counters counters, long embedDelayMs) {
      this.counters = counters;
      this.embedDelayMs = embedDelayMs;
    }

    @Override
    public String provider() {
      return "baseline-fake";
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      counters.chatCalls.incrementAndGet();
      return ModelCallResult.ofText("", "baseline-fake", null, null, null);
    }

    @Override
    public ModelCallResult<String> chat(
        Prompt prompt, com.slz.crm.platform.contract.ModelCallOptions options) {
      return chat(prompt);
    }

    @Override
    public Flux<org.springframework.ai.chat.model.ChatResponse> streamChat(Prompt prompt) {
      throw new UnsupportedOperationException("热路径基线不调用流式模型");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      List<String> texts = request.getInstructions();
      counters.embedCalls.incrementAndGet();
      counters.embedTexts.addAndGet(texts.size());
      long start = System.nanoTime();
      try {
        sleepQuietly(embedDelayMs);
        StringBuilder joined = new StringBuilder();
        for (String text : texts) {
          joined.append(text);
        }
        float[] vector = bucketVector(joined.toString());
        long promptTokens = joined.length();
        return ModelCallResult.ofVector(vector, "baseline-fake", promptTokens, promptTokens);
      } finally {
        counters.embedNanos.addAndGet(System.nanoTime() - start);
      }
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      throw new UnsupportedOperationException("热路径基线不调用视觉模型");
    }

    /** 确定性向量：字符按 codePoint 对 32 取模分桶累加后 L2 归一化；同文本同向量。 */
    private static float[] bucketVector(String text) {
      double[] buckets = new double[VECTOR_DIM];
      for (int index = 0; index < text.length(); index++) {
        buckets[Math.floorMod(text.charAt(index), VECTOR_DIM)] += 1.0d;
      }
      double norm = 0;
      for (double bucket : buckets) {
        norm += bucket * bucket;
      }
      float[] vector = new float[VECTOR_DIM];
      if (norm == 0) {
        vector[0] = 1.0f;
        return vector;
      }
      double scale = 1.0d / Math.sqrt(norm);
      for (int index = 0; index < VECTOR_DIM; index++) {
        vector[index] = (float) (buckets[index] * scale);
      }
      return vector;
    }
  }

  /** 计数向量库装饰器：search/upsertAll 计数计时，upsert 前补齐 Qdrant payload 读回语义。 */
  private static final class CountingVectorStore implements CrmVectorStore {
    private final InMemoryVectorStore delegate = new InMemoryVectorStore();
    private final Counters counters;
    private final long searchDelayMs;

    CountingVectorStore(Counters counters, long searchDelayMs) {
      this.counters = counters;
      this.searchDelayMs = searchDelayMs;
    }

    @Override
    public void upsert(VectorRecord record) {
      delegate.upsert(augmentLikeQdrantPayload(record));
    }

    @Override
    public void upsertAll(List<VectorRecord> records) {
      counters.upsertAllCalls.incrementAndGet();
      delegate.upsertAll(
          records.stream().map(CountingVectorStore::augmentLikeQdrantPayload).toList());
    }

    @Override
    public List<com.slz.crm.platform.contract.VectorSearchHit> search(VectorSearchRequest request) {
      counters.searchCalls.incrementAndGet();
      Object kbId = request.filter().get("knowledgeBaseId");
      counters.searchedKbIds.add(String.valueOf(kbId));
      long start = System.nanoTime();
      try {
        sleepQuietly(searchDelayMs);
        return delegate.search(request);
      } finally {
        counters.searchNanos.addAndGet(System.nanoTime() - start);
      }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
      counters.deleteCalls.incrementAndGet();
      delegate.deleteByDocumentId(documentId);
    }

    /**
     * 复现 {@code QdrantReflectionSupport#toPoint} 的 payload 追加：documentId/chunkId/chunkIndex/text 进
     * metadata。生产 Qdrant 读回命中带 chunkIndex，邻居查询才会触发；内存库默认不带，须在此补齐。
     */
    private static VectorRecord augmentLikeQdrantPayload(VectorRecord record) {
      Map<String, Object> metadata = new LinkedHashMap<>(record.metadata());
      metadata.put("documentId", record.documentId());
      metadata.put("chunkId", record.chunkId());
      metadata.put(
          "chunkIndex", record.chunkIndex() == null ? 0L : record.chunkIndex().longValue());
      metadata.put("text", record.text());
      return new VectorRecord(
          record.id(),
          record.documentId(),
          record.chunkId(),
          record.chunkIndex(),
          record.text(),
          record.embedding(),
          metadata);
    }
  }

  /**
   * 内存 + 计数 {@link DocumentVectorChunkMapper} double：insert 分配数字主键（等价 MP 自增），
   * selectById/selectBatchIds/selectList/fulltextSearch 计数计时并可注入 SQL 延迟；fulltextSearch 用字符 bigram
   * 重叠分近似 ngram FULLTEXT（与 InMemoryDocumentVectorChunkMapper 同口径；语料全部在授权集合内，不建模授权 JOIN）。
   */
  static final class CountingChunkMapper {

    private static final Pattern COL_EQ =
        Pattern.compile("([\\w_]+)\\s*=\\s*#\\{([^}]*\\.(\\w+))\\}");
    private static final Pattern COL_IN = Pattern.compile("([\\w_]+)\\s+IN\\s*\\(([^)]*)\\)");

    private CountingChunkMapper() {}

    static DocumentVectorChunkMapper create(Counters counters, long sqlDelayMs) {
      Handler handler = new Handler(counters, sqlDelayMs);
      return (DocumentVectorChunkMapper)
          Proxy.newProxyInstance(
              DocumentVectorChunkMapper.class.getClassLoader(),
              new Class<?>[] {DocumentVectorChunkMapper.class},
              handler);
    }

    private static final class Handler implements InvocationHandler {
      private final Counters counters;
      private final long sqlDelayMs;
      private final List<DocumentVectorChunkEntity> rows = new ArrayList<>();
      private long idSequence;

      Handler(Counters counters, long sqlDelayMs) {
        this.counters = counters;
        this.sqlDelayMs = sqlDelayMs;
      }

      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
          case "insert" -> insert(args[0]);
          case "selectById" -> selectById(args.length > 0 ? args[0] : null);
          case "selectBatchIds" -> selectBatchIds(args.length > 0 ? args[0] : null);
          case "selectList" -> selectList(args.length > 0 ? args[0] : null);
          case "fulltextSearch" ->
              fulltextSearch(
                  (String) args[0], uncheckedList(args[1]), (String) args[2], (int) args[3]);
          case "deletePhysicallyByDocumentId" -> deletePhysicallyByDocumentId((String) args[0]);
          case "toString" -> "CountingChunkMapper(rows=" + rows.size() + ")";
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          default -> defaultReturn(method.getReturnType());
        };
      }

      private Object insert(Object entity) {
        if (entity instanceof DocumentVectorChunkEntity chunk) {
          if (chunk.getId() == null) {
            chunk.setId(++idSequence);
          }
          rows.add(chunk);
          if ("PARENT".equalsIgnoreCase(chunk.getChunkRole())) {
            counters.insertParentCalls.incrementAndGet();
          } else {
            counters.insertChildCalls.incrementAndGet();
          }
          return 1;
        }
        return defaultReturn(int.class);
      }

      private DocumentVectorChunkEntity selectById(Object id) {
        return timedSql(
            () -> {
              Long target = asLong(id);
              if (target == null) {
                return null;
              }
              for (DocumentVectorChunkEntity row : rows) {
                if (target.equals(row.getId())) {
                  return row;
                }
              }
              return null;
            },
            counters.selectByIdCalls);
      }

      /** 批量主键查询：计数计时与 selectById 同桶口径（快照 SQL），语义 = 逐条 selectById 的行集。 */
      @SuppressWarnings("unchecked")
      private List<DocumentVectorChunkEntity> selectBatchIds(Object ids) {
        return timedSql(
            () -> {
              List<DocumentVectorChunkEntity> matched = new ArrayList<>();
              if (ids instanceof Iterable<?> iterable) {
                for (Object id : iterable) {
                  Long target = asLong(id);
                  if (target == null) {
                    continue;
                  }
                  for (DocumentVectorChunkEntity row : rows) {
                    if (target.equals(row.getId())) {
                      matched.add(row);
                      break;
                    }
                  }
                }
              }
              return List.copyOf(matched);
            },
            counters.selectBatchIdsCalls);
      }

      private List<DocumentVectorChunkEntity> selectList(Object wrapper) {
        return timedSql(
            () -> {
              Map<String, List<Object>> criteria = parseCriteria(wrapper);
              List<DocumentVectorChunkEntity> matched = new ArrayList<>();
              for (DocumentVectorChunkEntity row : rows) {
                if (matches(criteria, row)) {
                  matched.add(row);
                }
              }
              return List.copyOf(matched);
            },
            counters.selectListCalls);
      }

      private List<SparseChunkRow> fulltextSearch(
          String query, List<String> kbIds, String category, int limit) {
        return timedSql(
            () -> {
              java.util.Set<String> queryGrams = InMemoryDocumentVectorChunkMapper.bigrams(query);
              if (queryGrams.isEmpty()) {
                return List.<SparseChunkRow>of();
              }
              List<ScoredRow> hits = new ArrayList<>();
              for (DocumentVectorChunkEntity row : rows) {
                if (!"CHILD".equalsIgnoreCase(row.getChunkRole())) {
                  continue;
                }
                if (category != null
                    && !category.isBlank()
                    && !category.equals(row.getCategory())) {
                  continue;
                }
                java.util.Set<String> rowGrams =
                    InMemoryDocumentVectorChunkMapper.bigrams(row.getChunkText());
                int overlap = 0;
                for (String gram : queryGrams) {
                  if (rowGrams.contains(gram)) {
                    overlap++;
                  }
                }
                if (overlap > 0) {
                  hits.add(new ScoredRow(row, (double) overlap / queryGrams.size()));
                }
              }
              hits.sort((left, right) -> Double.compare(right.score(), left.score()));
              List<SparseChunkRow> result = new ArrayList<>();
              for (ScoredRow hit : hits.subList(0, Math.min(limit, hits.size()))) {
                result.add(toSparseRow(hit.row(), hit.score()));
              }
              return result;
            },
            counters.fulltextCalls);
      }

      private static SparseChunkRow toSparseRow(DocumentVectorChunkEntity row, double score) {
        SparseChunkRow sparseRow = new SparseChunkRow();
        sparseRow.setChunkId(row.getId());
        sparseRow.setDocumentId(row.getDocumentId());
        sparseRow.setChunkIndex(row.getChunkIndex());
        sparseRow.setChunkText(row.getChunkText());
        sparseRow.setFilename(row.getFilename());
        sparseRow.setCategory(row.getCategory());
        sparseRow.setPageNo(row.getPageNo());
        sparseRow.setRowIndex(row.getRowIndex());
        sparseRow.setKnowledgeBaseId(null);
        sparseRow.setScore(score);
        return sparseRow;
      }

      private int deletePhysicallyByDocumentId(String documentId) {
        int before = rows.size();
        rows.removeIf(row -> documentId != null && documentId.equals(row.getDocumentId()));
        return before - rows.size();
      }

      /** QueryWrapper 条目解析：只识别 document_id/chunk_role/chunk_index（邻居查询仅用这三列）。 */
      private Map<String, List<Object>> parseCriteria(Object wrapper) {
        Map<String, List<Object>> criteria = new LinkedHashMap<>();
        if (!(wrapper
            instanceof
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> abstractWrapper)) {
          return criteria;
        }
        String sql = String.valueOf(abstractWrapper.getSqlSegment());
        Map<String, Object> params = new LinkedHashMap<>();
        try {
          Map<String, Object> raw = abstractWrapper.getParamNameValuePairs();
          if (raw != null) {
            params.putAll(raw);
          }
        } catch (RuntimeException ignored) {
          // 读参失败按无参处理：条件解析不到就安全放行（检索侧再按行过滤兜底）
        }
        Matcher inMatcher = COL_IN.matcher(sql);
        while (inMatcher.find()) {
          List<Object> values = new ArrayList<>();
          Matcher token = Pattern.compile("MPGENVAL\\d+").matcher(inMatcher.group(2));
          while (token.find()) {
            Object value = resolveParam(params, token.group());
            if (value instanceof Iterable<?> iterable) {
              iterable.forEach(values::add);
            } else if (value != null) {
              values.add(value);
            }
          }
          criteria.put(inMatcher.group(1).toLowerCase(java.util.Locale.ROOT), values);
        }
        Matcher eqMatcher = COL_EQ.matcher(sql);
        while (eqMatcher.find()) {
          Object value = resolveParam(params, eqMatcher.group(3));
          if (value != null) {
            criteria.put(eqMatcher.group(1).toLowerCase(java.util.Locale.ROOT), List.of(value));
          }
        }
        return criteria;
      }

      private boolean matches(Map<String, List<Object>> criteria, DocumentVectorChunkEntity row) {
        for (Map.Entry<String, List<Object>> entry : criteria.entrySet()) {
          switch (entry.getKey()) {
            case "document_id" -> {
              if (!entry.getValue().contains(row.getDocumentId())) {
                return false;
              }
            }
            case "chunk_role" -> {
              if (!entry.getValue().contains(row.getChunkRole())) {
                return false;
              }
            }
            case "chunk_index" -> {
              boolean hit = false;
              for (Object value : entry.getValue()) {
                Long number = asLong(value);
                if (number != null
                    && number.equals(
                        row.getChunkIndex() == null ? null : Long.valueOf(row.getChunkIndex()))) {
                  hit = true;
                  break;
                }
              }
              if (!hit) {
                return false;
              }
            }
            default -> {
              // 未识别列：宽容放行（基线装配只用到以上三列）
            }
          }
        }
        return true;
      }

      /** SQL 计数 + 可选注入延迟 + 耗时桶（含调用体本身，不只睡眠段）。 */
      private <T> T timedSql(java.util.function.Supplier<T> body, AtomicLong callCounter) {
        callCounter.incrementAndGet();
        long start = System.nanoTime();
        try {
          sleepQuietly(sqlDelayMs);
          return body.get();
        } finally {
          counters.sqlNanos.addAndGet(System.nanoTime() - start);
        }
      }

      private static Object resolveParam(Map<String, Object> params, String tokenName) {
        Object exact = params.get(tokenName);
        if (exact != null || params.containsKey(tokenName)) {
          return exact;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
          if (entry.getKey().endsWith("." + tokenName) || entry.getKey().equals(tokenName)) {
            return entry.getValue();
          }
        }
        return null;
      }

      private static Long asLong(Object value) {
        if (value instanceof Number number) {
          return number.longValue();
        }
        if (value instanceof String text) {
          try {
            return Long.parseLong(text);
          } catch (NumberFormatException exception) {
            return null;
          }
        }
        return null;
      }

      @SuppressWarnings("unchecked")
      private static <T> T uncheckedList(Object value) {
        return (T) value;
      }

      private record ScoredRow(DocumentVectorChunkEntity row, double score) {}
    }
  }

  /** knowledge_base 表 double：selectById 恒返回实体（写授权由 FixedKbAuthorizationService 决定）。 */
  static final class KnowledgeBaseMapperDouble {

    private KnowledgeBaseMapperDouble() {}

    static KnowledgeBaseMapper create(List<Long> knowledgeBaseIds) {
      return (KnowledgeBaseMapper)
          Proxy.newProxyInstance(
              KnowledgeBaseMapper.class.getClassLoader(),
              new Class<?>[] {KnowledgeBaseMapper.class},
              (proxy, method, args) -> {
                if ("selectById".equals(method.getName()) && args != null && args.length > 0) {
                  Long id = null;
                  if (args[0] instanceof Number number) {
                    id = number.longValue();
                  }
                  if (id != null && knowledgeBaseIds.contains(id)) {
                    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
                    entity.setId(id);
                    entity.setVisibility(
                        com.slz.crm.knowledge.entity.KnowledgeBaseVisibility.PRIVATE);
                    return entity;
                  }
                  return null;
                }
                return defaultReturn(method.getReturnType());
              });
    }
  }

  /** uploaded_file 表 double：insert 分配自增主键（等价 MP 回填），updateById 计成功。 */
  static final class UploadedFileMapperDouble {

    private UploadedFileMapperDouble() {}

    static UploadedFileMapper create() {
      AtomicLong idSequence = new AtomicLong();
      return (UploadedFileMapper)
          Proxy.newProxyInstance(
              UploadedFileMapper.class.getClassLoader(),
              new Class<?>[] {UploadedFileMapper.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "insert" -> {
                    if (args != null
                        && args.length > 0
                        && args[0] instanceof com.slz.crm.knowledge.entity.UploadedFileEntity file
                        && file.getId() == null) {
                      file.setId(idSequence.incrementAndGet());
                    }
                    return 1;
                  }
                  case "updateById" -> {
                    return 1;
                  }
                  default -> {
                    return defaultReturn(method.getReturnType());
                  }
                }
              });
    }
  }

  // ---------------------------------------------------------------- 计数与计时工具

  /** 全部假端口的调用计数与耗时桶。单线程消费；Atomic 仅用于跨内联边界可见。 */
  static final class Counters {
    final AtomicLong embedCalls = new AtomicLong();
    final AtomicLong embedTexts = new AtomicLong();
    final AtomicLong embedNanos = new AtomicLong();
    final AtomicLong chatCalls = new AtomicLong();
    final AtomicLong searchCalls = new AtomicLong();
    final AtomicLong searchNanos = new AtomicLong();
    final AtomicLong upsertAllCalls = new AtomicLong();
    final AtomicLong deleteCalls = new AtomicLong();
    final AtomicLong fulltextCalls = new AtomicLong();
    final AtomicLong selectByIdCalls = new AtomicLong();
    final AtomicLong selectListCalls = new AtomicLong();
    final AtomicLong selectBatchIdsCalls = new AtomicLong();
    final AtomicLong insertChildCalls = new AtomicLong();
    final AtomicLong insertParentCalls = new AtomicLong();
    final AtomicLong sqlNanos = new AtomicLong();
    final List<String> searchedKbIds = Collections.synchronizedList(new ArrayList<>());

    void reset() {
      embedCalls.set(0);
      embedTexts.set(0);
      embedNanos.set(0);
      chatCalls.set(0);
      searchCalls.set(0);
      searchNanos.set(0);
      upsertAllCalls.set(0);
      deleteCalls.set(0);
      fulltextCalls.set(0);
      selectByIdCalls.set(0);
      selectListCalls.set(0);
      selectBatchIdsCalls.set(0);
      insertChildCalls.set(0);
      insertParentCalls.set(0);
      sqlNanos.set(0);
      searchedKbIds.clear();
    }

    /** 只清耗时桶（预热后计量前调用），调用次数沿用快照差值口径不需要清零。 */
    void resetBuckets() {
      embedNanos.set(0);
      searchNanos.set(0);
      sqlNanos.set(0);
    }

    long[] snapshot() {
      return new long[] {
        embedCalls.get(),
        embedTexts.get(),
        chatCalls.get(),
        searchCalls.get(),
        upsertAllCalls.get(),
        fulltextCalls.get(),
        selectByIdCalls.get(),
        selectListCalls.get(),
        insertChildCalls.get(),
        insertParentCalls.get(),
        searchedKbIds.size(),
        selectBatchIdsCalls.get()
      };
    }
  }

  private static void assertDelta(
      Counters counters, long[] before, int index, long expected, String label) {
    long[] after = counters.snapshot();
    assertEquals(expected, after[index] - before[index], label);
  }

  /** 未实现方法的安全默认值：基本类型给 0/false，引用类型给 null。 */
  private static Object defaultReturn(Class<?> returnType) {
    if (!returnType.isPrimitive()) {
      return null;
    }
    if (returnType == boolean.class) {
      return false;
    }
    if (returnType == long.class) {
      return 0L;
    }
    if (returnType == double.class) {
      return 0.0d;
    }
    return 0;
  }

  private static void sleepQuietly(long delayMs) {
    if (delayMs > 0) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("基线延迟睡眠被中断", exception);
      }
    }
  }

  private static double millis(long nanos) {
    return nanos / 1_000_000.0d;
  }

  /** 最近邻秩百分位：p50/p95（跨机抖动大，只打印不入门禁）。 */
  private static double percentileMillis(long[] nanos, double p) {
    long[] sorted = nanos.clone();
    Arrays.sort(sorted);
    int index = (int) Math.ceil(p * sorted.length) - 1;
    index = Math.max(0, Math.min(sorted.length - 1, index));
    return millis(sorted[index]);
  }

  /** 合成占比比较：返回注入场景下单独领先（≥10 个百分点）的最高类，仅供文档记录条件结论； 差距不足记并列（tie）。该结果只对预设注入延迟与固定负载成立，不指定生产优化对象。 */
  private static String gateVerdict(double remoteShare, double databaseShare, double localShare) {
    double[] shares = {remoteShare, databaseShare, localShare};
    String[] names = {"remote", "database", "local"};
    java.util.Arrays.sort(shares);
    double diff = shares[shares.length - 1] - shares[shares.length - 2];
    if (diff >= GATE_MARGIN_POINTS) {
      if (remoteShare == shares[shares.length - 1]) {
        return "remote";
      }
      if (databaseShare == shares[shares.length - 1]) {
        return "database";
      }
      return "local";
    }
    return "tie";
  }

  private static double topTwoDiff(double a, double b, double c) {
    double[] shares = {a, b, c};
    java.util.Arrays.sort(shares);
    return shares[shares.length - 1] - shares[shares.length - 2];
  }

  private static void printf(String format, Object... args) {
    System.out.printf(java.util.Locale.ROOT, format, args);
  }
}
