package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.dockerjava.api.exception.NotFoundException;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.config.QdrantProperties;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentIngestionCommand;
import com.slz.crm.knowledge.document.DocumentIngestionResult;
import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.RetrievalDefaults;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMemberMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

/**
 * 本机隔离环境请求热路径度量（add-representative-hotpath-measurement）。
 *
 * <p><b>证据级别</b>：「本机真实存储 + 本地模型桩」。检索与摄取主路径接<b>本机一次性 MySQL/Qdrant</b>——生产 Flyway 迁移链建库、 生产
 * MyBatis-Plus mapper/SQL/索引、生产 {@link KnowledgeBaseAuthorizationService} 授权 SQL、生产 {@link
 * QdrantVectorStore} gRPC 客户端与 payload 过滤；模型 embed/chat/查询改写<b>硬绑定确定性本地桩</b>：不读任何真实密钥（env 与仓库 .env
 * 都不读）、不构造 {@link ModelProviderImpl}，环境里留有真实 Provider 开关或密钥也不影响装配。<b>本结果不是生产 RTT</b>，不得外推生产瓶颈，
 * 不构成任何生产优化或费用授权。
 *
 * <p><b>非默认执行（fail closed）</b>：类名不以 {@code Test/Tests/IT/IntegrationTest} 结尾，surefire/failsafe
 * 默认发现不到； 仅显式 {@code -Dtest=RepresentativeHotpathBenchmark} 才会运行。运行时先校验独立 opt-in 开关 {@link
 * #OPT_IN_ENV} （先于一切容器/装配动作），再做 Docker/镜像<b>只读</b>预检（不 pull）。开关缺失、Docker 不可用、镜像缺失一律显式失败并报告「未测」，
 * 绝不自动拉取镜像、不连业务数据库、不假装成功。
 *
 * <p><b>固定负载（复用 add-request-hotpath-baseline，逐项可对照）</b>：检索 = 4 个授权库（真实非超管 owner 授权 SQL 路径）×
 * 单路查询「客户合同续签流程」× topK 5；摄取 = 6600 字符（fixed 320/40 → 24 子块、0 父块）。摄取写独立库（90001），每样本
 * <b>计时窗口之外</b>物理清理复位（切片行/文件行/Qdrant 点），样本初始数据规模恒定，检索种子库（1–4）不受写入影响。
 *
 * <p><b>采集口径</b>：端到端墙钟逐请求采样；分段（授权 SQL、稀疏 SQL、父块展开、邻居查询、切片/文件写库、连接获取、改写桩、嵌入桩、 Qdrant
 * 检索/写入、融合、重排、上下文、摄取解析）经测试侧计时装饰器在<b>同一窗口</b>累计，报告为「窗口总量 ÷ 成功请求数」的平均口径。 连接获取是 SQL
 * 分段的<b>嵌套（包含）子段</b>；并发档各分段相互重叠，段间只做同窗平均比较，各段之和<b>不等于</b>端到端， 也不得冒称 CPU 或业务规则时间。资源同窗采集：进程 CPU（50ms
 * 采样线程均值）、堆（窗口峰值/首末）、GC 计数/暂停增量、线程数、 阻塞态线程采样；容器资源 best effort，取不到写「未知」不写 0。
 *
 * <p><b>复现</b>：{@code REPRESENTATIVE_HOTPATH_MEASURE=1 mvn -B -ntp
 * -Dtest=RepresentativeHotpathBenchmark test} （stdout 搜 {@code REPHOT} 行；surefire 报告 system-out
 * 有同样内容）。同负载两轮（round 1/2）在同一 JVM、同一容器、同一种子上完成； 重新执行命令即整轮复跑。
 */
class RepresentativeHotpathBenchmark {

  // ---- 独立 opt-in 开关与镜像钉扎（先于一切容器/装配动作校验） ----

  /** 本案独立 opt-in 开关；与 RAG_BENCHMARK_REAL / RAG_VISION_PDF_REAL 等旧开关完全无关、互不读取。 */
  static final String OPT_IN_ENV = "REPRESENTATIVE_HOTPATH_MEASURE";

  /** 本地已存在、禁止自动拉取的 MySQL 镜像钉扎。 */
  static final String DEFAULT_MYSQL_IMAGE = "mysql:8.0";

  /** 本地已存在的 Qdrant 镜像钉扎。 */
  static final String DEFAULT_QDRANT_IMAGE = "qdrant/qdrant:v1.18.3";

  /** 镜像名覆盖系统属性：仅用于 fail-closed 定向验证（指向不存在的镜像名并确认不拉取即失败）。真实度量必须用默认钉扎。 */
  static final String MYSQL_IMAGE_PROP = "rephot.mysql.image";

  static final String QDRANT_IMAGE_PROP = "rephot.qdrant.image";

  // ---- 固定负载（与 docs/request-hotpath-baseline.md §1.1 逐项一致） ----

  /** 检索负载：授权库数。 */
  private static final List<Long> KB_IDS = List.of(1L, 2L, 3L, 4L);

  /** 检索负载：topK（显式传入，与 RetrievalDefaults.TOP_K 一致）。 */
  private static final int TOP_K = 5;

  /** 检索负载：不拆分查询——不含分隔符，ConstraintQuerySplitter 恒单路（与旧基线同一条）。 */
  private static final String RETRIEVAL_QUERY = "客户合同续签流程";

  /** 摄取负载：预期 24 个非空子块（fixed 320/40 下 6600 字符）。 */
  private static final int INGEST_CHUNK_TARGET = 24;

  /** 摄取负载文本长度：落在 (280×23, 280×24] 内，保证恰好 24 个子块（与旧基线同一构造）。 */
  private static final int INGEST_TEXT_CHARS = 6600;

  /** 摄取独立库 id：检索种子库（1–4）不受摄取测量写入影响。 */
  private static final long INGEST_KB_ID = 90001L;

  /** 基准用户：非超管（roleId≠1），真实授权走 owner/PUBLIC/成员三条 SQL。 */
  private static final long USER_ID = 10001L;

  /** 伪向量维度（与旧基线 BaselineModelProvider 同款 32 维确定性向量）。 */
  private static final int VECTOR_DIM = 32;

  // ---- 采样计划（预热 + 稳态窗口；两轮同负载） ----

  private static final int RETRIEVAL_C1_WARMUP = 10;
  private static final int RETRIEVAL_C1_SAMPLES = 80;
  private static final int RETRIEVAL_C8_THREADS = 8;
  private static final int RETRIEVAL_C8_WARMUP = 8;
  private static final int RETRIEVAL_C8_SAMPLES_PER_THREAD = 20;
  private static final int INGEST_THREADS = 4;
  private static final int INGEST_WARMUP = 4;
  private static final int INGEST_SAMPLES_PER_THREAD = 10;
  private static final int ROUNDS = 2;

  // ---- 每请求调用形状（真实存储下预期与旧基线一致；漂移即显式红，据实修正记录） ----

  /** 检索每请求授权 SQL：owner selectList + PUBLIC selectList + 成员 selectList（非超管路径）。 */
  private static final long AUTH_SQL_PER_RETRIEVAL = 3;

  /** 检索每请求 Qdrant search：= 授权库数。 */
  private static final long QDRANT_SEARCH_PER_RETRIEVAL = 4;

