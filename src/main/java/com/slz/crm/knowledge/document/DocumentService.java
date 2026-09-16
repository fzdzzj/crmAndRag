package com.slz.crm.knowledge.document;

import com.alibaba.excel.EasyExcel;
import com.slz.crm.platform.contract.DynamicConfigService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析与分块服务。
 *
 * <p>按页保留 pageNo；Excel 按行保留 rowIndex，满足 D15 来源锚点要求——锚点按页统一附加，
 * 任何切分策略（{@link ChunkingStrategy}）都不跨页聚合，页级引用语义不受策略影响。</p>
 *
 * <p>切分策略经动态配置切换（提案4 任务 1.1）：{@code rag.chunking.strategy = fixed | semantic}
 * （默认 fixed = 升级前行为）；semantic 的段长上限走 {@code rag.chunking.max-chunk-size}。
 * 无参构造（测试/评测数据准备用）固定走 fixed。</p>
 */
@Service
public class DocumentService {
    /** 切分策略配置键：fixed（升级前滑窗）| semantic（标题/段落/转折词边界）。 */
    public static final String STRATEGY_KEY = "rag.chunking.strategy";
    public static final String STRATEGY_FIXED = "fixed";
    public static final String STRATEGY_SEMANTIC = "semantic";
    /** 语义切分单块上限配置键。 */
    public static final String MAX_CHUNK_SIZE_KEY = "rag.chunking.max-chunk-size";
    static final int DEFAULT_MAX_CHUNK_SIZE = 480;

