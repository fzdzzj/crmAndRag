package com.slz.crm.quality;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.ai.CitationAligner;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真检索质量基准 runner（add-rag-quality-baseline 任务 3.1–3.3，failsafe IT）。
 *
 * <p><b>门控</b>：{@code RAG_BENCHMARK_REAL=1} 且存在 {@code DASHSCOPE_API_KEY}（env 或仓库根 .env）
 * 才执行；否则按假设跳过、构建不失败——默认 CI 与本地 verify 恒不发真实外网请求
 * （沿用 runbook §6 的"无 key 不炸"原则）。</p>
 *
 * <p><b>执行内容</b>：fixtures 幂等入库（真嵌入）→ 黄金占位 id 对齐改写 → 真检索管线
 * （真查询改写 + 真嵌入 + InMemoryVectorStore 召回 + BM25 融合重排）→ 真模型生成带引用答案 →
 * 真模型判卷答案要点覆盖 → {@link RagQualityEvaluator} 汇总 → JSON 落盘
 * {@code docs/rag-quality/baseline-v1.json}，作为提案 2–5 的"不回退/提升"对照锚点。</p>
 *
 * <p><b>口径说明</b>：token 只计"答案生成 + 判卷"两段（检索侧改写/嵌入的 usage 被
 * {@code EmbeddingService}/{@code RetrievalQueryRewriteService} 吞掉，无法回收）；
 * TTFT 取流式答案首 chunk 时延；总延迟覆盖 检索+生成+判卷 全程。
 * 向量库用 InMemory（评测环境无 Qdrant 依赖），检索算法与生产同源
 * （{@link KnowledgeRetrievalServiceImpl} 全链路，仅授权换成评测桩）。</p>
 *
 * <p><b>取证旁路</b>（trace-i05-citation-forensics）：{@code -Drag.benchmark.only} 过滤单条；
 * {@code -Drag.benchmark.trace.out} 落盘对齐前后答案与 [n]/召回 excerpt。不改 CaseScore /
 * CitationAligner / citationPrecision 公式。单条报告禁止当锚点。</p>
 */
class RagRealRetrievalBenchmarkIT {

    /** compatible-mode 端点（与 ModelProviderImplDashScopeIT 相同的装配方式） */
    private static final String COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private static final long EVAL_KB_ID = 99001L;
    private static final long EVAL_USER_ID = 99001L;
    /** recall@k / precision@k / MRR 的截断排名 = 生产默认 topK */
    private static final int TOP_K = 5;

    /** 基线报告默认落盘路径（failsafe 工作目录 = 仓库根）；可用 {@code -Drag.benchmark.out} 覆盖。 */
    private static final Path DEFAULT_BASELINE_PATH = Path.of("docs", "rag-quality", "baseline-v1.json");
    /** 当跑 profile：{@code -Drag.benchmark.run=v1|hybrid|context|chunking|query}（默认 v1）。 */
    private static final String RUN_PROP = "rag.benchmark.run";
    /** 报告输出路径覆盖：{@code -Drag.benchmark.out=docs/rag-quality/<name>.json}。 */
    private static final String OUT_PROP = "rag.benchmark.out";
    /**
     * 用例 id 过滤（trace-i05-citation-forensics 任务 1.1）：{@code -Drag.benchmark.only=I-05}，
     * 逗号分隔多 id；未设置=全套。过滤后报告不是可比锚点。
     */
    static final String ONLY_PROP = "rag.benchmark.only";
    /**
     * 取证旁路 JSON（任务 1.2）：默认 {@code docs/rag-quality/i05-forensics.json}，
     * 可用 {@code -Drag.benchmark.trace.out} 覆盖。不进 CaseScore。
     */
    static final String TRACE_OUT_PROP = "rag.benchmark.trace.out";
    static final Path DEFAULT_TRACE_PATH = Path.of("docs", "rag-quality", "i05-forensics.json");
    /** excerpt 截断长度（字）。 */
    private static final int EXCERPT_LIMIT = 400;

