package com.slz.crm.knowledge.document;

import com.alibaba.excel.EasyExcel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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
 * <p>按页保留 pageNo；Excel 按行保留 rowIndex，满足 D15 来源锚点要求。</p>
 */
@Service
public class DocumentService {
    private static final int CHUNK_SIZE = 320;
    private static final int CHUNK_OVERLAP = 40;
    private static final int MIN_TEXT_LENGTH = 80;
    private static final int KEYWORD_COUNT = 6;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "txt", "md", "markdown", "xlsx", "xls");
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,30}|[\\p{IsHan}]{2,24}");
    private static final Set<String> STOP_WORDS = Set.of("我们", "你们", "他们", "是否", "已经", "进行", "可以",
            "需要", "因为", "所以", "为了", "如果", "或者", "以及", "其中", "通过", "对于", "相关");

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
        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentPage page : parsed.pages()) {
            String normalized = normalize(page.text());
            if (normalized.isEmpty()) {
                continue;
            }
            int start = 0;
            while (start < normalized.length()) {
                int end = Math.min(start + CHUNK_SIZE, normalized.length());
                String text = normalized.substring(start, end);
                chunks.add(new DocumentChunk(
                        text,
                        chunks.size(),
                        page.pageNo(),
                        page.rowIndex(),
                        category,
                        extractKeywords(text)));
                if (end >= normalized.length()) {
                    break;
                }
                start = Math.max(end - CHUNK_OVERLAP, start + 1);
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

    private ParsedDocument parse(InputStream content, String filename, String fileType) throws Exception {
        return switch (fileType) {
            case "txt", "md", "markdown" -> new ParsedDocument(fileType, List.of(new DocumentPage(
                    1, null, new String(content.readAllBytes(), StandardCharsets.UTF_8))));
            case "pdf" -> parsePdf(content);
            case "xlsx", "xls" -> parseExcel(content);
            default -> throw new IllegalStateException("不支持的文件类型: " + fileType);
        };
    }

    /** PDFBox 直连解析；按页抽取并保留 pageNo，供页级来源引用使用。 */
    private ParsedDocument parsePdf(InputStream content) throws Exception {
        byte[] bytes = content.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = document.getNumberOfPages();
            List<DocumentPage> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                pages.add(new DocumentPage(page, null, text));
            }
            return new ParsedDocument("pdf", pages);
        }
    }

    /** 使用 EasyExcel 同步读取首个工作表；每行单独保留 rowIndex。 */
    private ParsedDocument parseExcel(InputStream content) {
        List<Map<Integer, String>> rows = EasyExcel.read(content)
                .headRowNumber(0)
                .sheet()
                .doReadSync();
        List<DocumentPage> pages = new ArrayList<>();
        int rowIndex = 0;
        for (Map<Integer, String> row : rows) {
            rowIndex++;
            String text = normalizeRow(row);
            if (!text.isEmpty()) {
                pages.add(new DocumentPage(1, rowIndex, text));
            }
        }
        return new ParsedDocument("excel", pages);
    }

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

    /** 轻量关键词提取；不引入 IK 依赖，仅保留高频中英文 token。 */
    private List<String> extractKeywords(String text) {
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
