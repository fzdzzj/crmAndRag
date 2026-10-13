package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.ai.port.BusinessRetrievalPort;
import com.slz.crm.server.service.DataScopeService;
import com.slz.crm.server.service.impl.BusinessRetrievalService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * customer 检索面真跑质量基准 runner（add-customer-retrieval-baseline 任务 3.1 / 3.2 / 3.3，failsafe IT）。
 *
 * <p><b>门控</b>：与 {@code RagRealRetrievalBenchmarkIT} 同款——{@code RAG_BENCHMARK_REAL=1} 且存在 {@code
 * DASHSCOPE_API_KEY}（env 或仓库根 .env）才执行；否则按假设跳过、构建不失败（默认 CI 与本地 verify 恒不发真实外网请求）。
 *
 * <p><b>执行内容</b>：固定 customer 语料（16 行真实感客户，字段口径对齐 {@code CustomerSemanticTextSupport}） 经生产摄取链 {@link
 * CustomerIngestionService#ingest} 真嵌入落内存 crm_entity 向量库 → 真 {@link BusinessRetrievalService}（评测上下文
 * ALL 档——权限面不混入质量评测，数据范围桩恒返 ALL）→ 10 例 customer 检索用例（语义匹配 行业/规模/区域 + 词汇失配 同义不同词，对齐
 * expand-rag-benchmark-mismatch 的 MISMATCH 口径）→ {@link RagQualityEvaluator} 判卷（recall/MRR/hit）→
 * JSON 落盘 {@code docs/rag-quality/customer-baseline-v1.json}。
 *
 * <p><b>口径说明</b>（D2 首跑不锚）：customer 用例独立编号（C-01…C-10），不并入知识库 63 例 fixture， 不触碰 {@code
 * RagBenchmarkSuite.SUITE_VERSION}；首跑结果只留痕不锚 fixture，观察一轮真实数据质量后 再决定锚入基线（裁决假设 5）。expectedChunkIds
 * 对应 crm_entity 向量 ID（{@code CUSTOMER_COMPANY:{id}}， 与生产摄取链同源）；评测上下文用 ALL 档（DataScopeLevel.ALL
 * 桩）——权限闸面由 {@code BusinessRetrievalServiceTest} 单测矩阵另行覆盖，不混入本基准。
 *
 * <p><b>token 计量</b>：语料真嵌入经 {@code EmbeddingService.embedWithUsage} 单独计量一遍（生产摄取链 {@code ingest} 内部走
 * {@code embed()} 不回流 usage，评测计量另跑一遍，成本量级分厘级，属本卡授权 评测链路外呼），实测 totalTokens 落盘——供任务组 2 回埋
 * token/成本留痕作「每字 token」校准口径。
 */
class CustomerRetrievalBenchmarkIT {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** compatible-mode 端点（与 RagRealRetrievalBenchmarkIT 相同的装配方式）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** customer 评测套件独立版本号（不并入知识库 SUITE_VERSION，独立编号）。 */
  private static final String CUSTOMER_SUITE_VERSION = "CUSTOMER-SUITE-1.0";

  /** 评测上下文（ALL 档桩依赖 userId/roleId/deptId 三元组，取值不影响检索）。 */
  private static final long EVAL_USER_ID = 99002L;

  /** recall@k / precision@k / MRR 的截断排名 = 生产默认 topK（对齐知识库基准）。 */
  private static final int TOP_K = 5;

  /** customer 首跑报告默认落盘路径；可用 {@code -Drag.customer.benchmark.out} 覆盖。 */
  private static final Path DEFAULT_REPORT_PATH =
      Path.of("docs", "rag-quality", "customer-baseline-v1.json");

  private static final String OUT_PROP = "rag.customer.benchmark.out";

  /** 语料行：字段口径对齐 CustomerCompanyEntity（摄取链直接消费）。 */
  private record CorpusRow(
      long id,
      String companyName,
      String industry,
      String address,
      String customerType,
      String description) {}

  /** 固定语料（16 行；查询相关语义全部落在 行业/地址/描述 字段）。 */
  private static final List<CorpusRow> CORPUS =
      List.of(
          new CorpusRow(
              2001L,
              "苏州精工数控设备有限公司",
              "高端装备制造",
              "江苏省苏州市工业园区",
              "直销",
              "主营五轴精密数控机床与加工中心的研发制造，年营收约1亿以上，为航空航天与汽车模具行业提供高精度加工装备"),
          new CorpusRow(
              2002L,
              "杭州云栖医疗软件有限公司",
              "医疗健康",
              "浙江省杭州市余杭区",
              "直销",
              "专注医院信息化系统与区域医疗平台建设，提供HIS/EMR等核心软件产品，服务三级医院超百家"),
          new CorpusRow(
              2003L,
              "深圳前海慧科金融科技有限公司",
              "金融科技",
              "广东省深圳市南山区",
              "直销",
              "为持牌金融机构提供风控建模与支付清算系统，团队规模200-500人，总部位于深圳前海"),
          new CorpusRow(
              2004L,
              "宁波东方储能科技有限公司",
              "新能源与储能",
              "浙江省宁波市北仑区",
              "直销",
              "从事电化学储能电站的系统集成与运营，承接电网侧调频调峰项目，具备百兆瓦级交付能力"),
          new CorpusRow(
              2005L,
              "无锡智链物流科技有限公司",
              "智慧物流",
              "江苏省无锡市新吴区",
              "直销",
              "研发仓储分拣机器人与柔性输送系统，团队规模500-1000人，为电商与快递客户提供自动化分拣解决方案"),
          new CorpusRow(
              2006L,
              "佛山瑞升清洁机器人有限公司",
              "高端装备制造",
              "广东省佛山市顺德区",
              "代理",
              "主营家用清洁机器人整机研发与销售，产品覆盖扫拖一体机型，渠道以电商直销为主"),
          new CorpusRow(
              2007L,
              "上海跨贸云软件有限公司",
              "软件与信息服务",
              "上海市浦东新区",
              "直销",
              "提供跨境贸易管理软件与海外仓结算系统，帮助出口企业完成订单、物流、清关全链路数字化"),
          new CorpusRow(
              2008L,
              "东莞华塑模具科技有限公司",
              "高端装备制造",
              "广东省东莞市松山湖",
              "代理",
              "塑料成型模具专业制造商，覆盖汽车内外饰件与家电壳体模具，具备模流分析与试模验证能力"),
          new CorpusRow(
              2009L,
              "合肥中科激光技术有限公司",
              "高端装备制造",
              "安徽省合肥市高新区",
              "直销",
              "研发高功率激光切割设备与激光焊接工作站，年营收约2000-5000万，服务钣金加工行业客户"),
          new CorpusRow(
              2010L,
              "天津海工重型装备有限公司",
              "高端装备制造",
              "天津市滨海新区",
              "直销",
              "制造港口起重机械与海洋工程重型装备，团队规模1000人以上，承接大型钢结构总包项目"),
          new CorpusRow(
              2011L,
              "广州岭南生鲜供应链有限公司",
              "智慧物流",
              "广东省广州市天河区",
              "直销",
              "专注生鲜冷链配送与城配网络建设，覆盖华南区域商超与餐饮客户，自有冷链车队五百余台"),
          new CorpusRow(
              2012L,
              "北京恒远数据安全有限公司",
              "软件与信息服务",
              "北京市海淀区",
              "直销",
              "提供数据脱敏、审计与合规检测软件，服务金融与政务客户，通过等保三级测评"),
          new CorpusRow(
              2013L,
              "上海安吉汽车零部件有限公司",
              "高端装备制造",
              "上海市嘉定区",
              "直销",
              "汽车底盘与传动系统零部件供应商，为整车厂提供转向节与差速器壳体等精密铸件"),
          new CorpusRow(
              2014L,
              "成都蜀锦智能穿戴有限公司",
              "互联网科技",
              "四川省成都市高新区",
              "直销",
              "研发智能手表与运动健康监测设备，团队规模50-200人，主打千元级大众市场"),
          new CorpusRow(
              2015L,
              "青岛海岳风电叶片有限公司",
              "新能源与储能",
              "山东省青岛市黄岛区",
              "直销",
              "制造大型风电叶片与塔筒法兰，年营收约5000万-1亿，配套海上风电项目交付"),
          new CorpusRow(
              2016L,
              "武汉光谷半导体材料有限公司",
              "互联网科技",
              "湖北省武汉市东湖高新区",
              "直销",
              "生产半导体硅片与湿电子化学品，服务晶圆代工厂，洁净车间产能持续扩建中"));

  /** 10 例 customer 检索用例：C-01~C-07 语义匹配（行业/规模/区域），C-08~C-10 词汇失配（同义不同词）。 */
  private static List<RagBenchmarkCase> suite() {
    List<RagBenchmarkCase> cases = new ArrayList<>();
    cases.add(caseOf("C-01", RagBenchmarkCase.Category.TEXT, "苏州工业园区做五轴精密数控机床的高端装备制造客户有哪些", 2001L));
    cases.add(caseOf("C-02", RagBenchmarkCase.Category.TEXT, "杭州做医院信息化系统的医疗健康企业", 2002L));
    cases.add(caseOf("C-03", RagBenchmarkCase.Category.TEXT, "深圳南山做支付清算的金融科技公司", 2003L));
    cases.add(caseOf("C-04", RagBenchmarkCase.Category.TEXT, "宁波做储能电站系统集成的企业", 2004L));
    cases.add(caseOf("C-05", RagBenchmarkCase.Category.TEXT, "五百人以上规模的仓储分拣自动化物流供应商", 2005L));
    cases.add(caseOf("C-06", RagBenchmarkCase.Category.TEXT, "合肥做激光切割焊接设备的厂商", 2009L));
    cases.add(caseOf("C-07", RagBenchmarkCase.Category.TEXT, "广州做生鲜冷链配送的物流公司", 2011L));
    // 词汇失配（同义不同词，对齐 MISMATCH 口径）：question 与 gold 文本词面重叠≈空，考察语义桥接能力
    cases.add(caseOf("C-08", RagBenchmarkCase.Category.MISMATCH, "找能做扫地机器人整机的代工工厂", 2006L));
    cases.add(caseOf("C-09", RagBenchmarkCase.Category.MISMATCH, "跨境电商ERP软件哪家做得成熟", 2007L));
    cases.add(caseOf("C-10", RagBenchmarkCase.Category.MISMATCH, "需要一家能开注塑模具的供应商", 2008L));
    return cases;
  }

  private static RagBenchmarkCase caseOf(
      String id, RagBenchmarkCase.Category category, String question, long goldId) {
    return new RagBenchmarkCase(
        id, category, question, Set.of("CUSTOMER_COMPANY:" + goldId), List.of(), true);
  }

  @Test
  void runCustomerRetrievalBenchmarkAndWriteFirstReport() throws Exception {
    Assumptions.assumeTrue(
        "1".equals(System.getenv("RAG_BENCHMARK_REAL")),
        "未设置 RAG_BENCHMARK_REAL=1，跳过 customer 真检索基准（默认 CI 不发外网请求）");
    String apiKey = resolveApiKey();
    Assumptions.assumeTrue(
        apiKey != null && !apiKey.isBlank(), "未配置 DASHSCOPE_API_KEY（env 或 .env），跳过 customer 真检索基准");

    ModelProvider provider = buildProvider(apiKey);
    ModelProviderProperties properties = new ModelProviderProperties();
    EmbeddingService embeddingService = new EmbeddingService(provider, properties);
    InMemoryVectorStore store = new InMemoryVectorStore();
    CrmEntityVectorStore entityStore = new CrmEntityVectorStore(store);

    // 1) 语料经生产摄取链真嵌入（CustomerIngestionService 拼文本/建 payload/upsert 全复用；owner 置空避免 mapper 依赖）
    CustomerIngestionService ingestion =
        new CustomerIngestionService(embeddingService, entityStore, null, null, null);
    long tokenMeter = 0L;
    long charMeter = 0L;
    for (CorpusRow row : CORPUS) {
      ingestion.ingest(toEntity(row));
      // token 计量：embedWithUsage 单独一遍（生产 ingest 内部 embed() 不回流 usage，评测计量属授权外呼）
      ModelCallResult<float[]> measured = embeddingService.embedWithUsage(textOf(row));
      if (measured.totalTokens() != null) {
        tokenMeter += measured.totalTokens();
      }
      charMeter += textOf(row).length();
    }
    System.out.println(
        "[rag-benchmark] customer 语料入库完成：corpus="
            + CORPUS.size()
            + " embedTokens="
            + tokenMeter
            + " chars="
            + charMeter);

    // 2) 真 BusinessRetrievalService + ALL 档数据范围桩（评测上下文 ALL——权限面不混入质量评测）
    BusinessRetrievalService retrieval =
        new BusinessRetrievalService(entityStore, embeddingService, allScopeStub(), null, null);
    UserContext evaluator =
        new UserContext(EVAL_USER_ID, 5L, 101L, DataScopeLevel.NONE, "customer-benchmark-runner");
    List<RagBenchmarkCase> suite = suite();

    Report report =
        RagQualityEvaluator.evaluate(suite, c -> evaluateCase(retrieval, evaluator, c), TOP_K);
    assertEquals(suite.size(), report.metrics().caseCount(), "每条 customer 用例都必须有评分");
    assertTrue(report.metrics().hitRate() > 0.0, "整轮零命中说明 customer 检索链路或语料装配有问题");
    System.out.println("[rag-benchmark] customer metrics=" + report.metrics());

    // 3) 首跑留痕落盘（不锚 fixture：D2 只记录不锚定）
    Path outPath =
        System.getProperty(OUT_PROP) == null || System.getProperty(OUT_PROP).isBlank()
            ? DEFAULT_REPORT_PATH
            : Path.of(System.getProperty(OUT_PROP));
    writeReport(outPath, report, suite, tokenMeter, charMeter);
    System.out.println("[rag-benchmark] customer 首跑报告已落盘: " + outPath.toAbsolutePath());
  }

  /** 单条 customer 用例：真检索（真嵌入 + 内存 crm_entity 召回），纯检索判卷（无答案生成）。 */
  private CaseOutcome evaluateCase(
      BusinessRetrievalService retrieval, UserContext evaluator, RagBenchmarkCase c) {
    long startedAt = System.currentTimeMillis();
    BusinessRetrievalPort.BusinessRetrievalResult result =
        retrieval.retrieve(
            new BusinessRetrievalPort.BusinessRetrievalQuery(c.question(), evaluator, TOP_K, 1));
    long elapsed = System.currentTimeMillis() - startedAt;
    List<String> retrieved =
        result.sources() == null
            ? List.of()
            : result.sources().stream().map(SourceReference::chunkId).toList();
    // 纯检索判卷：无引用、无答案要点（answerPointCoverage=1 中立，不参与 customer 质量主指标 recall/MRR/hit）
    return new CaseOutcome(retrieved, List.of(), 1.0d, elapsed, elapsed, 0, true);
  }

  /** ALL 档数据范围桩：getHighestDataScopeLevel 恒返 ALL，其余方法不参与本基准路径（空实现）。 */
  private DataScopeService allScopeStub() {
    return new DataScopeService() {
      @Override
      public Integer getHighestDataScopeLevel(RoleAO user, String resourceType) {
        return DataScopeLevel.ALL.getCode();
      }

      @Override
      public void addDataScopeCondition(
          com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> wrapper,
          RoleAO user,
          String resourceType) {
        // 本基准不消费列表 SQL 条件
      }

      @Override
      public void addDataScopeCondition(
          com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> wrapper,
          RoleAO user,
          String resourceType) {
        // 本基准不消费列表 SQL 条件
      }

      @Override
      public List<Long> getTageResourceIds(Long roleId, String resourceType) {
        return List.of();
      }

      @Override
      public List<Long> getSharedResourceIds(Long userId, Long roleId, String resourceType) {
        return List.of();
      }

      @Override
      public boolean canReadResource(
          RoleAO user, String resourceType, Long resourceId, Long... relatedUserIds) {
        return true;
      }
    };
  }

  /** 语料行 → 实体（owner 置空：不依赖 userMapper，payload 不含 ownerUserId/ownerDeptId 键）。 */
  private com.slz.crm.pojo.entity.CustomerCompanyEntity toEntity(CorpusRow row) {
    com.slz.crm.pojo.entity.CustomerCompanyEntity entity =
        new com.slz.crm.pojo.entity.CustomerCompanyEntity();
    entity.setId(row.id());
    entity.setCompanyName(row.companyName());
    entity.setIndustry(row.industry());
    entity.setAddress(row.address());
    entity.setCustomerType(row.customerType());
    entity.setDescription(row.description());
    entity.setGrade(5);
    return entity;
  }

  /** 与 CustomerSemanticTextSupport 同口径的语义文本（仅用于 token 计量，字段为语料行承载）。 */
  private String textOf(CorpusRow row) {
    StringBuilder sb = new StringBuilder();
    appendField(sb, "公司名称", row.companyName());
    appendField(sb, "所属行业", row.industry());
    appendField(sb, "客户属性", row.customerType());
    appendField(sb, "公司地址", row.address());
    appendField(sb, "公司描述", row.description());
    appendField(sb, "客户等级", String.valueOf(5));
    return sb.toString();
  }

  private static void appendField(StringBuilder sb, String label, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append("；");
    }
    sb.append(label).append(": ").append(value.trim());
  }

  /** 首跑报告落盘（customer 独立 JSON 形态，绝不触碰知识库基线文件）。 */
  private void writeReport(
      Path outPath, Report report, List<RagBenchmarkCase> suite, long tokenMeter, long charMeter)
      throws IOException {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("suiteVersion", CUSTOMER_SUITE_VERSION);
    root.put("generatedAt", Instant.now().toString());
    root.put("corpusSize", CORPUS.size());
    root.put("topK", TOP_K);
    root.put("contextScope", "ALL（权限面不混入质量评测）");
    root.put("suiteNote", "customer 用例独立编号 C-01~C-10，不并入知识库 63 例 fixture，SUITE_VERSION 不动；D2 首跑不锚");
    ObjectNode metrics = root.putObject("metrics");
    metrics.put("caseCount", report.metrics().caseCount());
    metrics.put("meanRecallAtK", report.metrics().meanRecallAtK());
    metrics.put("meanPrecisionAtK", report.metrics().meanPrecisionAtK());
    metrics.put("mrr", report.metrics().mrr());
    metrics.put("hitRate", report.metrics().hitRate());
    metrics.put("corpusEmbeddingTotalTokens", tokenMeter);
    metrics.put("corpusEmbeddingChars", charMeter);
    ArrayNode cases = root.putArray("cases");
    for (RagQualityReport.CaseScore score : report.cases()) {
      ObjectNode node = cases.addObject();
      node.put("id", score.id());
      node.put("category", score.category());
      RagBenchmarkCase c =
          suite.stream().filter(s -> s.id().equals(score.id())).findFirst().orElse(null);
      if (c != null) {
        node.put("question", c.question());
        ArrayNode golds = node.putArray("expectedChunkIds");
        golds.addAll((ArrayNode) MAPPER.valueToTree(new LinkedHashSet<>(c.expectedChunkIds())));
      }
      node.put("recallAtK", score.recallAtK());
      node.put("reciprocalRank", score.reciprocalRank());
      node.put("hit", score.hit());
    }
    Files.createDirectories(outPath.toAbsolutePath().getParent());
    Files.writeString(outPath, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
  }

  /**
   * 装配真实 Provider：DashScope 原生（chat/embed）+ compatible-mode（stream），同 RagRealRetrievalBenchmarkIT。
   */
  private ModelProvider buildProvider(String apiKey) {
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);

    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.ai.dashscope.api-key", apiKey);
    environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);

    ModelProviderProperties properties = new ModelProviderProperties();
    return new ModelProviderImpl(
        provider(nativeChat), provider(embeddingModel), properties, environment);
  }

  private <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  /** 读 key：优先环境变量，其次仓库根 .env（与 RagRealRetrievalBenchmarkIT 相同的解析顺序）。 */
  private String resolveApiKey() {
    String fromEnv = System.getenv("DASHSCOPE_API_KEY");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    Path dotEnv = Path.of(".env");
    if (Files.exists(dotEnv)) {
      try {
        return Files.readAllLines(dotEnv, StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("DASHSCOPE_API_KEY="))
            .map(line -> line.substring("DASHSCOPE_API_KEY=".length()).trim())
            .findFirst()
            .orElse(null);
      } catch (Exception ignored) {
        // 读不到就走"跳过"分支
      }
    }
    return null;
  }
}
