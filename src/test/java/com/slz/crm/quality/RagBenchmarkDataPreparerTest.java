package com.slz.crm.quality;

import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.quality.RagBenchmarkCase.Category;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据准备 runner 单测（add-rag-quality-baseline 任务 2.3/2.4；expand-rag-benchmark 任务 3.1 扩容）。
 *
 * <p>用确定性 fake embedder 驱动<b>真实 fixtures + 真实 {@code DocumentService} 分块</b>，
 * 不需要模型 key 与外网：幂等性、对齐拒绝、套件占位 id 与语料 GOLD 标记的同步关系
 * 都在这里机械保证——fixtures 改版导致对齐破坏时会在此先红，而不是等真跑基线才发现。</p>
 */
class RagBenchmarkDataPreparerTest {

    private static final long EVAL_KB_ID = 99001L;

    /**
     * 确定性 fake 向量：按字符位置累加到固定 64 维槽位，同一文本永远得到同一向量，
     * 不同文本得到不同向量——足够让 InMemoryVectorStore 的余弦检索在单测里正常工作。
     */
    private static float[] fakeEmbed(String text) {
        float[] vector = new float[64];
        for (int i = 0; i < text.length(); i++) {
            vector[(text.charAt(i) * 31 + i) % vector.length] += 1f;
        }
        if (text.isEmpty()) {
            vector[0] = 1f;
        }
        return vector;
    }

    @Test
    void rerunProducesIdenticalChunkIdSetAndMapping() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        RagBenchmarkDataPreparer.Preparation first =
                RagBenchmarkDataPreparer.prepare(store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);
        Set<String> firstChunkIds = new HashSet<>(first.chunkIds());
        Map<String, String> firstMapping = first.goldenToChunkId();

        // 同一环境连续跑第二次：chunkId 集合与黄金映射必须完全一致（幂等，任务 2.3）
        RagBenchmarkDataPreparer.Preparation second =
                RagBenchmarkDataPreparer.prepare(store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);
        assertEquals(firstChunkIds, second.chunkIds(), "重跑后 chunkId 集合必须一致");
        assertEquals(firstMapping, second.goldenToChunkId(), "重跑后黄金映射必须一致");

        // 换一个全新向量库再跑一遍：产出仍然一致（幂等不依赖库内残留状态）
        RagBenchmarkDataPreparer.Preparation fresh =
                RagBenchmarkDataPreparer.prepare(new InMemoryVectorStore(), RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);
        assertEquals(firstChunkIds, fresh.chunkIds(), "全新向量库产出的 chunkId 集合必须一致");

