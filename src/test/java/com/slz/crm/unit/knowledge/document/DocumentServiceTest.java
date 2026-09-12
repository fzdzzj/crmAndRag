package com.slz.crm.unit.knowledge.document;

import com.alibaba.excel.EasyExcel;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.platform.contract.DynamicConfigService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文档解析与页级分块测试。
 *
 * <p>提案4 任务 1.1/1.3/1.4：fixed 策略与升级前算法逐字等价、semantic 经动态配置激活、
 * 任何策略下 pageNo/rowIndex 锚点保留（D15 页级引用是冻结行为）、小节完整性对比。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {
    private final DocumentService documentService = new DocumentService();

    @Test
    void txtShouldSplitIntoChunksWithPageAnchor() throws Exception {
        String text = ("销售过程管理是CRM系统中的关键能力，客户、商机、合同与回款数据需要保持一致，"
                + "同时支持角色权限和部门数据范围过滤。".repeat(25));
        List<DocumentChunk> chunks = documentService.process(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "sales.txt", "crm");

        assertTrue(chunks.size() > 1, "长文本应产生多个切片");
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals(1, chunks.get(1).chunkIndex());
        assertEquals(1, chunks.get(0).pageNo());
        assertEquals("crm", chunks.get(0).category());
    }

    @Test
    void unsupportedFilenameShouldBeRejected() {
        assertTrue(!documentService.supports("invoice.docx"));
    }

    /** 任务 1.1：fixed 策略必须与升级前内联算法（320/40 滑窗）同输入同 chunk 序列。 */
    @Test
    void fixedStrategyMustMatchLegacyAlgorithm() throws Exception {
        List<String> inputs = List.of(
                "销售过程管理是CRM系统中的关键能力，客户、商机、合同与回款数据需要保持一致。".repeat(40),
                "第一行内容，合同审批流程说明。\n第二行内容，回款节点与逾期处理口径。\n第三行内容，法务介入条件。",
                "短文本不足一刀",
                "甲".repeat(321));
        for (String input : inputs) {
            List<DocumentChunk> chunks = documentService.process(
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), "legacy.txt", "crm");
            assertEquals(legacySplit(normalizeLikeService(input)), chunks.stream().map(DocumentChunk::text).toList(),
                    "fixed 策略切分序列必须与升级前算法一致");
            assertTrue(chunks.stream().allMatch(c -> c.parentText() == null),
                    "fixed 策略不产生父块（parentText 恒 null）");
        }
    }

    /** 任务 1.1：semantic 经 rag.chunking.strategy 显式开启；无配置/无 provider 一律 fixed。 */
    @Test
    @SuppressWarnings("unchecked")
    void semanticStrategyActivatesOnlyViaDynamicConfig() throws Exception {
        String markdown = "# 甲节\n" + "甲节内容句子完整收尾。".repeat(60);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        // 无配置（无 provider 的无参构造）= 升级前 fixed 行为
        List<DocumentChunk> plain = documentService.process(
                new ByteArrayInputStream(bytes), "doc.md", "crm");
        assertEquals(legacySplitCount(normalizeLikeService(markdown)), plain.size(),
                "未配置策略时必须保持升级前滑窗行为");
        assertTrue(plain.stream().allMatch(c -> c.parentText() == null),
                "fixed 策略不产生父块");

        // 显式 semantic：受 max-chunk-size 约束并产生父块全文
        ObjectProvider<DynamicConfigService> provider = mock(ObjectProvider.class);
        DynamicConfigService config = mock(DynamicConfigService.class);
        when(provider.getIfAvailable()).thenReturn(config);
        when(config.get(eq(DocumentService.STRATEGY_KEY), eq(String.class), any())).thenReturn("semantic");
        when(config.get(eq(DocumentService.MAX_CHUNK_SIZE_KEY), eq(Integer.class), any())).thenReturn(200);
        List<DocumentChunk> semantic = new DocumentService(provider).process(
                new ByteArrayInputStream(bytes), "doc.md", "crm");
        assertTrue(semantic.stream().allMatch(c -> c.text().length() <= 200),
                "semantic 切片须受 max-chunk-size 约束");
        assertTrue(semantic.stream().anyMatch(c -> c.parentText() != null),
                "semantic 超上限逻辑段必须产生父块全文");

        // 切回 fixed：切分序列与无参构造（升级前行为）逐字一致
        when(config.get(eq(DocumentService.STRATEGY_KEY), eq(String.class), any())).thenReturn("fixed");
        List<DocumentChunk> fixed = new DocumentService(provider).process(
                new ByteArrayInputStream(bytes), "doc.md", "crm");
        assertEquals(plain.stream().map(DocumentChunk::text).toList(),
                fixed.stream().map(DocumentChunk::text).toList(),
                "显式 fixed 与升级前行为必须逐字一致");
    }

    /** 任务 1.3：semantic 策略下 PDF 按页保留 pageNo（D15 页级引用冻结行为）。 */
    @Test
    @SuppressWarnings("unchecked")
    void semanticStrategyPreservesPageNoAnchorsOnPdf() throws Exception {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (int page = 1; page <= 2; page++) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                document.addPage(pdPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(40, 760);
                    // 12 行排版保证坐标在页内；单页文本超长（12×3×34≈1224 字符）迫使页内多切片
                    String marker = page == 1 ? "pageone" : "pagetwo";
                    String line = (marker + " contract clause sentence. ").repeat(3);
                    for (int row = 0; row < 12; row++) {
                        content.showText(line);
                        content.newLineAtOffset(0, -14);
                    }
                    content.endText();
                }
            }
            document.save(pdf);
        }

        List<DocumentChunk> chunks = semanticService().process(
                new ByteArrayInputStream(pdf.toByteArray()), "contract.pdf", "crm");
        assertTrue(chunks.size() > 2, "两页文档应产出多切片: " + chunks.size());
        for (DocumentChunk chunk : chunks) {
            if (chunk.text().contains("pageone")) {
                assertEquals(1, chunk.pageNo(), "第 1 页内容必须携带 pageNo=1: " + chunk.text());
            }
            if (chunk.text().contains("pagetwo")) {
                assertEquals(2, chunk.pageNo(), "第 2 页内容必须携带 pageNo=2: " + chunk.text());
            }
        }
    }

    /** 任务 1.3：semantic 策略下 Excel 按行保留 rowIndex。 */
    @Test
    @SuppressWarnings("unchecked")
    void semanticStrategyPreservesRowIndexAnchorsOnExcel() throws Exception {
        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        List<List<String>> rows = List.of(
                List.of("客户甲，签约金额八十万，回款计划按季度执行。"),
                List.of("客户乙，签约金额一百二十万，超长行内容。".repeat(30)),
                List.of("客户丙，商机推进中。"));
        EasyExcel.write(xlsx).sheet("data").doWrite(rows);

        List<DocumentChunk> chunks = semanticService().process(
                new ByteArrayInputStream(xlsx.toByteArray()), "plans.xlsx", "crm");
        assertTrue(chunks.size() > 3, "超长行应产出多切片: " + chunks.size());
        for (DocumentChunk chunk : chunks) {
            if (chunk.text().contains("客户甲")) {
                assertEquals(1, chunk.rowIndex(), "第 1 行内容必须携带 rowIndex=1");
            }
            if (chunk.text().contains("客户乙")) {
                assertEquals(2, chunk.rowIndex(), "第 2 行内容必须携带 rowIndex=2（超长行多切片同锚点）");
            }
            if (chunk.text().contains("客户丙")) {
                assertEquals(3, chunk.rowIndex(), "第 3 行内容必须携带 rowIndex=3");
            }
        }
    }

    /** 任务 1.4 质量对比：小节内容装得下时 semantic 保持小节完整，fixed 滑窗会切在段中间。 */
    @Test
    @SuppressWarnings("unchecked")
    void semanticKeepsSectionIntactWhileFixedSplitsMidSection() throws Exception {
        // 每节约 350 字：semantic（上限 480）单块装下；fixed（320）必然每节劈成两片
        String doc = "# 安装\n" + "安装步骤说明，安装前需要逐项检查运行环境。".repeat(20) + "\n\n"
                + "# 卸载\n" + "卸载步骤说明，卸载后需要清理全部残留数据。".repeat(20);
        byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);

        List<DocumentChunk> semantic = semanticService().process(
                new ByteArrayInputStream(bytes), "guide.md", "crm");
        assertEquals(2, semantic.size(), "semantic 应两小节各一块");
        assertTrue(semantic.get(0).text().contains("安装") && !semantic.get(0).text().contains("卸载"));
        assertTrue(semantic.get(1).text().contains("卸载") && !semantic.get(1).text().contains("安装"));

        List<DocumentChunk> fixed = documentService.process(new ByteArrayInputStream(bytes), "guide.md", "crm");
        assertTrue(fixed.size() > 2, "fixed 320 滑窗应把两节劈成更多片: " + fixed.size());
        assertTrue(fixed.stream().anyMatch(c -> c.text().contains("安装") && c.text().contains("卸载")),
                "fixed 滑窗必然存在跨小节混片的切片（话题断裂）");
    }

    /** semantic 配置下的 DocumentService（mock 动态配置，上限 480）。 */
    @SuppressWarnings("unchecked")
    private DocumentService semanticService() {
        ObjectProvider<DynamicConfigService> provider = mock(ObjectProvider.class);
        DynamicConfigService config = mock(DynamicConfigService.class);
        when(provider.getIfAvailable()).thenReturn(config);
        when(config.get(eq(DocumentService.STRATEGY_KEY), eq(String.class), any())).thenReturn("semantic");
        when(config.get(eq(DocumentService.MAX_CHUNK_SIZE_KEY), eq(Integer.class), any())).thenReturn(480);
        return new DocumentService(provider);
    }

    /** 升级前内联算法的逐字参考实现（normalize 同口径后 320/40 滑窗）。 */
    private static List<String> legacySplit(String normalized) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + 320, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - 40, start + 1);
        }
        return chunks;
    }

    private static int legacySplitCount(String normalized) {
        return legacySplit(normalized).size();
    }

    /** 与 DocumentService.normalize 同口径的归一化（测试参考实现用）。 */
    private static String normalizeLikeService(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").strip();
    }
}
