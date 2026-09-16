package com.slz.crm.knowledge.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

/**
 * 图像 PDF 单页真 VLM 试点（add-vision-pdf-ingest-pilot 任务 5.1 / 5.2，授权节点）。
 *
 * <p>只在显式设置环境变量 {@code RAG_VISION_PDF_REAL=1} 时执行，否则 JUnit 假设跳过（CI 恒跳过）。
 * 本类**不设置也不读取** {@code RAG_BENCHMARK_REAL}，**不跑** 54 条基准、**不**做 132 页全量转写。</p>
 *
 * <p>试点链路（人造夹具，成本约 1 次 vision）：1 页仅绘制位图的 PDF（无文本算子，PDFTextStripper 近空）
 * → {@code PdfVisionTranscriber} 渲染 144DPI PNG → 真实 DashScope {@link ModelProvider#vision}
 * （{@code qwen-vl-plus}，compatible-mode）→ 质量闸门 / 回退链 → {@link DocumentService#process}。</p>
 *
 * <p>判定边界：vision 抛错（渲染/模型异常）→ 测试红，记录异常类别后停下、不改代码；
 * 闸门未过但 process 安全回退（抛「文档解析结果为空」而非视觉异常）→ 测试绿，记录原因。
 * 不放宽阈值、不改 Prompt、不做全量重试。</p>
 *
 * <p>API key 只从环境变量或仓库根 {@code .env} 读取，不打印、不入库。</p>
 */
class VisionPdfRealPilotIT {

    /** 授权闸门：未设该环境变量则整类跳过。 */
    private static final String GATE_ENV = "RAG_VISION_PDF_REAL";
    /** compatible-mode 端点（Spring AI OpenAI 协议要求 base-url 已含版本前缀）。 */
    private static final String COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 图上可见关键词（用于判定转写是否真的读到了图，而非空壳）。 */
    private static final List<String> IMAGE_KEYWORDS = List.of("服务台", "架构", "接入层", "调度层", "数据层", "台账");
    private static final String UNCLEAR_MARK = "（截图不清）";
    private static final Pattern HAN_PATTERN = Pattern.compile("\\p{IsHan}");
    /** 试点固定配置：min-text-chars=80（与默认一致）/ max-pages=1（避免多页外呼）。 */
    private static final int PILOT_MIN_TEXT_CHARS = 80;
    private static final int PILOT_MAX_PAGES = 1;