  /** 检索每请求 SQL 总数（授权 3 + 稀疏 1 + 父块展开 5 + 邻居 5）。 */
  private static final long SQL_PER_RETRIEVAL = 14;

  /** 摄取每文档授权/库读取 SQL（knowledge_base selectById；owner 短路不再查成员表）。 */
  private static final long AUTH_SQL_PER_INGEST = 1;

  /** 摄取每文档 SQL（库读取 1 + 子块 insert 24 + 文件 insert 1 + 文件 update 1）。 */
  private static final long SQL_PER_INGEST = 27;

  /** 共享分段计数器。 */
  private final Counters counters = new Counters();

  // ---------------------------------------------------------------- 度量入口

  @Test
  void measureWithRealLocalStorageAndStubModels() throws Exception {
    // 1) 独立 opt-in：先于任何 Docker/镜像/装配动作（spec 阶段 1 的硬顺序）
    requireOptIn();
    // 2) 模型桩硬绑定自证
    assertStubProviderHardwired();
    // 3) Docker/镜像只读预检：缺失即 fail closed，不自动拉取
    String mysqlImage = System.getProperty(MYSQL_IMAGE_PROP, DEFAULT_MYSQL_IMAGE);
    String qdrantImage = System.getProperty(QDRANT_IMAGE_PROP, DEFAULT_QDRANT_IMAGE);
    requireDockerAndLocalImages(List.of(mysqlImage, qdrantImage));

    MySQLContainer<?> mysql = null;
    GenericContainer<?> qdrant = null;
    try {
      mysql =
          new MySQLContainer<>(DockerImageName.parse(mysqlImage))
              .withDatabaseName("crm_rephot")
              .withUsername("crm")
              .withPassword("crm_rephot_pwd");
      mysql.start();
      qdrant =
          new GenericContainer<>(DockerImageName.parse(qdrantImage))
              .withExposedPorts(6333, 6334)
              .waitingFor(Wait.forListeningPort());
      qdrant.start();
      awaitQdrantReady(qdrant);

      migrateAndSeed(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());

      Environment env =
          assemble(
              mysql.getJdbcUrl(),
              mysql.getUsername(),
              mysql.getPassword(),
              mysql.getDriverClassName(),
              qdrant.getHost(),
              qdrant.getMappedPort(6334));

      printEnvironment(mysqlImage, qdrantImage, qdrant);
      runBothRounds(env);
      env.rawStore().close();
      printf(
          "REPHOT verdict: evidence-class=local-real-storage+local-model-stub production-rtt=unknown");
    } finally {
      if (qdrant != null) {
        qdrant.stop();
      }
      if (mysql != null) {
        mysql.stop();
      }
    }
  }

  // ---------------------------------------------------------------- 门禁（静态、纯 JVM）

  /** 独立 opt-in 校验：缺失即抛错（fail closed），绝不进入容器/装配阶段。 */
  static void requireOptIn() {
    if (!"1".equals(System.getenv(OPT_IN_ENV))) {
      throw new IllegalStateException(
          "未测：缺少独立 opt-in 开关 "
              + OPT_IN_ENV
              + "=1。本度量入口不允许默认运行；开关缺失/镜像缺失一律 fail closed，不自动拉取镜像、不假装成功。");
    }
  }

  /** 模型桩硬绑定自证：装配层只构造本地桩；真实 Provider 开关/密钥存在与否都不影响。 */
  static void assertStubProviderHardwired() {
    StubModelProvider stub = new StubModelProvider(new Counters());
    ModelProvider port = stub;
    assertTrue(
        port.getClass() != ModelProviderImpl.class,
        "模型端口运行时类不得是 ModelProviderImpl（真实 Provider 禁止混入装配）");
    assertEquals(StubModelProvider.STUB_NAME, stub.provider(), "provider 名必须是本地桩标识");
  }

  /** Docker/镜像只读预检：不 pull；镜像缺失即抛错并报告「未测」。 */
  static void requireDockerAndLocalImages(List<String> images) {
    if (!DockerClientFactory.instance().isDockerAvailable()) {
      throw new IllegalStateException("未测：Docker 不可用。本度量需要本机一次性 MySQL/Qdrant，按规格不自动拉取镜像、不降级假装成功。");
    }
    List<String> missing = missingLocalImages(images);
    if (!missing.isEmpty()) {
      throw new IllegalStateException("未测：本地镜像缺失 " + missing + "。按规格不自动拉取镜像；请先手工准备与钉扎一致的镜像后重试。");
    }
  }

  /** 逐镜像 inspectImage 只读探测，返回缺失清单（纯查询，无拉取副作用）。 */
  static List<String> missingLocalImages(List<String> images) {
    List<String> missing = new ArrayList<>();
    for (String image : images) {
      try {
        DockerClientFactory.instance().client().inspectImageCmd(image).exec();
      } catch (NotFoundException exception) {
        missing.add(image);
      }
    }
    return missing;
  }

  // ---------------------------------------------------------------- 环境打印

  private void printEnvironment(String mysqlImage, String qdrantImage, GenericContainer<?> qdrant) {
    com.sun.management.OperatingSystemMXBean osBean =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    printf(
        "REPHOT env: evidence_class=%s docker=%s mysql_image=%s qdrant_image=%s",
        "local-real-storage+local-model-stub", "available", mysqlImage, qdrantImage);
    printf(
        "REPHOT env: java=%s jacoco_agent=%s cores=%d model_provider=%s real_key_read=false",
        System.getProperty("java.version"),
        jacocoAgentAttached() ? "attached" : "not-attached",
        osBean.getAvailableProcessors(),
        StubModelProvider.STUB_NAME);
    printf(
        "REPHOT config: dynamic_config=empty-map(defaults) top_k=%d(min_score=%.2f default) qdrant_collection=%s dims=%d",
        TOP_K, RetrievalDefaults.MIN_SCORE, "representative_hotpath", VECTOR_DIM);
    printf(
        "REPHOT load: retrieval_query=%s kb_ids=%s top_k=%d ingest_chars=%d ingest_chunks=%d corpus_docs=%d",
        RETRIEVAL_QUERY, KB_IDS, TOP_K, INGEST_TEXT_CHARS, INGEST_CHUNK_TARGET, KB_IDS.size());
  }

  // ---------------------------------------------------------------- 两轮同负载度量

  private void runBothRounds(Environment env) throws Exception {
    for (int round = 1; round <= ROUNDS; round++) {
      printf("REPHOT round %d: begin", round);
      runRetrievalPhase(env, round, 1, RETRIEVAL_C1_WARMUP, RETRIEVAL_C1_SAMPLES, true);
      runRetrievalPhase(
          env,
          round,
          RETRIEVAL_C8_THREADS,
          RETRIEVAL_C8_WARMUP,
          RETRIEVAL_C8_THREADS * RETRIEVAL_C8_SAMPLES_PER_THREAD,
          false);
      runIngestPhase(
          env, round, INGEST_THREADS, INGEST_WARMUP, INGEST_THREADS * INGEST_SAMPLES_PER_THREAD);
      printf("REPHOT round %d: end", round);
    }
  }

