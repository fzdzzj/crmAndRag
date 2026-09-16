package com.slz.crm.knowledge.document;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图像 PDF 页视觉转写试点（add-vision-pdf-ingest-pilot 任务 1.1）。
 *
 * <p>文本层过短时，把该页渲染为 PNG 后调用 {@link ModelProvider#vision}；
 * 质量闸门失败或调用异常时返回 empty，由 {@link DocumentService} 保留文本层。
 * 默认关总开关不在本类强制——由 DocumentService 在调用前判断；本类只负责检测/渲染/转写/闸门/计量/页数帽。</p>
 *
 * <p>不新增 Maven 依赖：渲染走 PDFBox 3 已有 {@code PDFRenderer}。</p>
 */
@Service
public class PdfVisionTranscriber {

    private static final Logger log = LoggerFactory.getLogger(PdfVisionTranscriber.class);

    /** 总开关键（默认 false，由 DocumentService 读取）。 */
    public static final String ENABLED_KEY = "rag.retrieval.vision-pdf.enabled";
    /** 低于此长度（normalize 后）视为图像页，尝试视觉。 */
    public static final String MIN_TEXT_CHARS_KEY = "rag.retrieval.vision-pdf.min-text-chars";
    /** 单文档最多 VLM 页。 */
    public static final String MAX_PAGES_KEY = "rag.retrieval.vision-pdf.max-pages";

    static final int DEFAULT_MIN_TEXT_CHARS = 80;
    static final int DEFAULT_MAX_PAGES = 3;
    static final int RENDER_DPI = 144;
    static final int MAX_EDGE_PX = 1600;
    /** 转写去空白后最短合格长度。 */
    static final int MIN_TRANSCRIPT_CHARS = 40;
    /** 「（截图不清）」次数 / 汉字数 上限；超过则闸门失败。 */
    static final double UNCLEAR_HAN_RATIO_LIMIT = 0.4;

    private static final String UNCLEAR_MARK = "（截图不清）";
    private static final Pattern HAN_PATTERN = Pattern.compile("\\p{IsHan}");

    /**
     * 转写提示词（add-vision-pdf-ingest-pilot 任务 1.2）：对齐 {@code _vlm_transcribe.py} 纪律——
     * 只转写可见内容、不清标「（截图不清）」、不猜测补全、不要寒暄。
     */
    static final String TRANSCRIBE_PROMPT = """
            你是精确的课件转写引擎。把这张幻灯片截图的全部内容转写为结构化 Markdown：
            1. 逐字提取所有可见文字：标题、副标题、正文、要点、图表标注、页眉页脚、页码、版权/水印行。
            2. 代码截图：用三个反引号包裹完整转写，尽量保留缩进与符号；无法确认的字符标注（截图不清）。
            3. 架构图/流程图：用文字加箭头描述节点与连接关系；有分层或分组要说明层级。
            4. 表格：转成 Markdown 表格，保留行列与表头。
            5. 图中极小、模糊或被截断而无法确认的文字，一律标注“（截图不清）”，禁止猜测补全成看似合理的值。
            6. 只转写图中确实存在的内容，不要添加解释、背景知识或图里没有的信息，不要编造数字/模型名/文献。
            7. 直接输出转写结果，不要寒暄。""";

    private final ModelProvider modelProvider;
    private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
    private final ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

    public PdfVisionTranscriber(ModelProvider modelProvider,
                                ObjectProvider<DynamicConfigService> dynamicConfigProvider,
                                ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider) {
        this.modelProvider = modelProvider;
        this.dynamicConfigProvider = dynamicConfigProvider;
        this.tokenUsageRecorderProvider = tokenUsageRecorderProvider;
    }

    /**
     * 为单次文档解析创建页数帽计数器（默认 3，范围 1~20）。
     */
    public AtomicInteger newPageBudget() {
        return new AtomicInteger(resolveMaxPages());
    }

    /**
     * 文本层过短时尝试视觉转写。
     *
     * @param document         已打开的 PDF（调用方负责关闭）
     * @param pageIndex0Based  0-based 页下标
     * @param pageText         该页文本层原文
     * @param remainingBudget  本文档剩余可调用 vision 的页数；≤0 时直接 empty；实际发起 vision 前减 1
     * @return 过闸门的转写正文；否则 empty（调用方保留文本层）
     */
    public Optional<String> tryTranscribe(PDDocument document,
                                          int pageIndex0Based,
                                          String pageText,
                                          AtomicInteger remainingBudget) {
        if (document == null || remainingBudget == null || remainingBudget.get() <= 0) {
            return Optional.empty();
        }
        String normalized = normalize(pageText);
        if (normalized.length() >= resolveMinTextChars()) {
            return Optional.empty();
        }
        // 计入页数帽：过短页才消耗预算（无论成败）
        remainingBudget.decrementAndGet();

        byte[] png;
        try {
            png = renderPagePng(document, pageIndex0Based);
        } catch (Exception renderEx) {
            log.warn("PDF 页渲染失败，回退文本层: pageIndex={}", pageIndex0Based, renderEx);
            return Optional.empty();
        }
        if (png == null || png.length == 0) {
            return Optional.empty();
        }

        ModelCallResult<String> result = null;
        boolean success = false;
        try {
            Media media = new Media(MimeType.valueOf("image/png"), new ByteArrayResource(png));
            UserMessage userMessage = UserMessage.builder()
                    .text(TRANSCRIBE_PROMPT)
                    .media(List.of(media))
                    .build();
            // thinking=false：defaults() 已关闭思维链
            result = modelProvider.vision(new Prompt(List.of(userMessage)), ModelCallOptions.defaults());
            String content = result == null ? null : result.content();
            if (passesQualityGate(content)) {
                success = true;
                return Optional.of(content.strip());
            }
            log.info("视觉转写未过质量闸门，回退文本层: pageIndex={}", pageIndex0Based);
            return Optional.empty();
        } catch (Exception visionEx) {
            log.warn("视觉转写调用失败，回退文本层: pageIndex={}", pageIndex0Based, visionEx);
            return Optional.empty();
        } finally {
            recordUsage(result, success);
        }
    }

    /** 渲染单页为 PNG（144DPI，宽边 ≤1600）。包内可见便于单测夹具。 */
    byte[] renderPagePng(PDDocument document, int pageIndex0Based) throws Exception {
        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage image = renderer.renderImageWithDPI(pageIndex0Based, RENDER_DPI);
        image = scaleToMaxEdge(image, MAX_EDGE_PX);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", baos)) {
            throw new IllegalStateException("ImageIO 无法写出 PNG");
        }
        return baos.toByteArray();
    }

    static BufferedImage scaleToMaxEdge(BufferedImage src, int maxEdge) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxEdge) {
            return src;
        }
        double scale = (double) maxEdge / (double) max;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * 质量闸门：blank / 去空白后 &lt;40 字 / 「（截图不清）」次数÷汉字数 &gt;0.4 → 失败。
     */
    static boolean passesQualityGate(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String compact = raw.replaceAll("\\s+", "");
        if (compact.length() < MIN_TRANSCRIPT_CHARS) {
            return false;
        }
        int unclear = countOccurrences(raw, UNCLEAR_MARK);
        int han = countHan(raw);
        if (unclear > 0 && han == 0) {
            return false;
        }
        if (han > 0 && ((double) unclear / (double) han) > UNCLEAR_HAN_RATIO_LIMIT) {
            return false;
        }
        return true;
    }

    private void recordUsage(ModelCallResult<String> result, boolean success) {
        TokenUsageRecorder recorder = tokenUsageRecorderProvider == null
                ? null : tokenUsageRecorderProvider.getIfAvailable();
        if (recorder == null) {
            return;
        }
        try {
            String model = result == null || result.model() == null ? "unknown" : result.model();
            recorder.record(new TokenUsageRecord(
                    model,
                    currentUserRef(),
                    null,
                    null,
                    TokenUsageType.VISION,
                    result == null ? null : result.promptTokens(),
                    result == null ? null : result.completionTokens(),
                    result == null ? null : result.totalTokens(),
                    success));
        } catch (Exception ignore) {
            // 计量失败不挡摄取
            log.debug("VISION token 计量上报失败（忽略）", ignore);
        }
    }

    private String currentUserRef() {
        UserContext user = UserContextHolder.current();
        return user == null ? "user:system" : user.userIdRef();
    }

    private int resolveMinTextChars() {
        DynamicConfigService config = dynamicConfigProvider == null
                ? null : dynamicConfigProvider.getIfAvailable();
        Integer configured = config == null ? null
                : config.get(MIN_TEXT_CHARS_KEY, Integer.class, DEFAULT_MIN_TEXT_CHARS);
        if (configured == null || configured < 1) {
            return DEFAULT_MIN_TEXT_CHARS;
        }
        return Math.min(configured, 2000);
    }

    private int resolveMaxPages() {
        DynamicConfigService config = dynamicConfigProvider == null
                ? null : dynamicConfigProvider.getIfAvailable();
        Integer configured = config == null ? null
                : config.get(MAX_PAGES_KEY, Integer.class, DEFAULT_MAX_PAGES);
        if (configured == null || configured < 1) {
            return DEFAULT_MAX_PAGES;
        }
        return Math.min(configured, 20);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").strip();
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                break;
            }
            count++;
            from = at + needle.length();
        }
        return count;
    }

    private static int countHan(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = HAN_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