        // 十一份语料全部产出了切片
        assertEquals(11, first.chunkIds().stream().map(id -> id.substring(0, id.lastIndexOf('-'))).distinct().count());
    }

    @Test
    void unalignedGoldenIdFailsExplicitlyInsteadOfSilentEmptySet() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        RagBenchmarkDataPreparer.Preparation preparation =
                RagBenchmarkDataPreparer.prepare(store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

        // 已对齐的占位 id 不应报错
        RagBenchmarkDataPreparer.validateAlignment(Set.of("sales-flow-1", "model-xr500"), preparation);
        // 未对齐的占位 id 必须显式失败，并把 id 报出来（任务 2.4：拒绝静默空集）
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> RagBenchmarkDataPreparer.validateAlignment(Set.of("ghost-golden"), preparation));
        assertTrue(exception.getMessage().contains("ghost-golden"),
                "失败信息必须包含未对齐的占位 id，实际=" + exception.getMessage());
    }

    @Test
    void expandedSuiteV2SizeAndCategoryBreakdown() {
        // 扩容后基准集规模与分类配比（expand-rag-benchmark 任务 3.1）：总数 54，五类 17/13/6/14/4
        List<RagBenchmarkCase> suite = RagBenchmarkSuite.standard();
        assertEquals(54, suite.size(), "扩容后基准集总条数必须为 54");
        Map<Category, Long> categoryCounts = suite.stream()
                .collect(Collectors.groupingBy(RagBenchmarkCase::category, Collectors.counting()));
        assertEquals(17L, categoryCounts.get(Category.TEXT), "TEXT 分类条数");
        assertEquals(13L, categoryCounts.get(Category.TABLE), "TABLE 分类条数");
        assertEquals(6L, categoryCounts.get(Category.IMAGE), "IMAGE 分类条数");
        assertEquals(14L, categoryCounts.get(Category.LEXICAL), "LEXICAL 分类条数");
        assertEquals(4L, categoryCounts.get(Category.EDGE), "EDGE 分类条数");
    }

    @Test
    void standardSuitePlaceholdersAllAlignToRealFixtureChunks() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        RagBenchmarkDataPreparer.Preparation preparation =
                RagBenchmarkDataPreparer.prepare(store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

        Set<String> placeholders = RagBenchmarkSuite.standard().stream()
                .flatMap(c -> c.expectedChunkIds().stream())
                .collect(Collectors.toSet());
        // 套件与语料同步：全部占位 id 都能在语料 GOLD 标记里找到，且一一对应（多、少、拼错都算失败）
        assertEquals(Set.copyOf(preparation.goldenToChunkId().keySet()), placeholders,
                "基准集占位 id 必须与语料 GOLD 标记一一对应（多、少、拼错都算失败）");

        List<RagBenchmarkCase> rewritten = RagBenchmarkDataPreparer.rewriteSuite(RagBenchmarkSuite.standard(), preparation);
        for (RagBenchmarkCase c : rewritten) {
            if (c.category() == Category.EDGE) {
                assertTrue(c.expectedChunkIds().isEmpty(), "边界用例期望片段必须为空: " + c.id());
                continue;
            }
            assertFalse(c.expectedChunkIds().isEmpty(), "非边界用例必须保留黄金片段: " + c.id());
            assertTrue(preparation.chunkIds().containsAll(c.expectedChunkIds()),
                    "改写后的黄金 id 必须都是真实入库 chunkId: " + c.id());
        }
        // 黄金片段分布健康度：44 个占位 id 至少映射到 30 个不同切片，防止全部挤在同一切片让 recall 失真
        assertTrue(new HashSet<>(preparation.goldenToChunkId().values()).size() >= 30,
                "黄金片段过于集中，请检查语料段落长度: " + preparation.goldenToChunkId());
    }

    /**
     * add-excel-header-projection 任务 2.4：维保/销售黄金行索引文本含列名；GOLD 标记仍剥离。
     */
    @Test
    void excelGoldenRowsIndexedTextContainsProjectedColumnNames() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        RagBenchmarkDataPreparer.Preparation preparation =
                RagBenchmarkDataPreparer.prepare(store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

        String maintChunkId = preparation.goldenToChunkId().get("maint-xr500-q");
        String salesChunkId = preparation.goldenToChunkId().get("sales-q3-华东");
        assertTrue(maintChunkId != null, "维保黄金占位 id 必须对齐");
        assertTrue(salesChunkId != null, "销售黄金占位 id 必须对齐");

        String maintText = preparation.chunks().stream()
                .filter(c -> c.chunkId().equals(maintChunkId))
                .map(RagBenchmarkDataPreparer.BenchmarkChunk::text)
                .findFirst()
                .orElseThrow();
        String salesText = preparation.chunks().stream()
                .filter(c -> c.chunkId().equals(salesChunkId))
                .map(RagBenchmarkDataPreparer.BenchmarkChunk::text)
                .findFirst()
                .orElseThrow();

        assertTrue(maintText.contains("计划工时"), "维保黄金行索引文本须含列名「计划工时」: " + maintText);
        assertTrue(salesText.contains("销售额"), "销售黄金行索引文本须含列名「销售额」: " + salesText);
        assertFalse(maintText.contains("【GOLD"), "GOLD 标记必须已剥离: " + maintText);
        assertFalse(salesText.contains("【GOLD"), "GOLD 标记必须已剥离: " + salesText);
    }

    @Test
    void indexedTextIsCleanAndMetadataMatchesRetrievalFilter() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        RagBenchmarkDataPreparer.Preparation preparation =
                RagBenchmarkDataPreparer.prepare(store, RagBenchmarkDataPreparerTest::fakeEmbed, EVAL_KB_ID);

        // 索引文本不残留评测脚手架标记
        for (String text : preparation.chunkTexts()) {
            assertFalse(text.contains("【GOLD"), "索引文本不得包含 GOLD 标记: " + text);
            assertFalse(text.isBlank(), "索引文本不得为空（标记剥离后只剩空白说明语料段落为空）");
        }

        // 检索侧按 knowledgeBaseId 过滤能命中本语料，metadata 与生产入库同口径
        List<VectorSearchHit> hits = store.search(new VectorSearchRequest(
                fakeEmbed("合同审批流程"), 10, 0.0, Map.of("knowledgeBaseId", String.valueOf(EVAL_KB_ID))));
        assertFalse(hits.isEmpty(), "按评测知识库过滤必须能命中入库切片");
        for (VectorSearchHit hit : hits) {
            assertEquals(String.valueOf(EVAL_KB_ID), hit.metadata().get("knowledgeBaseId"));
            assertTrue(preparation.chunkIds().contains(hit.chunkId()),
                    "命中 chunkId 必须来自本次准备产出: " + hit.chunkId());
            assertTrue(hit.metadata().containsKey("filename"));
            assertTrue(hit.metadata().containsKey("chunkId"));
        }
    }
}