  /** 检索相：预热 → 计数清零 → 稳态采样 → 分段/资源汇总 → 调用形状断言。 */
  private void runRetrievalPhase(
      Environment env,
      int round,
      int concurrency,
      int warmup,
      int samples,
      boolean perRequestAssert)
      throws Exception {
    counters.reset();
    for (int i = 0; i < warmup; i++) {
      retrieveOnce(env);
    }
    counters.reset();

    ResourceWindow resources = ResourceWindow.start();
    ConcurrentLinkedQueue<Long> e2eNanos = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Long> failures = new ConcurrentLinkedQueue<>();
    // 单并发档逐请求形状断言：用上一请求快照作 before（c1 下确定性好）；并发档只做轮级总量断言
    AtomicReference<long[]> lastSnapshot = new AtomicReference<>(counters.snapshot());
    runConcurrently(
        concurrency,
        samples,
        () -> {
          long start = System.nanoTime();
          try {
            KnowledgeRetrievalPort.RetrievalResult result = retrieveOnce(env);
            e2eNanos.add(System.nanoTime() - start);
            if (perRequestAssert) {
              long[] before = lastSnapshot.get();
              long[] after = counters.snapshot();
              assertRetrievalShape(before, after, result);
              lastSnapshot.compareAndSet(before, after);
            }
          } catch (RuntimeException exception) {
            failures.add(System.nanoTime() - start);
          }
        });
    ResourceSample resourceSample = resources.stop();

    reportPhase("retrieval", round, concurrency, samples, e2eNanos, failures, resourceSample);
    assertRetrievalTotals(samples);
    verifyKbFilterCoverage(samples);
  }

  /** 摄取相：预热 → 计数清零 → 稳态采样（每样本计时窗口外复位）→ 分段/资源汇总 → 形状与初始状态断言。 */
  private void runIngestPhase(Environment env, int round, int concurrency, int warmup, int samples)
      throws Exception {
    counters.reset();
    for (int i = 0; i < warmup; i++) {
      DocumentIngestionResult warm =
          env.ingestion().ingest(ingestCommand(INGEST_KB_ID, ingestText()));
      resetDocument(env, warm.documentId());
    }
    counters.reset();
    long chunksBefore = countRows(env, "document_vector_chunk");
    long filesBefore = countRows(env, "uploaded_file");

    ResourceWindow resources = ResourceWindow.start();
    ConcurrentLinkedQueue<Long> e2eNanos = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Long> failures = new ConcurrentLinkedQueue<>();
    runConcurrently(
        concurrency,
        samples,
        () -> {
          long start = System.nanoTime();
          String documentId = null;
          try {
            DocumentIngestionResult result =
                env.ingestion().ingest(ingestCommand(INGEST_KB_ID, ingestText()));
            documentId = result.documentId();
            e2eNanos.add(System.nanoTime() - start);
            if (result.chunkCount() != INGEST_CHUNK_TARGET) {
              throw new IllegalStateException(
                  "子块数漂移：期望 " + INGEST_CHUNK_TARGET + " 实际 " + result.chunkCount());
            }
          } catch (RuntimeException exception) {
            failures.add(System.nanoTime() - start);
            printf(
                "REPHOT ingest-failure: %s: %s",
                exception.getClass().getSimpleName(), String.valueOf(exception.getMessage()));
          } finally {
            // 复位在计时窗口之外：每个样本的初始数据规模一致；失败样本也按 documentId 兜底清理
            resetDocument(env, documentId);
          }
        });
    ResourceSample resourceSample = resources.stop();

    reportPhase("ingest", round, concurrency, samples, e2eNanos, failures, resourceSample);
    assertIngestTotals(samples);
    long chunksAfter = countRows(env, "document_vector_chunk");
    long filesAfter = countRows(env, "uploaded_file");
    assertEquals(chunksBefore, chunksAfter, "摄取轮结束后切片行数必须复位（样本初始数据状态一致）");
    assertEquals(filesBefore, filesAfter, "摄取轮结束后文件行数必须复位（样本初始数据状态一致）");
    printf(
        "REPHOT ingest-state: round=%d chunks_before=%d chunks_after=%d files_before=%d files_after=%d",
        round, chunksBefore, chunksAfter, filesBefore, filesAfter);
  }

  // ---------------------------------------------------------------- 装配

  /**
   * 生产服务 + 本机存储 + 模型桩装配。真实面：MyBatis-Plus 生产 mapper（会话按调用开关，等价 Spring SqlSessionTemplate 形状）、 生产
   * {@link KnowledgeBaseAuthorizationService}（真实授权 SQL）、生产 {@link SparseRecallService} / {@link
   * ContextBuilder} / {@link RrfFusion} / {@link DefaultWeightedReranker}、生产 {@link
   * QdrantVectorStore}（gRPC + payload 过滤）。 替换面（全部测试侧构件）：{@link StubModelProvider}、计时装饰器、{@link
   * InMemoryFileStorageService}（文件字节内存暂存， 非测量目标）、{@link GovernanceAuditRecorder} 空记录器（ingest
   * 路径不触发审计）。
   */
  private Environment assemble(
      String jdbcUrl,
      String user,
      String password,
      String driver,
      String qdrantHost,
      int qdrantPort)
      throws Exception {
    PooledTimingDataSource pooledDataSource =
        new PooledTimingDataSource(driver, jdbcUrl, user, password, counters);

    MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
    factoryBean.setDataSource(pooledDataSource);
    SqlSessionFactory factory = factoryBean.getObject();
    Configuration configuration = factory.getConfiguration();
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.addMapper(KnowledgeBaseMapper.class);
    configuration.addMapper(KnowledgeBaseMemberMapper.class);
    configuration.addMapper(UploadedFileMapper.class);
    configuration.addMapper(DocumentVectorChunkMapper.class);

    // 生产 mapper（会话按调用开关，线程安全）→ 计数计时代理（同窗分段）
    KnowledgeBaseMapper kbMapper =
        timed(KnowledgeBaseMapper.class, dispatch(factory, KnowledgeBaseMapper.class), counters);
    KnowledgeBaseMemberMapper memberMapper =
        timed(
            KnowledgeBaseMemberMapper.class,
            dispatch(factory, KnowledgeBaseMemberMapper.class),
            counters);
    UploadedFileMapper uploadedFileMapper =
        timed(UploadedFileMapper.class, dispatch(factory, UploadedFileMapper.class), counters);
    DocumentVectorChunkMapper chunkMapper =
        timed(
            DocumentVectorChunkMapper.class,
            dispatch(factory, DocumentVectorChunkMapper.class),
            counters);

    StubModelProvider provider = new StubModelProvider(counters);
    EmbeddingService embedding = new EmbeddingService(provider, new ModelProviderProperties());

    QdrantProperties qdrantProperties = new QdrantProperties();
    qdrantProperties.setHost(qdrantHost);
    qdrantProperties.setPort(qdrantPort);
    qdrantProperties.setCollection("representative_hotpath");
    qdrantProperties.setDimensions(VECTOR_DIM);
    qdrantProperties.setTimeoutMs(10_000);
    qdrantProperties.setInitializeOnStartup(true);
    QdrantVectorStore rawStore = new QdrantVectorStore(qdrantProperties);
    rawStore.initialize();
    // 检索/写/删全部走修复后的生产 QdrantVectorStore（fix-qdrant-search-score-conversion 之后无需测试侧 shim）
    TimedVectorStore vectorStore = new TimedVectorStore(rawStore, counters);

    KnowledgeBaseAuthorizationService authorization =
        new KnowledgeBaseAuthorizationService(kbMapper, memberMapper);
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(Map.of()));
    RetrievalQueryRewriteService rewrite =
        new RetrievalQueryRewriteService(provider, configProvider);
    SparseRecallService sparse = new SparseRecallService(chunkMapper);
    DefaultWeightedReranker reranker = new TimedReranker(new Bm25Scorer(), configProvider);
    ContextBuilder contextBuilder =
        new TimedContextBuilder(chunkMapper, configProvider, new RuleContextCompressor(), null);
    RrfFusion rrfFusion = new TimedRrfFusion();
    KnowledgeRetrievalPort retrieval =
        new KnowledgeRetrievalServiceImpl(
            authorization,
            embedding,
            vectorStore,
            rewrite,
            configProvider,
            sparse,
            rrfFusion,
            reranker,
            null,
            contextBuilder);