    /** 本轮 evaluateCase 采集的取证旁路（不进 CaseScore）。 */
    private final List<ForensicCase> forensicCases = new ArrayList<>();

    private static final String KB_ANSWER_SYSTEM_PROMPT = """
            你是 CRM 知识库助手。仅依据下方编号资料回答用户问题，作答时用 [n] 标注所引用资料的编号。
            若资料不足以回答，直接说明未找到相关信息，不要编造资料之外的内容。
            """;

    private static final String CHAT_SYSTEM_PROMPT = "你是 CRM 助手，请自然友好地回应用户。";

    private static final String NO_HIT_SYSTEM_PROMPT = """
            你是 CRM 知识库助手。知识库中未检索到与问题相关的资料：
            如果无法回答，请直接说明未找到相关信息，不要编造。
            """;

    /** 答案"诚实拒答"的判别标记（D16：零命中时诚实说明而非编造） */
    private static final List<String> DECLINE_MARKS = List.of(
            "未找到", "没有找到", "无法回答", "没有相关", "未能找到", "资料不足", "无法确定");

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,2})]");
    private static final Pattern BOOLEAN_PATTERN = Pattern.compile("true|false", Pattern.CASE_INSENSITIVE);

    @Test
    void runRealRetrievalBenchmarkAndWriteBaselineReport() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("RAG_BENCHMARK_REAL")),
                "未设置 RAG_BENCHMARK_REAL=1，跳过真检索基准（默认 CI 不发外网请求）");
        String apiKey = resolveApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未配置 DASHSCOPE_API_KEY（env 或 .env），跳过真检索基准");

        ModelProvider provider = buildProvider(apiKey);
        ModelProviderProperties properties = new ModelProviderProperties();
        EmbeddingService embeddingService = new EmbeddingService(provider, properties);

        // profile 由 -Drag.benchmark.run 决定；当跑矩阵（DynamicConfig 桩）+ 输出路径据此装配
        RagBenchmarkRun run = RagBenchmarkRun.from(System.getProperty(RUN_PROP, "v1"));
        DynamicConfigService dynamicConfig = RagBenchmarkPipelineFactory.dynamicConfig(run.matrix);
        System.out.println("[rag-benchmark] run=" + run + " matrix=" + run.matrix);

        // 1) 数据准备：fixtures 幂等入库（真嵌入），黄金占位 id → 真实 chunkId（对齐失败显式报错）；
        //    DocumentService 走矩阵的 rag.chunking.strategy（fixed/semantic）
        InMemoryVectorStore store = new InMemoryVectorStore();
        RagBenchmarkDataPreparer.Preparation preparation = RagBenchmarkDataPreparer.prepare(
                store, embeddingService::embed, EVAL_KB_ID,
                new com.slz.crm.knowledge.document.DocumentService(
                        RagBenchmarkPipelineFactory.provider(dynamicConfig)));
        System.out.println("[rag-benchmark] 语料入库完成：chunks=" + preparation.chunkIds().size()
                + " goldens=" + preparation.goldenToChunkId().size());
        List<RagBenchmarkCase> suite = RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
        // trace-i05-citation-forensics 1.1：按 -Drag.benchmark.only 过滤（未知 id / 空套件失败）
        suite = applyOnlyFilter(suite, System.getProperty(ONLY_PROP));
        System.out.println("[rag-benchmark] suite size after only-filter=" + suite.size());

        // 2) 真检索管线：真改写 + 真嵌入 + 真召回融合重排（授权为评测桩：固定放行评测库）
        KnowledgeRetrievalPort retrievalPort = RagBenchmarkPipelineFactory.build(
                run, allowEvalKnowledgeBaseOnly(), embeddingService, store,
                dynamicConfig, provider, preparation.chunks());

        UserContext evaluator = new UserContext(EVAL_USER_ID, EVAL_USER_ID + 1, EVAL_USER_ID + 2,
                DataScopeLevel.NONE, "rag-benchmark-runner");
        Report report;
        forensicCases.clear();
        try {
            UserContextHolder.set(evaluator);
            report = RagQualityEvaluator.evaluate(suite, c -> evaluateCase(retrievalPort, provider, c), TOP_K);
        } finally {
            UserContextHolder.clear();
        }

        // 3) 结果校验 + 落盘（版本/时间戳由评估器写入报告）
        assertEquals(suite.size(), report.metrics().caseCount(), "每条用例都必须有评分");
        assertEquals(RagBenchmarkSuite.SUITE_VERSION, report.suiteVersion());
        assertTrue(report.metrics().failureRate() <= 0.25,
                "失败用例过多（" + report.metrics().failureRate() + "），基线不可信");
        assertTrue(report.metrics().hitRate() > 0.0, "整轮零命中说明检索链路或语料装配有问题");

        Path outPath = System.getProperty(OUT_PROP) == null || System.getProperty(OUT_PROP).isBlank()
                ? run.outFile == null ? DEFAULT_BASELINE_PATH : Path.of(run.outFile)
                : Path.of(System.getProperty(OUT_PROP));
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, withRunConfig(report.toJson(), run), StandardCharsets.UTF_8);
        System.out.println("[rag-benchmark] 基线已落盘: " + outPath.toAbsolutePath());
        System.out.println("[rag-benchmark] metrics=" + report.metrics());

        // 4) 取证旁路（trace-i05-citation-forensics 1.2）：对齐前后答案/[n]/召回/excerpt，不进 CaseScore
        Path tracePath = resolveTraceOutPath();
        writeForensicsTrace(tracePath, enrichForensicsWithScores(forensicCases, report));
        System.out.println("[rag-benchmark] 取证旁路已落盘: " + tracePath.toAbsolutePath());
    }

    /** 解析取证旁路输出路径。 */
    static Path resolveTraceOutPath() {
        String override = System.getProperty(TRACE_OUT_PROP);
        if (override == null || override.isBlank()) {
            return DEFAULT_TRACE_PATH;
        }
        return Path.of(override);
    }

    /**
     * only 过滤（任务 1.1）：{@code only} 为空不滤；逗号分隔 id；未知 id 或结果空套件失败。
     * package-private 供 {@code BenchmarkOnlyFilterTest} 单测。
     */
    static List<RagBenchmarkCase> applyOnlyFilter(List<RagBenchmarkCase> suite, String only) {
        Objects.requireNonNull(suite, "suite");
        if (only == null || only.isBlank()) {
            return suite;
        }
        Set<String> wanted = Arrays.stream(only.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertFalse(wanted.isEmpty(), "rag.benchmark.only 解析后为空");

        Set<String> available = suite.stream().map(RagBenchmarkCase::id).collect(Collectors.toSet());
        Set<String> unknown = new LinkedHashSet<>();
        for (String id : wanted) {
            if (!available.contains(id)) {
                unknown.add(id);
            }
        }
        assertTrue(unknown.isEmpty(),
                "rag.benchmark.only 含未知 id: " + unknown + "（套件已知 id 数=" + available.size() + "）");

        List<RagBenchmarkCase> filtered = suite.stream()
                .filter(c -> wanted.contains(c.id()))
                .toList();
        assertFalse(filtered.isEmpty(), "rag.benchmark.only 过滤后套件为空: " + wanted);
        return filtered;
    }

    /** 把报告里的 citP/recall/ansC 回填到取证记录（不改 CaseScore 字段集）。 */
    static List<ForensicCase> enrichForensicsWithScores(List<ForensicCase> traces, Report report) {
        Map<String, RagQualityReport.CaseScore> byId = new LinkedHashMap<>();
        if (report != null && report.cases() != null) {
            for (RagQualityReport.CaseScore score : report.cases()) {
                byId.put(score.id(), score);
            }
        }
        List<ForensicCase> enriched = new ArrayList<>(traces.size());
        for (ForensicCase trace : traces) {
            RagQualityReport.CaseScore score = byId.get(trace.id());
            if (score == null) {
                enriched.add(trace);
                continue;
            }
            enriched.add(new ForensicCase(
                    trace.id(),
                    trace.question(),
                    trace.goldenIds(),
                    trace.retrievedChunkIds(),
                    trace.excerpts(),
                    trace.rawAnswer(),
                    trace.alignedAnswer(),
                    trace.citationsBefore(),
                    trace.citationsAfter(),
                    score.citationPrecision(),
                    score.answerConsistency(),
                    score.recallAtK()));
        }
        return enriched;
    }

    /** 取证 JSON 落盘（字段齐全；假数据单测可直接调 {@link #forensicsToJson}）。 */
    static void writeForensicsTrace(Path path, List<ForensicCase> cases) throws Exception {
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        Files.writeString(path, forensicsToJson(cases), StandardCharsets.UTF_8);
    }

    /** 序列化取证旁路 JSON（任务 2.2 单测入口）。 */
    static String forensicsToJson(List<ForensicCase> cases) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("caseCount", cases == null ? 0 : cases.size());
        root.put("cases", cases == null ? List.of() : cases);
        return mapper.writeValueAsString(root);
    }

    /**
     * 取证单条（不进 CaseScore）。excerpts 含 1-based n / chunkId / 截断 excerpt。
     * package-private 供单测构造假数据。
     */
    record ForensicCase(
            String id,
            String question,
            List<String> goldenIds,
            List<String> retrievedChunkIds,
            List<ExcerptTrace> excerpts,
            String rawAnswer,
            String alignedAnswer,
            List<Integer> citationsBefore,
            List<Integer> citationsAfter,
            Double citationPrecision,
            Double answerConsistency,
            Double recallAtK) {
    }

    /** 召回来源 excerpt 追踪行。 */
    record ExcerptTrace(int n, String chunkId, String excerpt) {
    }

    /** 在报告 JSON 末尾附当跑矩阵快照（凭报告可复现该跑）；不影响 RAG 指标字段。 */
    private static String withRunConfig(String reportJson, RagBenchmarkRun run) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reportJson);
            root.put("runProfile", run.name());
            root.put("runChunking", run.chunking);
            root.putObject("runConfig").put("sparseOn", run.sparseOn).put("contextOn", run.contextOn)
                    .set("matrix", mapper.valueToTree(run.matrix));
            return mapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return reportJson;
        }
    }

    /** 单条用例：检索 → 生成（带引用）→ 判卷要点覆盖；任何阶段异常由评估器记失败用例。 */
    private CaseOutcome evaluateCase(KnowledgeRetrievalPort port, ModelProvider provider, RagBenchmarkCase c) {
        long startedAt = System.currentTimeMillis();

        // 闲聊（KB OFF）：真系统不检索，直接对话生成；无检索无引用 = 诚实满分口径
        if (!c.useKnowledgeBase()) {
            Generated reply = generate(provider, CHAT_SYSTEM_PROMPT, c.question(), 256);
            recordForensics(c, List.of(), List.of(), reply.text(), reply.text(), List.of(), List.of());
            return new CaseOutcome(List.of(), List.of(), 1.0, reply.ttftMs(),
                    System.currentTimeMillis() - startedAt, reply.tokens(), !reply.text().isBlank());
        }

        KnowledgeRetrievalPort.RetrievalResult result = port.retrieve(new KnowledgeRetrievalPort.RetrievalQuery(
                c.question(), EVAL_USER_ID, List.of(String.valueOf(EVAL_KB_ID)), TOP_K, null, null));
        List<String> retrieved = result.sources().stream().map(SourceReference::chunkId).toList();

        if (retrieved.isEmpty()) {
            // 零命中：验证答案阶段的诚实语义（D16）；EDGE（期望空）答"未找到"=覆盖满分
            Generated reply = generate(provider, NO_HIT_SYSTEM_PROMPT, c.question(), 256);
            boolean declined = isDecline(reply.text());
            double coverage = c.expectedAnswerPoints().isEmpty() ? (declined ? 1.0 : 0.0) : 0.0;
            recordForensics(c, List.of(), List.of(), reply.text(), reply.text(), List.of(), List.of());
            return new CaseOutcome(List.of(), List.of(), coverage,
                    reply.ttftMs(), System.currentTimeMillis() - startedAt, reply.tokens(), true);
        }

        // 有命中：资料编号注入 → 流式生成带 [n] 引用的答案 → 抽取真实引用编号
        String userContent = "资料：\n" + result.context() + "\n\n问题：" + c.question();
        Generated answer = generate(provider, KB_ANSWER_SYSTEM_PROMPT, userContent, 512);
        // trace-i05-citation-forensics 1.2：对齐前先 extract，再 align，再 extract
        List<Integer> citationsBefore = extractCitations(answer.text(), retrieved.size());
        // fix-citation-alignment 任务 2.2：评测抽取与生产共用 CitationAligner
        CitationAligner.Alignment aligned = CitationAligner.align(answer.text(), result.sources());
        String alignedText = aligned.text();
        List<Integer> citations = extractCitations(alignedText, retrieved.size());
        recordForensics(c, retrieved, result.sources(), answer.text(), alignedText, citationsBefore, citations);

        // 答案要点覆盖：期望要点非空时由模型判卷；EDGE（期望空）看是否诚实拒答（对齐只改编号）
        double coverage;
        if (c.expectedAnswerPoints().isEmpty()) {
            coverage = isDecline(alignedText) ? 1.0 : 0.0;
        } else {
            coverage = judgeCoverage(provider, c.expectedAnswerPoints(), alignedText);
        }
        return new CaseOutcome(retrieved, citations, coverage,
                answer.ttftMs(), System.currentTimeMillis() - startedAt, answer.tokens(), true);
    }

    /** 采集对齐前后答案、引用、召回与 excerpt（截断），稍后与评分回填合并写旁路文件。 */
    private void recordForensics(
            RagBenchmarkCase c,
            List<String> retrieved,
            List<SourceReference> sources,
            String rawAnswer,
            String alignedAnswer,
            List<Integer> citationsBefore,
            List<Integer> citationsAfter) {
        List<ExcerptTrace> excerpts = new ArrayList<>();
        if (sources != null) {
            for (int i = 0; i < sources.size(); i++) {
                SourceReference source = sources.get(i);
                String excerpt = source.excerpt() == null ? "" : source.excerpt();
                if (excerpt.length() > EXCERPT_LIMIT) {
                    excerpt = excerpt.substring(0, EXCERPT_LIMIT);
                }
                excerpts.add(new ExcerptTrace(i + 1, source.chunkId(), excerpt));
            }
        }
        List<String> goldens = c.expectedChunkIds() == null
                ? List.of()
                : c.expectedChunkIds().stream().sorted().toList();
        forensicCases.add(new ForensicCase(
                c.id(),
                c.question(),
                goldens,
                retrieved == null ? List.of() : List.copyOf(retrieved),
                List.copyOf(excerpts),
                rawAnswer == null ? "" : rawAnswer,
                alignedAnswer == null ? "" : alignedAnswer,
                citationsBefore == null ? List.of() : List.copyOf(citationsBefore),
                citationsAfter == null ? List.of() : List.copyOf(citationsAfter),
                null,
                null,
                null));
    }

    /** 流式生成：TTFT=首个 chunk 时延；tokens=流中最后一次 usage；文本按增量拼接。 */
    private Generated generate(ModelProvider provider, String systemPrompt, String userContent, int maxTokens) {
        long startedAt = System.currentTimeMillis();
        List<Message> messages = List.of(new SystemMessage(systemPrompt), new UserMessage(userContent));
        Prompt prompt = new Prompt(messages);
        // 中立选项：不开思维链、低温、限输出长度——基线要求稳定可复现
        ModelCallOptions options = new ModelCallOptions(null, false, 0.1d, maxTokens, null, null, Map.of());

        AtomicLong ttft = new AtomicLong();
        AtomicLong tokens = new AtomicLong();
        StringBuilder text = new StringBuilder();
        Flux<ChatResponse> stream = provider.streamChat(prompt, options)
                .doOnNext(chunk -> {
                    ttft.compareAndSet(0L, System.currentTimeMillis() - startedAt);
                    if (chunk.getResult() != null && chunk.getResult().getOutput() != null
                            && chunk.getResult().getOutput().getText() != null) {
                        text.append(chunk.getResult().getOutput().getText());
                    }
                    if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null
                            && chunk.getMetadata().getUsage().getTotalTokens() != null) {
                        tokens.set(chunk.getMetadata().getUsage().getTotalTokens());
                    }
                });
        List<ChatResponse> chunks = stream.collectList().block(Duration.ofSeconds(120));
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("流式生成返回空流");
        }
        return new Generated(text.toString(), ttft.get(), System.currentTimeMillis() - startedAt, (int) tokens.get());
    }

    /** 要点覆盖判卷：逐点输出 true/false，按顺序对齐；解析不出布尔串时保守记 0 并打印原文。 */
    private double judgeCoverage(ModelProvider provider, List<String> points, String answer) {
        String userContent = "回答：" + answer + "\n\n要点清单：\n" + outline(points)
                + "\n\n逐点判断回答是否覆盖了该要点。只输出一个 JSON 数组，按要点顺序，覆盖为 true 否则 false。";
        ModelCallOptions options = new ModelCallOptions(null, false, 0.0d, 128, null, null, Map.of());
        List<Message> judgeMessages = List.of(
                new SystemMessage("你是严格的评测判卷器，只输出 JSON 数组，不输出解释。"),
                new UserMessage(userContent));
        ModelCallResult<String> verdict = provider.chat(new Prompt(judgeMessages), options);
        List<String> booleans = new ArrayList<>();
        Matcher matcher = BOOLEAN_PATTERN.matcher(verdict.content() == null ? "" : verdict.content());
        while (matcher.find()) {
            booleans.add(matcher.group().toLowerCase(java.util.Locale.ROOT));
        }
        if (booleans.size() < points.size()) {
            System.out.println("[rag-benchmark] 判卷输出无法解析: " + verdict.content());
            return 0.0;
        }
        long covered = 0;
        for (int i = 0; i < points.size(); i++) {
            if ("true".equals(booleans.get(i))) {
                covered++;
            }
        }
        return (double) covered / points.size();
    }

    private String outline(List<String> points) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            builder.append(i + 1).append(". ").append(points.get(i)).append('\n');
        }
        return builder.toString();
    }

    /** 答案内联 [n] → 1-based 引用编号（去重、越界忽略），供 citationPrecision 判定。 */
    private List<Integer> extractCitations(String answer, int sourceCount) {
        List<Integer> citations = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return citations;
        }
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= sourceCount && !citations.contains(index)) {
                citations.add(index);
            }
        }
        return citations;
    }

    private boolean isDecline(String answer) {
        return answer != null && DECLINE_MARKS.stream().anyMatch(answer::contains);
    }

    /** 授权评测桩：评测固定放行评测知识库（生产授权语义不在本基准考察范围）。 */
    private KnowledgeBaseAuthorizationService allowEvalKnowledgeBaseOnly() {
        return new KnowledgeBaseAuthorizationService(null, null) {
            @Override
            public List<Long> authorizedKnowledgeBaseIds(UserContext user, List<String> requestedScopes) {
                return List.of(EVAL_KB_ID);
            }
        };
    }

    /** 装配真实 Provider：DashScope 原生（chat/embed）+ compatible-mode（stream），同 DashScope IT。 */
    private ModelProvider buildProvider(String apiKey) {
        DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
        ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", apiKey);
        environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);

        ModelProviderProperties properties = new ModelProviderProperties();
        return new ModelProviderImpl(provider(nativeChat), provider(embeddingModel), properties, environment);
    }

    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }
        };
    }

    /** 读 key：优先环境变量，其次仓库根 .env（与 ModelProviderImplDashScopeIT 相同的解析顺序）。 */
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

    /** 一次生成的产出：文本、首 chunk 时延、总时延、流内 usage token。 */
    private record Generated(String text, long ttftMs, long totalMs, int tokens) {
    }
}