    private static final int MIN_TEXT_LENGTH = 80;
    private static final int KEYWORD_COUNT = 6;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "txt", "md", "markdown", "xlsx", "xls");
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,30}|[\\p{IsHan}]{2,24}");
    private static final Set<String> STOP_WORDS = Set.of("我们", "你们", "他们", "是否", "已经", "进行", "可以",
            "需要", "因为", "所以", "为了", "如果", "或者", "以及", "其中", "通过", "对于", "相关");

    /** 表头启发式：首行非空格数下限（add-excel-header-projection 任务 1.1）。 */
    private static final int HEADER_MIN_NON_EMPTY = 2;
    /** 表头启发式：单个非空单元格长度上限；超长视为数据长句而非列名。 */
    private static final int HEADER_CELL_MAX_LEN = 32;


    private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
    /** 图像 PDF 视觉转写器；无参/单参构造为 null，永不调 vision（add-vision-pdf-ingest-pilot）。 */
    private final ObjectProvider<PdfVisionTranscriber> visionTranscriberProvider;

    /** 兼容构造（评测数据准备/单测）：无动态配置，固定 fixed 策略，不注入视觉转写。 */
    public DocumentService() {
        this(null, null);
    }

    /** 单测/评测：仅动态配置，不注入视觉转写。 */
    public DocumentService(ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
        this(dynamicConfigProvider, null);
    }

    /**
     * Spring 装配构造：经动态配置解析切分策略；可选装配 {@link PdfVisionTranscriber}。
     * add-vision-pdf-ingest-pilot 任务 2.1。
     */
    @Autowired
    public DocumentService(ObjectProvider<DynamicConfigService> dynamicConfigProvider,
                           ObjectProvider<PdfVisionTranscriber> visionTranscriberProvider) {
        this.dynamicConfigProvider = dynamicConfigProvider;
        this.visionTranscriberProvider = visionTranscriberProvider;
    }

    /**
     * 解析并分块。
     *
     * @param content  文件输入流
     * @param filename 原始文件名
     * @param category 业务类目
     * @return 全文档有序切片
     */
    public List<DocumentChunk> process(InputStream content, String filename, String category) throws Exception {
        String fileType = fileType(filename);
        ParsedDocument parsed = parse(content, filename, fileType);
        ChunkingStrategy strategy = resolveStrategy();
        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentPage page : parsed.pages()) {
            String normalized = normalize(page.text());
            if (normalized.isEmpty()) {
                continue;
            }
            for (ChunkingStrategy.StrategyChunk piece : strategy.split(normalized)) {
                chunks.add(new DocumentChunk(
                        piece.text(),
                        chunks.size(),
                        page.pageNo(),
                        page.rowIndex(),
                        category,
                        extractKeywords(piece.text()),
                        piece.parentText()));
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文档解析结果为空: " + filename);
        }
        return chunks;
    }

    /** 判断扩展名是否受支持，避免把解析失败错误记到错误类型上。 */
    public boolean supports(String filename) {
        return SUPPORTED_EXTENSIONS.contains(fileType(filename));
    }

    /** 策略解析：semantic 显式开启才生效，其余（含未配置/非法值）一律 fixed 现行为回退。 */
    private ChunkingStrategy resolveStrategy() {
        DynamicConfigService config = dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable();
        String strategy = config == null ? null : config.get(STRATEGY_KEY, String.class, STRATEGY_FIXED);
        if (strategy != null && STRATEGY_SEMANTIC.equalsIgnoreCase(strategy.strip())) {
            return new SemanticChunkingStrategy(resolveMaxChunkSize(config));
        }
        return FixedChunkingStrategy.INSTANCE;
    }

    /** 语义切分单块上限：{@code rag.chunking.max-chunk-size}（默认 480）；&lt;1 回落默认。 */
    private int resolveMaxChunkSize(DynamicConfigService config) {
        Integer configured = config == null ? null
                : config.get(MAX_CHUNK_SIZE_KEY, Integer.class, DEFAULT_MAX_CHUNK_SIZE);
        return configured == null || configured < 1 ? DEFAULT_MAX_CHUNK_SIZE : configured;
    }

    private ParsedDocument parse(InputStream content, String filename, String fileType) throws Exception {
        return switch (fileType) {
            case "txt", "md", "markdown" -> new ParsedDocument(fileType, List.of(new DocumentPage(
                    1, null, new String(content.readAllBytes(), StandardCharsets.UTF_8))));
            case "pdf" -> parsePdf(content);
            case "xlsx", "xls" -> parseExcel(content);
            default -> throw new IllegalStateException("不支持的文件类型: " + fileType);
        };
    }

    /**
     * PDFBox 直连解析；按页抽取并保留 pageNo，供页级来源引用使用。
     *
     * <p>add-vision-pdf-ingest-pilot 任务 2.1：开关开且转写器给出非空转写时替换该页文本，
     * pageNo 不变；开关关 / 未装配 / 闸门失败 → 保留文本层，视觉异常不向外抛。</p>
     */
    private ParsedDocument parsePdf(InputStream content) throws Exception {
        byte[] bytes = content.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = document.getNumberOfPages();
            List<DocumentPage> pages = new ArrayList<>(pageCount);
            PdfVisionTranscriber transcriber = resolveVisionTranscriber();
            AtomicInteger visionBudget = transcriber == null ? null : transcriber.newPageBudget();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (transcriber != null && visionBudget != null) {
                    try {
                        Optional<String> visionText = transcriber.tryTranscribe(
                                document, page - 1, text, visionBudget);
                        if (visionText.isPresent()) {
                            text = visionText.get();
                        }
                    } catch (Exception ignored) {
                        // 视觉路径任何异常都不得打断整篇解析
                    }
                }
                pages.add(new DocumentPage(page, null, text));
            }
            return new ParsedDocument("pdf", pages);
        }
    }

    /**
     * 仅当 vision-pdf.enabled=true 且转写器已装配时返回实例；默认关路径零 vision 调用。
     */
    private PdfVisionTranscriber resolveVisionTranscriber() {
        if (visionTranscriberProvider == null) {
            return null;
        }
        PdfVisionTranscriber transcriber = visionTranscriberProvider.getIfAvailable();
        if (transcriber == null) {
            return null;
        }
        DynamicConfigService config = dynamicConfigProvider == null
                ? null : dynamicConfigProvider.getIfAvailable();
        Boolean enabled = config == null ? Boolean.FALSE
                : config.get(PdfVisionTranscriber.ENABLED_KEY, Boolean.class, Boolean.FALSE);
        return Boolean.TRUE.equals(enabled) ? transcriber : null;
    }

    /**
     * 使用 EasyExcel 同步读取首个工作表；每行单独保留 rowIndex。
     *
     * <p>add-excel-header-projection 任务 1.1：首行像表头时把列名投影进数据行（{@code 列名：值} tab 拼接），
     * 表头行本身不再入库；不满足启发式则与升级前逐字一致（含单列长句表）。</p>
     */
    private ParsedDocument parseExcel(InputStream content) {
        List<Map<Integer, String>> rows = EasyExcel.read(content)
                .headRowNumber(0)
                .sheet()
                .doReadSync();
        List<DocumentPage> pages = new ArrayList<>();
        if (rows.isEmpty()) {
            return new ParsedDocument("excel", pages);
        }
        Map<Integer, String> firstRow = rows.get(0);
        boolean projectHeaders = looksLikeHeaderRow(firstRow);
        Map<Integer, String> headers = projectHeaders ? buildHeaderNames(firstRow) : Map.of();

        int rowIndex = 0;
        for (Map<Integer, String> row : rows) {
            rowIndex++;
            if (projectHeaders && rowIndex == 1) {
                continue; // 列名已投影到数据行，独立表头块会占 top-K
            }
            String text = projectHeaders ? projectRow(headers, row) : normalizeRow(row);
            if (!text.isEmpty()) {
                pages.add(new DocumentPage(1, rowIndex, text));
            }
        }
        return new ParsedDocument("excel", pages);
    }

    /**
     * 表头启发式（add-excel-header-projection 任务 1.2）：非空格 ≥ 2 且每格长度 ≤ 32。
     */
    private boolean looksLikeHeaderRow(Map<Integer, String> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        int nonEmpty = 0;
        for (String raw : row.values()) {
            if (raw == null) {
                continue;
            }
            String value = raw.strip();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > HEADER_CELL_MAX_LEN) {
                return false;
            }
            nonEmpty++;
        }
        return nonEmpty >= HEADER_MIN_NON_EMPTY;
    }

    /**
     * 为表头行建立列下标 → 列名；空列名回退 {@code 列{1-based}}，重名加 {@code _2} 后缀。
     */
    private Map<Integer, String> buildHeaderNames(Map<Integer, String> headerRow) {
        Map<Integer, String> names = new HashMap<>();
        Map<String, Integer> occurrence = new HashMap<>();
        for (Integer col : headerRow.keySet().stream().sorted().toList()) {
            String raw = headerRow.get(col);
            String base = (raw == null || raw.strip().isEmpty()) ? ("列" + (col + 1)) : raw.strip();
            int seen = occurrence.merge(base, 1, Integer::sum);
            names.put(col, seen == 1 ? base : base + "_" + seen);
        }
        return names;
    }

    /**
     * 把列名投影进数据行（add-excel-header-projection 任务 1.1）：{@code 列名：值}，空值省略，tab 拼接。
     * 数据行多出的无表头列回退 {@code 列{1-based}}。
     */
    private String projectRow(Map<Integer, String> headers, Map<Integer, String> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        return row.keySet().stream().sorted()
                .map(col -> {
                    String raw = row.get(col);
                    if (raw == null) {
                        return null;
                    }
                    String value = raw.strip();
                    if (value.isEmpty()) {
                        return null;
                    }
                    String name = headers.get(col);
                    if (name == null || name.isEmpty()) {
                        name = "列" + (col + 1);
                    }
                    return name + "：" + value;
                })
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "\t" + right)
                .orElse("");
    }

    /** 升级前行为：单元格值 strip 后按列下标 tab 拼接（无列名）。 */
    private String normalizeRow(Map<Integer, String> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        return row.keySet().stream().sorted().map(row::get)
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + "\t" + right)
                .orElse("");
    }

    private String fileType(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").strip();
    }

    /** 轻量关键词提取；不引入 IK 依赖，仅保留高频中英文 token。包内可见：父块行关键词复用。 */
    List<String> extractKeywords(String text) {
        if (text.length() < MIN_TEXT_LENGTH) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = KEYWORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token)) {
                counts.merge(token, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(KEYWORD_COUNT)
                .map(Map.Entry::getKey)
                .toList();
    }
}