    DocumentService documentService = new TimedDocumentService();
    DocumentIngestionService ingestion =
        new DocumentIngestionService(
            kbMapper,
            uploadedFileMapper,
            chunkMapper,
            authorization,
            new InMemoryFileStorageService(),
            documentService,
            embedding,
            vectorStore,
            new GovernanceAuditRecorder(null, null));

    Environment env = new Environment(retrieval, ingestion, rawStore, pooledDataSource);
    // 语料种子经生产摄取路径落库（真实 SQL + 真实 Qdrant upsert），随后清零计数（语料不在测量窗口内）
    for (Long kbId : KB_IDS) {
      DocumentIngestionResult corpus = ingestion.ingest(ingestCommand(kbId, corpusText(kbId)));
      printf(
          "REPHOT corpus: kb=%d document_id=%s chunks=%d vectors=%d",
          kbId, corpus.documentId(), corpus.chunkCount(), corpus.vectorCount());
    }
    counters.reset();
    return env;
  }

  /** Flyway 生产迁移链建库 + 授权数据种子：4 个检索库 + 1 个摄取库，全部 owner=user:10001（真实非超管授权 SQL 路径）。 */
  private static void migrateAndSeed(String jdbcUrl, String user, String password) {
    Flyway.configure()
        .dataSource(jdbcUrl, user, password)
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
    try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
      seedKnowledgeBase(connection, 1L);
      seedKnowledgeBase(connection, 2L);
      seedKnowledgeBase(connection, 3L);
      seedKnowledgeBase(connection, 4L);
      seedKnowledgeBase(connection, INGEST_KB_ID);
    } catch (SQLException exception) {
      throw new IllegalStateException("种子数据写入失败", exception);
    }
  }

  private static void seedKnowledgeBase(Connection connection, long kbId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_base (id, name, display_name, owner_user_id, visibility) "
                + "VALUES (?, ?, ?, ?, 'PRIVATE')")) {
      statement.setLong(1, kbId);
      statement.setString(2, "rephot-kb-" + kbId);
      statement.setString(3, "rephot-kb-" + kbId);
      statement.setString(4, "user:" + USER_ID);
      statement.executeUpdate();
    }
  }

  // ---------------------------------------------------------------- 负载

  private KnowledgeRetrievalPort.RetrievalResult retrieveOnce(Environment env) {
    UserContext user = benchmarkUser();
    return UserContextHolder.callWith(
        user,
        () ->
            env.retrieval()
                .retrieve(
                    new KnowledgeRetrievalPort.RetrievalQuery(
                        RETRIEVAL_QUERY,
                        user.userId(),
                        // 摄取库 90001 同属 owner 可见集合；scope 显式收敛到 4 个检索库，
                        // 与旧基线「4 个已授权 KB」负载保持可对照（授权 SQL 形状不变）
                        KB_IDS.stream().map(String::valueOf).toList(),
                        TOP_K,
                        null,
                        null)));
  }

  private static DocumentIngestionCommand ingestCommand(long kbId, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return new DocumentIngestionCommand(
        kbId,
        benchmarkUser(),
        "rephot-" + kbId + "-" + UUID.randomUUID() + ".txt",
        "text/plain",
        (long) bytes.length,
        "合同",
        null,
        new ByteArrayInputStream(bytes));
  }

  /** 检索语料文本：与旧基线同款重复句（≈3003 字符，fixed 320/40 → 11 片）。 */
  private static String corpusText(long kbId) {
    String sentence = "客户合同续签流程：客户经理需在到期前确认客户合同条款并归档记录" + kbId + "。";
    StringBuilder text = new StringBuilder();
    while (text.length() < 3000) {
      text.append(sentence);
    }
    return text.toString();
  }

  /** 摄取负载文本：10 字符句重复到 6600 字符 → fixed 320/40 恰好 24 个非空子块、0 个父块（与旧基线同款）。 */
  static String ingestText() {
    StringBuilder text = new StringBuilder();
    while (text.length() < INGEST_TEXT_CHARS) {
      text.append("客户合同续签流程要点");
    }
    return text.toString();
  }

  static UserContext benchmarkUser() {
    return new UserContext(USER_ID, 3L, 11L, DataScopeLevel.NONE, "代表性热路径度量用户");
  }

  // ---------------------------------------------------------------- 断言

  /** 检索每请求形状（单并发档逐请求断言）：命中 topK、授权 3 SQL、向量检索 4、稀疏 1、父块展开/邻居各 5。 */
  private void assertRetrievalShape(
      long[] before, long[] after, KnowledgeRetrievalPort.RetrievalResult result) {
    assertEquals(TOP_K, result.hitCount(), "命中数应为 topK");
    assertDelta(
        before,
        after,
        Counters.AUTH_SQL_CALLS,
        AUTH_SQL_PER_RETRIEVAL,
        "每请求授权 SQL 3 条（owner+PUBLIC+成员）");
    assertDelta(before, after, Counters.EMBED_CALLS, 1L, "每请求嵌入 1 次（单路不拆分）");
    assertDelta(before, after, Counters.REWRITE_CALLS, 1L, "查询改写桩 1 次");
    assertDelta(
        before, after, Counters.QDRANT_SEARCH_CALLS, QDRANT_SEARCH_PER_RETRIEVAL, "向量检索 = 授权库数");
    assertDelta(before, after, Counters.FULLTEXT_CALLS, 1L, "稀疏检索 1 次");
    assertDelta(before, after, Counters.SELECT_BY_ID_CALLS, TOP_K, "父块展开 selectById = 命中数");
    assertDelta(before, after, Counters.SELECT_LIST_CALLS, TOP_K, "邻居查询 = 命中数");
    assertDelta(before, after, Counters.QDRANT_UPSERT_CALLS, 0L, "检索不写向量库");
  }

  /** 检索轮级形状总断言：并发档按总量断言（每请求形状已在单并发档锁定）。 */
  private void assertRetrievalTotals(int samples) {
    long[] after = counters.snapshot();
    assertDelta(after, Counters.EMBED_CALLS, samples, "轮内嵌入总数 = 样本数");
    assertDelta(after, Counters.REWRITE_CALLS, samples, "轮内改写桩总数 = 样本数");
    assertDelta(
        after,
        Counters.QDRANT_SEARCH_CALLS,
        samples * QDRANT_SEARCH_PER_RETRIEVAL,
        "轮内 Qdrant search 总数 = 样本数 × 授权库数");
    assertDelta(after, Counters.FULLTEXT_CALLS, samples, "轮内稀疏检索总数 = 样本数");
    assertDelta(
        after, Counters.AUTH_SQL_CALLS, samples * AUTH_SQL_PER_RETRIEVAL, "轮内授权 SQL 总数 = 样本数 × 3");
    assertDelta(
        after,
        Counters.SQL_CALLS,
        samples * SQL_PER_RETRIEVAL,
        "轮内 SQL 总数 = 样本数 × 14（授权+稀疏+父块+邻居）");
  }

  /** 逐库过滤覆盖：本轮每个授权库 id 恰好被检索 samples 次（Qdrant payload 过滤真的逐库下发）。 */
  private void verifyKbFilterCoverage(int samples) {
    List<String> searched = counters.searchedKbIds();
    assertEquals(samples * KB_IDS.size(), searched.size(), "逐库检索次数 = 样本数 × 授权库数");
    for (Long kbId : KB_IDS) {
      long hits = searched.stream().filter(kbId.toString()::equals).count();
      assertEquals(samples, hits, "库 " + kbId + " 被检索次数 = 样本数");
    }
  }

  /** 摄取轮级形状总断言：嵌入/写库/upsert 逐文档形状。 */
  private void assertIngestTotals(int samples) {
    long[] after = counters.snapshot();
    assertDelta(after, Counters.EMBED_CALLS, samples * INGEST_CHUNK_TARGET, "每子块 1 次嵌入");
    assertDelta(after, Counters.INSERT_CHILD_CALLS, samples * INGEST_CHUNK_TARGET, "子块行 = 24/文档");
    assertDelta(after, Counters.INSERT_PARENT_CALLS, 0L, "父块行 = 0（fixed 无父块）");
    assertDelta(after, Counters.QDRANT_UPSERT_CALLS, samples, "整文档一次 upsertAll");
    assertDelta(after, Counters.QDRANT_SEARCH_CALLS, 0L, "摄取不检索");
    assertDelta(after, Counters.AUTH_SQL_CALLS, samples * AUTH_SQL_PER_INGEST, "每文档 1 条库读取 SQL");
    assertDelta(after, Counters.SQL_CALLS, samples * SQL_PER_INGEST, "每文档 27 条 SQL（库读取+切片+文件）");
  }

  private static void assertDelta(
      long[] before, long[] after, int index, long expected, String label) {
    assertEquals(expected, after[index] - before[index], label);
  }

  private static void assertDelta(long[] after, int index, long expected, String label) {
    assertEquals(expected, after[index], label);
  }

  // ---------------------------------------------------------------- 并发执行

  private void runConcurrently(int concurrency, int samples, Runnable body) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(concurrency);
    try {
      CountDownLatch ready = new CountDownLatch(concurrency);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      int perThread = Math.max(1, samples / concurrency);
      int assigned = 0;
      for (int thread = 0; thread < concurrency; thread++) {
        int quota =
            thread == concurrency - 1
                ? Math.max(0, samples - perThread * (concurrency - 1))
                : perThread;
        assigned += quota;
        final int runs = quota;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  try {
                    go.await();
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  for (int i = 0; i < runs; i++) {
                    body.run();
                  }
                }));
      }
      if (assigned != samples) {
        throw new IllegalStateException("并发档样本分配不齐：" + assigned + " != " + samples);
      }
      ready.await();
      go.countDown();
      long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
      for (Future<?> future : futures) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        future.get(Math.max(1, remainingMs), TimeUnit.MILLISECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------------------------------------------------------------- 分段/资源汇总与报告

  private void reportPhase(
      String phase,
      int round,
      int concurrency,
      int samples,
      ConcurrentLinkedQueue<Long> e2eNanos,
      ConcurrentLinkedQueue<Long> failures,
      ResourceSample resourceSample) {
    long[] e2e = e2eNanos.stream().mapToLong(Long::longValue).toArray();
    long successCount = e2e.length;
    printf(
        "REPHOT %s r%d c%d window: samples=%d success=%d failure=%d timeout=within-5min-deadline"
            + " throughput_ok_per_s=%.3f window_s=%.3f",
        phase,
        round,
        concurrency,
        samples,
        successCount,
        failures.size(),
        successCount / Math.max(1e-9, resourceSample.windowSeconds()),
        resourceSample.windowSeconds());
    printPercentiles(phase, round, concurrency, "e2e", e2e);
    printSegmentAverages(phase, round, concurrency, successCount);
    resourceSample.print(phase, round, concurrency);
  }

  private void printPercentiles(
      String phase, int round, int concurrency, String metric, long[] nanos) {
    if (nanos.length == 0) {
      printf(
          "REPHOT %s r%d c%d %s: n=0 p50=unknown p95=unknown p99=unknown max=unknown",
          phase, round, concurrency, metric);
      return;
    }
    printf(
        "REPHOT %s r%d c%d %s: n=%d p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f max_ms=%.3f",
        phase,
        round,
        concurrency,
        metric,
        nanos.length,
        percentileMillis(nanos, 0.50),
        percentileMillis(nanos, 0.95),
        percentileMillis(nanos, 0.99),
        percentileMillis(nanos, 1.00));
  }

  /** 分段同窗平均：只声明平均口径，不与端到端求和比较，不冒称 CPU/业务规则时间。 */
  private void printSegmentAverages(String phase, int round, int concurrency, long requests) {
    if (requests <= 0) {
      return;
    }
    long[] after = counters.snapshot();
    segment(
        phase,
        round,
        concurrency,
        "auth_sql",
        after,
        Counters.AUTH_SQL_NANOS,
        Counters.AUTH_SQL_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "rewrite_stub",
        after,
        Counters.REWRITE_NANOS,
        Counters.REWRITE_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "embed_stub",
        after,
        Counters.EMBED_NANOS,
        Counters.EMBED_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "qdrant_search",
        after,
        Counters.QDRANT_SEARCH_NANOS,
        Counters.QDRANT_SEARCH_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "qdrant_write",
        after,
        Counters.QDRANT_UPSERT_NANOS,
        Counters.QDRANT_UPSERT_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "sparse_sql",
        after,
        Counters.FULLTEXT_NANOS,
        Counters.FULLTEXT_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "parent_sql",
        after,
        Counters.SELECT_BY_ID_NANOS,
        Counters.SELECT_BY_ID_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "neighbor_sql",
        after,
        Counters.SELECT_LIST_NANOS,
        Counters.SELECT_LIST_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "chunk_insert",
        after,
        Counters.INSERT_NANOS,
        Counters.INSERT_CHILD_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "file_sql",
        after,
        Counters.FILE_SQL_NANOS,
        Counters.FILE_SQL_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "conn_acquire_inclusive",
        after,
        Counters.CONN_NANOS,
        Counters.CONN_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "fuse",
        after,
        Counters.FUSE_NANOS,
        Counters.FUSE_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "rerank",
        after,
        Counters.RERANK_NANOS,
        Counters.RERANK_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "context",
        after,
        Counters.CONTEXT_NANOS,
        Counters.CONTEXT_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "ingest_parse",
        after,
        Counters.PARSE_NANOS,
        Counters.PARSE_CALLS,
        requests);
    segment(
        phase,
        round,
        concurrency,
        "sql_all",
        after,
        Counters.SQL_NANOS,
        Counters.SQL_CALLS,
        requests);
  }

  private void segment(
      String phase,
      int round,
      int concurrency,
      String name,
      long[] after,
      int nanosIndex,
      int callsIndex,
      long requests) {
    printf(
        "REPHOT %s r%d c%d seg_%s: avg_ms=%.3f calls_per_req=%.2f",
        phase,
        round,
        concurrency,
        name,
        after[nanosIndex] / 1e6 / requests,
        after[callsIndex] / (double) requests);
  }

  // ---------------------------------------------------------------- 资源同窗采样

  /** 资源窗口：进程 CPU 采样线程 + 窗口首末堆/GC/线程快照 + 阻塞态线程采样。 */
  private static final class ResourceWindow {
    private final com.sun.management.OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private final List<GarbageCollectorMXBean> gcBeans;
    private final ThreadMXBean threadBean;
    private final long[] gcCountsAtStart;
    private final long[] gcTimesAtStart;
    private final MemoryUsage heapAtStart;
    private final int threadsAtStart;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final double[] cpuSum = new double[] {0.0d};
    private final AtomicLong cpuSamples = new AtomicLong();
    private final AtomicLong heapPeak = new AtomicLong();
    private final AtomicLong blockedSamples = new AtomicLong();
    private final Thread sampler;
    private final long startNanos;

    ResourceWindow() {
      osBean =
          (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      memoryBean = ManagementFactory.getMemoryMXBean();
      gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
      threadBean = ManagementFactory.getThreadMXBean();
      if (threadBean.isThreadContentionMonitoringSupported()) {
        threadBean.setThreadContentionMonitoringEnabled(true);
      }
      gcCountsAtStart =
          gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).toArray();
      gcTimesAtStart =
          gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).toArray();
      heapAtStart = memoryBean.getHeapMemoryUsage();
      threadsAtStart = threadBean.getThreadCount();
      startNanos = System.nanoTime();
      Runnable sampleLoop =
          () -> {
            while (running.get()) {
              double load = osBean.getProcessCpuLoad();
              if (load >= 0) {
                synchronized (cpuSum) {
                  cpuSum[0] += load;
                }
                cpuSamples.incrementAndGet();
              }
              long used = memoryBean.getHeapMemoryUsage().getUsed();
              heapPeak.accumulateAndGet(used, Math::max);
              try {
                blockedSamples.addAndGet(
                    Arrays.stream(threadBean.getThreadInfo(threadBean.getAllThreadIds()))
                        .filter(Objects::nonNull)
                        .filter(info -> info.getThreadState() == Thread.State.BLOCKED)
                        .count());
              } catch (RuntimeException ignored) {
                // 线程快照竞争失败按未采到处理
              }
              try {
                Thread.sleep(50);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
              }
            }
          };
      sampler = new Thread(sampleLoop, "rephot-resource-sampler");
      sampler.setDaemon(true);
      sampler.start();
    }

    static ResourceWindow start() {
      return new ResourceWindow();
    }

    ResourceSample stop() {
      running.set(false);
      try {
        sampler.join(2_000);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
      long totalGcCount = 0;
      long totalGcTime = 0;
      for (int i = 0; i < gcBeans.size(); i++) {
        totalGcCount += Math.max(0, gcBeans.get(i).getCollectionCount() - gcCountsAtStart[i]);
        totalGcTime += Math.max(0, gcBeans.get(i).getCollectionTime() - gcTimesAtStart[i]);
      }
      MemoryUsage heapAtEnd = memoryBean.getHeapMemoryUsage();
      double cpuAvg;
      synchronized (cpuSum) {
        cpuAvg = cpuSamples.get() > 0 ? cpuSum[0] / cpuSamples.get() : -1.0d;
      }
      return new ResourceSample(
          (System.nanoTime() - startNanos) / 1e9,
          cpuAvg,
          cpuSamples.get(),
          heapAtStart,
          heapAtEnd,
          heapPeak.get(),
          threadsAtStart,
          threadBean.getThreadCount(),
          blockedSamples.get(),
          totalGcCount,
          totalGcTime,
          threadBean.isThreadContentionMonitoringEnabled());
    }
  }

  /** 单窗口资源采样结果（同窗口径；容器资源由外部 docker stats 记录，取不到记未知）。 */
  private record ResourceSample(
      double windowSeconds,
      double cpuLoadAvg,
      long cpuSamples,
      MemoryUsage heapAtStart,
      MemoryUsage heapAtEnd,
      long heapPeakUsed,
      int threadsAtStart,
      int threadsAtEnd,
      long blockedThreadSamples,
      long gcCount,
      long gcTimeMs,
      boolean contentionMonitoring) {

    void print(String phase, int round, int concurrency) {
      String cpu = cpuLoadAvg >= 0 ? String.format(Locale.ROOT, "%.3f", cpuLoadAvg) : "unknown";
      printf(
          "REPHOT %s r%d c%d cpu: process_cpu_load_avg=%s samples=%d container_cpu_mem=unknown-docker-stats-not-wired",
          phase, round, concurrency, cpu, cpuSamples);
      printf(
          "REPHOT %s r%d c%d heap: used_start_mb=%.1f used_end_mb=%.1f peak_used_mb=%.1f"
              + " committed_end_mb=%.1f max_mb=%s",
          phase,
          round,
          concurrency,
          heapAtStart.getUsed() / 1e6,
          heapAtEnd.getUsed() / 1e6,
          heapPeakUsed / 1e6,
          heapAtEnd.getCommitted() / 1e6,
          heapAtEnd.getMax() < 0 ? "unknown" : String.valueOf(heapAtEnd.getMax() / 1e6));
      printf(
          "REPHOT %s r%d c%d gc: collections=%d pause_total_ms=%d",
          phase, round, concurrency, gcCount, gcTimeMs);
      printf(
          "REPHOT %s r%d c%d threads: start=%d end=%d blocked_state_samples=%d"
              + " contention_monitor=%s lock_wait_time=unknown",
          phase,
          round,
          concurrency,
          threadsAtStart,
          threadsAtEnd,
          blockedThreadSamples,
          contentionMonitoring);
    }
  }

  // ---------------------------------------------------------------- 一次性容器等待与状态核对

  private static void awaitQdrantReady(GenericContainer<?> qdrant) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String url = "http://" + qdrant.getHost() + ":" + qdrant.getMappedPort(6333) + "/readyz";
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
    throw new IllegalStateException("未测：Qdrant 容器 30s 内未就绪（readyz 不通过）", last);
  }

  private static long countRows(Environment env, String table) throws SQLException {
    try (Connection connection = env.pooledDataSource().rawConnection();
        PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  /** 摄取样本复位（计时窗口之外）：物理删切片行 + 物理删文件行 + 生产 Qdrant 按文档删点。 */
  private void resetDocument(Environment env, String documentId) {
    if (documentId == null || documentId.isBlank()) {
      return;
    }
    try (Connection connection = env.pooledDataSource().rawConnection();
        PreparedStatement deleteChunks =
            connection.prepareStatement("DELETE FROM document_vector_chunk WHERE document_id = ?");
        PreparedStatement deleteFiles =
            connection.prepareStatement("DELETE FROM uploaded_file WHERE document_id = ?")) {
      deleteChunks.setString(1, documentId);
      deleteChunks.executeUpdate();
      deleteFiles.setString(1, documentId);
      deleteFiles.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("摄取样本复位失败：" + documentId, exception);
    }
    env.rawStore().deleteByDocumentId(documentId);
  }

  // ---------------------------------------------------------------- 计数器

  /** 全部计时装饰器共享的分段计数器（并发安全；窗口语义 = reset 后的绝对值）。 */
  static final class Counters {
    static final int AUTH_SQL_CALLS = 0;
    static final int AUTH_SQL_NANOS = 1;
    static final int SQL_CALLS = 2;
    static final int SQL_NANOS = 3;
    static final int FULLTEXT_CALLS = 4;
    static final int FULLTEXT_NANOS = 5;
    static final int SELECT_BY_ID_CALLS = 6;
    static final int SELECT_BY_ID_NANOS = 7;
    static final int SELECT_LIST_CALLS = 8;
    static final int SELECT_LIST_NANOS = 9;
    static final int INSERT_CHILD_CALLS = 10;
    static final int INSERT_PARENT_CALLS = 11;
    static final int INSERT_NANOS = 12;
    static final int FILE_SQL_CALLS = 13;
    static final int FILE_SQL_NANOS = 14;
    static final int CONN_CALLS = 15;
    static final int CONN_NANOS = 16;
    static final int EMBED_CALLS = 17;
    static final int EMBED_TEXTS = 18;
    static final int EMBED_NANOS = 19;
    static final int REWRITE_CALLS = 20;
    static final int REWRITE_NANOS = 21;
    static final int QDRANT_SEARCH_CALLS = 22;
    static final int QDRANT_SEARCH_NANOS = 23;
    static final int QDRANT_UPSERT_CALLS = 24;
    static final int QDRANT_UPSERT_NANOS = 25;
    static final int QDRANT_DELETE_CALLS = 26;
    static final int QDRANT_DELETE_NANOS = 35;
    static final int FUSE_CALLS = 27;
    static final int FUSE_NANOS = 28;
    static final int RERANK_CALLS = 29;
    static final int RERANK_NANOS = 30;
    static final int CONTEXT_CALLS = 31;
    static final int CONTEXT_NANOS = 32;
    static final int PARSE_CALLS = 33;
    static final int PARSE_NANOS = 34;
    static final int COUNTER_COUNT = 36;

    private final LongAdder[] values = new LongAdder[COUNTER_COUNT];
    private final List<String> searchedKbIds = Collections.synchronizedList(new ArrayList<>());

    Counters() {
      for (int i = 0; i < COUNTER_COUNT; i++) {
        values[i] = new LongAdder();
      }
    }

    void add(int index, long amount) {
      values[index].add(amount);
    }

    void reset() {
      for (LongAdder value : values) {
        value.reset();
      }
      searchedKbIds.clear();
    }

    long[] snapshot() {
      long[] out = new long[COUNTER_COUNT];
      for (int i = 0; i < COUNTER_COUNT; i++) {
        out[i] = values[i].sum();
      }
      return out;
    }

    List<String> searchedKbIds() {
      synchronized (searchedKbIds) {
        return List.copyOf(searchedKbIds);
      }
    }

    void recordSearchedKb(Object kbId) {
      searchedKbIds.add(String.valueOf(kbId));
    }
  }

  // ---------------------------------------------------------------- 计时装饰器

  /** 会话按调用开关的生产 mapper 分发代理（线程安全，等价 SqlSessionTemplate 形状）。 */
  private static <T> T dispatch(SqlSessionFactory factory, Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (Object proxy, Method method, Object[] args) -> {
              if (method.getName().equals("toString")) {
                return type.getSimpleName() + "@rephot-dispatch";
              }
              try (SqlSession session = factory.openSession(true)) {
                return method.invoke(session.getMapper(type), args);
              }
            }));
  }

  /** 生产 mapper 计时代理：SQL 总分段 + 授权/切片/文件分桶 + 语句细粒度计数。 */
  private static <T> T timed(Class<T> type, Object delegate, Counters counters) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (Object proxy, Method method, Object[] args) -> {
              if (method.getName().equals("toString")) {
                return type.getSimpleName() + "@rephot-timed";
              }
              boolean authBucket =
                  type == KnowledgeBaseMapper.class || type == KnowledgeBaseMemberMapper.class;
              boolean fileBucket = type == UploadedFileMapper.class;
              long start = System.nanoTime();
              try {
                return method.invoke(delegate, args);
              } finally {
                long nanos = System.nanoTime() - start;
                counters.add(Counters.SQL_CALLS, 1);
                counters.add(Counters.SQL_NANOS, nanos);
                if (authBucket) {
                  counters.add(Counters.AUTH_SQL_CALLS, 1);
                  counters.add(Counters.AUTH_SQL_NANOS, nanos);
                }
                if (fileBucket) {
                  counters.add(Counters.FILE_SQL_CALLS, 1);
                  counters.add(Counters.FILE_SQL_NANOS, nanos);
                }
                switch (method.getName()) {
                  case "fulltextSearch" -> {
                    counters.add(Counters.FULLTEXT_CALLS, 1);
                    counters.add(Counters.FULLTEXT_NANOS, nanos);
                  }
                  case "selectById" -> {
                    // 只有切片快照表的 selectById 才是父块展开；knowledge_base selectById 是摄取的库读取
                    if (type == DocumentVectorChunkMapper.class) {
                      counters.add(Counters.SELECT_BY_ID_CALLS, 1);
                      counters.add(Counters.SELECT_BY_ID_NANOS, nanos);
                    }
                  }
                  case "selectList" -> {
                    // 只有切片快照表的 selectList 才是邻居查询；授权路径的 owner/PUBLIC/成员 selectList 记授权桶
                    if (type == DocumentVectorChunkMapper.class) {
                      counters.add(Counters.SELECT_LIST_CALLS, 1);
                      counters.add(Counters.SELECT_LIST_NANOS, nanos);
                    }
                  }
                  case "insert" -> {
                    if (type == DocumentVectorChunkMapper.class) {
                      Object arg = args == null || args.length == 0 ? null : args[0];
                      boolean parent =
                          arg instanceof DocumentVectorChunkEntity entity
                              && "PARENT".equalsIgnoreCase(entity.getChunkRole());
                      counters.add(
                          parent ? Counters.INSERT_PARENT_CALLS : Counters.INSERT_CHILD_CALLS, 1);
                      counters.add(Counters.INSERT_NANOS, nanos);
                    }
                  }
                  default -> {
                    // 其余方法只进 SQL 总段，不单列
                  }
                }
              }
            }));
  }

  /** 连接池计时：getConnection 是 SQL 分段的嵌套子段（包含关系，重叠口径）。 */
  private static final class PooledTimingDataSource implements DataSource {
    private final PooledDataSource delegate;
    private final Counters counters;

    PooledTimingDataSource(
        String driver, String url, String user, String password, Counters counters) {
      this.delegate = new PooledDataSource(driver, url, user, password);
      this.counters = counters;
    }

    Connection rawConnection() throws SQLException {
      return DriverManager.getConnection(
          delegate.getUrl(), delegate.getUsername(), delegate.getPassword());
    }

    @Override
    public Connection getConnection() throws SQLException {
      long start = System.nanoTime();
      try {
        return delegate.getConnection();
      } finally {
        counters.add(Counters.CONN_CALLS, 1);
        counters.add(Counters.CONN_NANOS, System.nanoTime() - start);
      }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return delegate.getConnection(username, password);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
      return delegate.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return delegate.getParentLogger();
    }
  }

  /** Qdrant 计时装饰器：search/upsertAll 分桶（生产 gRPC 客户端原样在下层）。 */
  private static final class TimedVectorStore implements CrmVectorStore {
    private final CrmVectorStore delegate;
    private final Counters counters;

    TimedVectorStore(CrmVectorStore delegate, Counters counters) {
      this.delegate = delegate;
      this.counters = counters;
    }

    @Override
    public void upsert(VectorRecord record) {
      delegate.upsert(record);
    }

    @Override
    public void upsertAll(List<VectorRecord> records) {
      long start = System.nanoTime();
      try {
        delegate.upsertAll(records);
      } finally {
        counters.add(Counters.QDRANT_UPSERT_CALLS, 1);
        counters.add(Counters.QDRANT_UPSERT_NANOS, System.nanoTime() - start);
      }
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
      counters.add(Counters.QDRANT_SEARCH_CALLS, 1);
      counters.recordSearchedKb(request.filter().get("knowledgeBaseId"));
      long start = System.nanoTime();
      try {
        return delegate.search(request);
      } finally {
        counters.add(Counters.QDRANT_SEARCH_NANOS, System.nanoTime() - start);
      }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
      long start = System.nanoTime();
      try {
        delegate.deleteByDocumentId(documentId);
      } finally {
        counters.add(Counters.QDRANT_DELETE_CALLS, 1);
        counters.add(Counters.QDRANT_DELETE_NANOS, System.nanoTime() - start);
      }
    }
  }

  /** 本地确定性模型桩：embed=字符分桶 32 维（同旧基线），chat=即时空串（改写回退原查询）。绝不读环境密钥或仓库 .env。 */
  static final class StubModelProvider implements ModelProvider {
    static final String STUB_NAME = "representative-hotpath-stub";

    private final Counters counters;

    StubModelProvider(Counters counters) {
      this.counters = counters;
    }

    @Override
    public String provider() {
      return STUB_NAME;
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      counters.add(Counters.REWRITE_CALLS, 1);
      long start = System.nanoTime();
      try {
        return ModelCallResult.ofText("", STUB_NAME, null, null, null);
      } finally {
        counters.add(Counters.REWRITE_NANOS, System.nanoTime() - start);
      }
    }

    @Override
    public ModelCallResult<String> chat(
        Prompt prompt, com.slz.crm.platform.contract.ModelCallOptions options) {
      return chat(prompt);
    }

    @Override
    public Flux<org.springframework.ai.chat.model.ChatResponse> streamChat(Prompt prompt) {
      throw new UnsupportedOperationException("代表性热路径度量不调用流式模型");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      List<String> texts = request.getInstructions();
      long start = System.nanoTime();
      try {
        StringBuilder joined = new StringBuilder();
        for (String text : texts) {
          joined.append(text);
        }
        float[] vector = bucketVector(joined.toString());
        long promptTokens = joined.length();
        return ModelCallResult.ofVector(vector, STUB_NAME, promptTokens, promptTokens);
      } finally {
        counters.add(Counters.EMBED_CALLS, 1);
        counters.add(Counters.EMBED_TEXTS, texts.size());
        counters.add(Counters.EMBED_NANOS, System.nanoTime() - start);
      }
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      throw new UnsupportedOperationException("代表性热路径度量不调用视觉模型");
    }

    /** 确定性向量：字符 codePoint 对 32 取模分桶累加后 L2 归一化（与旧基线同款）。 */
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

  /** 重排段计时（生产 DefaultWeightedReranker 之上）。 */
  private final class TimedReranker extends DefaultWeightedReranker {
    private TimedReranker(Bm25Scorer scorer, ObjectProvider<DynamicConfigService> provider) {
      super(scorer, provider);
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
      long start = System.nanoTime();
      try {
        return super.rerank(query, candidates);
      } finally {
        counters.add(Counters.RERANK_CALLS, 1);
        counters.add(Counters.RERANK_NANOS, System.nanoTime() - start);
      }
    }
  }

  /** 上下文段计时（生产 ContextBuilder 之上）。 */
  private final class TimedContextBuilder extends ContextBuilder {
    private TimedContextBuilder(
        DocumentVectorChunkMapper mapper,
        ObjectProvider<DynamicConfigService> provider,
        RuleContextCompressor compressor,
        com.slz.crm.knowledge.retrieval.LlmContextCompressor llmCompressor) {
      super(mapper, provider, compressor, llmCompressor);
    }

    @Override
    public String build(List<RetrievalCandidate> candidates) {
      long start = System.nanoTime();
      try {
        return super.build(candidates);
      } finally {
        counters.add(Counters.CONTEXT_CALLS, 1);
        counters.add(Counters.CONTEXT_NANOS, System.nanoTime() - start);
      }
    }
  }

  /** RRF 融合段计时（生产 RrfFusion 之上）。 */
  private final class TimedRrfFusion extends RrfFusion {
    @Override
    public List<RetrievalCandidate> fuse(
        List<RetrievalCandidate> vectorCandidates,
        List<RetrievalCandidate> sparseCandidates,
        int k) {
      long start = System.nanoTime();
      try {
        return super.fuse(vectorCandidates, sparseCandidates, k);
      } finally {
        counters.add(Counters.FUSE_CALLS, 1);
        counters.add(Counters.FUSE_NANOS, System.nanoTime() - start);
      }
    }

    @Override
    public List<RetrievalCandidate> fuseAll(List<List<RetrievalCandidate>> routes, int k) {
      long start = System.nanoTime();
      try {
        return super.fuseAll(routes, k);
      } finally {
        counters.add(Counters.FUSE_CALLS, 1);
        counters.add(Counters.FUSE_NANOS, System.nanoTime() - start);
      }
    }
  }

  /** 摄取解析/切分段计时（生产 DocumentService 之上）。 */
  private final class TimedDocumentService extends DocumentService {
    @Override
    public List<DocumentChunk> process(InputStream content, String filename, String category)
        throws Exception {
      long start = System.nanoTime();
      try {
        return super.process(content, filename, category);
      } finally {
        counters.add(Counters.PARSE_CALLS, 1);
        counters.add(Counters.PARSE_NANOS, System.nanoTime() - start);
      }
    }
  }

  /** 装配结果引用集。 */
  private record Environment(
      KnowledgeRetrievalPort retrieval,
      DocumentIngestionService ingestion,
      QdrantVectorStore rawStore,
      PooledTimingDataSource pooledDataSource) {}

  // ---------------------------------------------------------------- 工具

  private static boolean jacocoAgentAttached() {
    return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .anyMatch(argument -> argument.contains("jacoco"));
  }

  private static double percentileMillis(long[] nanos, double p) {
    long[] sorted = nanos.clone();
    Arrays.sort(sorted);
    int index = (int) Math.ceil(p * sorted.length) - 1;
    index = Math.max(0, Math.min(sorted.length - 1, index));
    return sorted[index] / 1_000_000.0d;
  }

  private static void printf(String format, Object... args) {
    System.out.printf(Locale.ROOT, format + "%n", args);
  }
}
