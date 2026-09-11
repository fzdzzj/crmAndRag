package com.slz.crm.unit.knowledge.document;

import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档解析与页级分块测试。
 */
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
}