    @Test
    void onePageTextlessPdfShouldPassRenderVisionGateChain() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv(GATE_ENV)),
                "未授权真 VLM 试点（需 " + GATE_ENV + "=1），跳过");
        String apiKey = resolveApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未配置 DASHSCOPE_API_KEY（env 或 .env），跳过真机试点");

        RecordingVisionProvider recording = new RecordingVisionProvider(buildProvider(apiKey));
        DynamicConfigService config = pilotConfig();
        ObjectProvider<DynamicConfigService> configProvider = fixedProvider(config);
        // 三参构造：真实 ModelProvider + mock 动态配置 + 无 token 计量（计量不参与本试点断言）
        PdfVisionTranscriber transcriber = new PdfVisionTranscriber(recording, configProvider, null);
        DocumentService service = new DocumentService(configProvider, fixedProvider(transcriber));

        byte[] pdf = onePageImageOnlyPdf();
        String textLayer = textLayerOf(pdf);

        List<DocumentChunk> chunks = null;
        IllegalStateException processFailure = null;
        try {
            chunks = service.process(new ByteArrayInputStream(pdf), "vision-pdf-pilot.pdf", "crm");
        } catch (IllegalStateException ex) {
            processFailure = ex;
        }

        String transcript = recording.transcript();
        boolean gatePassed = transcript != null && PdfVisionTranscriber.passesQualityGate(transcript);
        String chunkPages = chunks == null ? "n/a" : chunks.stream()
                .map(DocumentChunk::pageNo).distinct().toList().toString();
        List<String> hitKeywords = IMAGE_KEYWORDS.stream()
                .filter(keyword -> transcript != null && transcript.contains(keyword))
                .toList();

        // 证据先打印，再断言（红也能拿到完整现场）
        System.out.println("[PILOT] gateEnv=" + GATE_ENV + "=1");
        System.out.println("[PILOT] textLayerChars=" + textLayer.strip().length());
        System.out.println("[PILOT] visionCalls=" + recording.visionCalls());
        System.out.println("[PILOT] transcriptChars=" + (transcript == null ? -1 : compact(transcript).length())
                + " han=" + hanCount(transcript) + " unclear=" + occurrences(transcript, UNCLEAR_MARK));
        System.out.println("[PILOT] gatePassed=" + gatePassed);
        System.out.println("[PILOT] gateFailureReason=" + (gatePassed ? "none" : gateFailureReason(transcript)));
        System.out.println("[PILOT] keywordHits=" + hitKeywords);
        System.out.println("[PILOT] chunks=" + (chunks == null ? -1 : chunks.size()) + " pageNos=" + chunkPages);
        System.out.println("[PILOT] processFailure=" + (processFailure == null ? "none" : processFailure.getMessage()));
        System.out.println("[PILOT] visionErrorClass="
                + (recording.visionError() == null ? "none" : recording.visionError().getClass().getName()));
        printTranscriptExcerpt(transcript);

        // 夹具前提：确为「无文本层」页，视觉路径才会被触发
        assertTrue(textLayer.strip().length() < PILOT_MIN_TEXT_CHARS,
                "夹具必须是文本层近空的图像页，实际文本层长度=" + textLayer.strip().length());
        // 成本闸门：本次试点只允许 1 次 vision（渲染失败/文本层够长都会走到这里变红）
        assertEquals(1, recording.visionCalls(),
                "试点必须恰好 1 次 vision（0 次=渲染失败或该页未被判为图像页）");
        // 渲染/模型异常 → 红并记录异常类别，按边界停下（不修代码、不重试）
        assertNull(recording.visionError(), "vision 调用异常，异常类别="
                + (recording.visionError() == null ? "none" : recording.visionError().getClass().getName()));

        if (gatePassed) {
            assertNotNull(chunks, "闸门通过时 process 必须产出切片");
            assertEquals("1", chunkPages.replaceAll("[^0-9]", ""),
                    "转写页 pageNo 必须为 1，实际=" + chunkPages);
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains(compact(transcript).substring(0, 10))),
                    "切片正文应来自视觉转写: " + chunks);
        } else {
            // 闸门失败必须安全回退：整篇空 → 旧口径异常，且不得冒泡视觉异常
            assertNotNull(processFailure, "闸门失败必须安全回退（不得产出空壳切片），闸门原因="
                    + gateFailureReason(transcript));
            assertTrue(processFailure.getMessage().contains("文档解析结果为空"), processFailure.getMessage());
            assertFalse(processFailure.getMessage().toLowerCase().contains("vision"), processFailure.getMessage());
        }
    }

    /** 打印转写前 10 行（非敏感：模型对本类人造位图的转写结果）。 */
    private static void printTranscriptExcerpt(String transcript) {
        System.out.println("[PILOT] transcriptExcerptBegin");
        if (transcript != null) {
            transcript.lines().limit(10).forEach(line -> System.out.println("[PILOT] " + line));
        }
        System.out.println("[PILOT] transcriptExcerptEnd");
    }

    // ---------- 夹具：1 页仅位图的 PDF（无文本算子） ----------

    /** 造 1 页 PDF：整页贴一张位图，不使用任何 PDF 文本算子（PDFTextStripper 取不到文字）。 */
    private static byte[] onePageImageOnlyPdf() throws Exception {
        BufferedImage image = architectureDiagram();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject xobject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(xobject, 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(out);
        }
        return out.toByteArray();
    }

    /** 位图内容：服务台架构分层图 + SLA 台账（文字用 Graphics2D 画在图上，非 PDF 文本层）。 */
    private static BufferedImage architectureDiagram() {
        int width = 1240;
        int height = 1754;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(0x1F, 0x2A, 0x37));
            g.setFont(cjkFont(Font.BOLD, 64));
            g.drawString("服务台架构", 80, 170);

            g.setFont(cjkFont(Font.PLAIN, 38));
            g.setColor(new Color(0x44, 0x4A, 0x55));
            g.drawString("接入层 -> 调度层 -> 数据层", 80, 250);

            String[] layers = {"接入层", "调度层", "数据层"};
            int boxWidth = 300;
            int boxHeight = 140;
            int boxTop = 320;
            g.setStroke(new BasicStroke(4f));
            int[] boxLefts = new int[layers.length];
            for (int i = 0; i < layers.length; i++) {
                int left = 80 + i * 400;
                boxLefts[i] = left;
                g.setColor(new Color(0xE8, 0xF1, 0xFB));
                g.fillRoundRect(left, boxTop, boxWidth, boxHeight, 24, 24);
                g.setColor(new Color(0x2C, 0x6D, 0xB4));
                g.drawRoundRect(left, boxTop, boxWidth, boxHeight, 24, 24);
                g.setColor(new Color(0x1F, 0x2A, 0x37));
                g.setFont(cjkFont(Font.BOLD, 46));
                g.drawString(layers[i], left + 70, boxTop + 90);
            }
            for (int i = 0; i < layers.length - 1; i++) {
                int fromX = boxLefts[i] + boxWidth;
                int toX = boxLefts[i + 1];
                int y = boxTop + boxHeight / 2;
                g.setColor(new Color(0x2C, 0x6D, 0xB4));
                g.drawLine(fromX + 10, y, toX - 24, y);
                g.fillPolygon(new int[]{toX - 24, toX - 46, toX - 46}, new int[]{y, y - 12, y + 12}, 3);
            }

            int ledgerTop = 560;
            g.setColor(new Color(0xFD, 0xF3, 0xE3));
            g.fillRoundRect(80, ledgerTop, width - 160, 150, 24, 24);
            g.setColor(new Color(0xD9, 0x8A, 0x1F));
            g.drawRoundRect(80, ledgerTop, width - 160, 150, 24, 24);
            g.setColor(new Color(0x1F, 0x2A, 0x37));
            g.setFont(cjkFont(Font.BOLD, 48));
            g.drawString("SLA台账", 120, ledgerTop + 70);
            g.setFont(cjkFont(Font.PLAIN, 34));
            g.drawString("工单、响应时长、解决时长按分层登记", 120, ledgerTop + 120);

            g.setFont(cjkFont(Font.PLAIN, 32));
            g.setColor(new Color(0x44, 0x4A, 0x55));
            g.drawString("工单进入接入层后由调度层分派，数据层负责台账落库。", 80, 800);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 选择能渲染本夹具汉字的字体（Windows 优先雅黑/黑体/宋体，兜底 AWT 逻辑字体回退）。 */
    private static Font cjkFont(int style, int size) {
        List<String> candidates = List.of("Microsoft YaHei", "SimHei", "SimSun",
                "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Zen Hei");
        for (String name : candidates) {
            Font font = new Font(name, style, size);
            if (font.canDisplayUpTo("服务台架构接入层调度数据台账") < 0) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    /** PDFTextStripper 抽文本层，用于证明夹具「无文本层」。 */
    private static String textLayerOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    // ---------- 配置与 Provider 装配 ----------

    /** 试点配置（其余键回落默认；max-pages=1 避免多页外呼）。 */
    private static DynamicConfigService pilotConfig() {
        return new DynamicConfigService() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(String key, Class<T> type, T defaultValue) {
                Object value = switch (key) {
                    case PdfVisionTranscriber.ENABLED_KEY -> Boolean.TRUE;
                    case PdfVisionTranscriber.MIN_TEXT_CHARS_KEY -> PILOT_MIN_TEXT_CHARS;
                    case PdfVisionTranscriber.MAX_PAGES_KEY -> PILOT_MAX_PAGES;
                    default -> null;
                };
                return value == null ? defaultValue : (T) value;
            }
        };
    }

    /** 构造真实 Provider：DashScope 原生（chat/embed）+ compatible-mode（stream/vision）。 */
    private static ModelProvider buildProvider(String apiKey) {
        DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
        ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.dashscope.api-key", apiKey);
        environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);

        ModelProviderProperties properties = new ModelProviderProperties();
        return new ModelProviderImpl(fixedProvider(nativeChat), fixedProvider(embeddingModel),
                properties, environment);
    }

    /** 读取 key：优先环境变量，其次仓库根 .env（与 ModelProviderImplDashScopeIT 同口径），不打印。 */
    private static String resolveApiKey() {
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
                // 读不到就走「跳过」分支
            }
        }
        return null;
    }

    /** 最小 ObjectProvider 包装（测试里没有容器，只有固定实例）。 */
    private static <T> ObjectProvider<T> fixedProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }
        };
    }

    // ---------- 现场记录辅助 ----------

    private static String gateFailureReason(String transcript) {
        if (transcript == null) {
            return "vision 未返回结果（调用异常或未发起）";
        }
        if (transcript.isBlank()) {
            return "转写 blank";
        }
        String compactText = compact(transcript);
        if (compactText.length() < PdfVisionTranscriber.MIN_TRANSCRIPT_CHARS) {
            return "去空白后长度 " + compactText.length() + " < " + PdfVisionTranscriber.MIN_TRANSCRIPT_CHARS;
        }
        int unclear = occurrences(transcript, UNCLEAR_MARK);
        int han = hanCount(transcript);
        if (unclear > 0 && han == 0) {
            return "「（截图不清）」出现 " + unclear + " 次且无汉字";
        }
        if (han > 0 && ((double) unclear / han) > PdfVisionTranscriber.UNCLEAR_HAN_RATIO_LIMIT) {
            return "「（截图不清）」/" + han + " 汉字 = " + unclear
                    + " > " + PdfVisionTranscriber.UNCLEAR_HAN_RATIO_LIMIT;
        }
        return "未命中已知闸门规则";
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private static int hanCount(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        Matcher matcher = HAN_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int occurrences(String text, String needle) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** 记录 vision 次数与返回内容（异常照样记录后原样抛出，保证与生产回退路径一致）。 */
    private static final class RecordingVisionProvider implements ModelProvider {

        private final ModelProvider delegate;
        private final AtomicInteger visionCalls = new AtomicInteger();
        private volatile String transcript;
        private volatile Throwable visionError;

        RecordingVisionProvider(ModelProvider delegate) {
            this.delegate = delegate;
        }

        int visionCalls() {
            return visionCalls.get();
        }

        String transcript() {
            return transcript;
        }

        Throwable visionError() {
            return visionError;
        }

        @Override
        public String provider() {
            return delegate.provider();
        }

        @Override
        public ModelCallResult<String> chat(Prompt prompt) {
            return delegate.chat(prompt);
        }

        @Override
        public Flux<ChatResponse> streamChat(Prompt prompt) {
            return delegate.streamChat(prompt);
        }

        @Override
        public ModelCallResult<float[]> embed(EmbeddingRequest request) {
            return delegate.embed(request);
        }

        @Override
        public ModelCallResult<String> vision(Prompt prompt) {
            return capture(prompt, null);
        }

        @Override
        public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
            return capture(prompt, options);
        }

        private ModelCallResult<String> capture(Prompt prompt, ModelCallOptions options) {
            visionCalls.incrementAndGet();
            try {
                ModelCallResult<String> result = options == null
                        ? delegate.vision(prompt) : delegate.vision(prompt, options);
                transcript = result == null ? null : result.content();
                return result;
            } catch (RuntimeException | Error ex) {
                visionError = ex;
                throw ex;
            }
        }
    }
}