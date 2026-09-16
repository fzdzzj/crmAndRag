package com.slz.crm.quality;

import com.slz.crm.knowledge.document.ChunkingStrategy;
import com.slz.crm.knowledge.document.FixedChunkingStrategy;
import com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.server.ai.CitationAligner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能基线微基准（measure-perf-baseline）：¥0、零外呼、不改热路径。
 *
 * <p>只断言跑完、块数&gt;0、无异常；ns/op 打印到 stdout，由人工抄入
 * {@code docs/perf-baseline.md}。数字不当 CI 门禁（跨机抖动）。</p>
 */
class PerfBaselineSmokeTest {

    private static final int WARMUP = 20;
    private static final int CHUNK_10K_ITERS = 200;
    private static final int CHUNK_100K_ITERS = 50;
    private static final int ALIGN_ITERS = 10_000;
    private static final int SPLIT_ITERS = 10_000;

    /** I-05 形短句 + 支撑/干扰片段（与 CitationAlignerTest 同源词面）。 */
    private static final String GOLD_ARCH = "服务台架构图注：接入层工单网关 → 调度层三班值班组 → "
            + "处理层一线/二线/三线工程师 → 数据层 SLA 台账与预警引擎；图注标注各层职责：网关负责工单登记与分流，"
            + "调度组负责时效监控与升级，处理线负责故障处置，台账负责达成率统计与赔偿核算。"
            + "预警引擎在时效阈值 80% 时提醒责任工程师，100% 时自动升级；"
            + "服务台整体时效达成率目标为 95%，连续两个月低于目标启动服务改进专项。";

    private static final String UNRELATED_A = "图注：机房平面示意图，标注 UPS 与精密空调位置，无架构分层说明。";
    private static final String UNRELATED_B = "图注：访客通道闸机布局，仅描述安防动线，不涉及服务台分层。";
    private static final String I05_ANSWER =
            "服务台分为接入层、调度层、处理层、数据层四层，数据层含台账与预警引擎[1]。";

    private static final String SPLIT_QUERY = "客户主体变更后重新签合同要满足条件";

    @Test
    void fixedChunking_10KB_recordsNsAndChunkCount() {
        String text = chineseRepeat(10 * 1024);
        FixedChunkingStrategy strategy = new FixedChunkingStrategy();
        for (int i = 0; i < WARMUP; i++) {
            strategy.split(text);
        }

        int chunks = 0;
        long start = System.nanoTime();
        for (int i = 0; i < CHUNK_10K_ITERS; i++) {
            chunks = strategy.split(text).size();
        }
        long elapsed = System.nanoTime() - start;
        double nsPerOp = elapsed * 1.0 / CHUNK_10K_ITERS;

        assertTrue(chunks > 0, "10KB fixed 切分应产出块");
        System.out.printf(
                "PERF fixed-chunk 10KB chars=%d chunks=%d iters=%d total_ns=%d ns_per_op=%.1f ns_per_char=%.3f%n",
                text.length(), chunks, CHUNK_10K_ITERS, elapsed, nsPerOp, nsPerOp / text.length());
    }

    @Test
    void fixedChunking_100KB_recordsNsAndChunkCount() {
        String text = chineseRepeat(100 * 1024);
        FixedChunkingStrategy strategy = new FixedChunkingStrategy();
        for (int i = 0; i < WARMUP; i++) {
            strategy.split(text);
        }

        int chunks = 0;
        long start = System.nanoTime();
        for (int i = 0; i < CHUNK_100K_ITERS; i++) {
            chunks = strategy.split(text).size();
        }
        long elapsed = System.nanoTime() - start;
        double nsPerOp = elapsed * 1.0 / CHUNK_100K_ITERS;

        assertTrue(chunks > 0, "100KB fixed 切分应产出块");
        System.out.printf(
                "PERF fixed-chunk 100KB chars=%d chunks=%d iters=%d total_ns=%d ns_per_op=%.1f ns_per_char=%.3f%n",
                text.length(), chunks, CHUNK_100K_ITERS, elapsed, nsPerOp, nsPerOp / text.length());
    }

    @Test
    void citationAligner_1e4_recordsNsPerOp() {
        List<SourceReference> sources = List.of(
                src(UNRELATED_A),
                src(UNRELATED_B),
                src(GOLD_ARCH));
        for (int i = 0; i < WARMUP; i++) {
            CitationAligner.align(I05_ANSWER, sources);
        }

        long start = System.nanoTime();
        CitationAligner.Alignment last = null;
        for (int i = 0; i < ALIGN_ITERS; i++) {
            last = CitationAligner.align(I05_ANSWER, sources);
        }
        long elapsed = System.nanoTime() - start;

        assertTrue(last != null && last.text() != null && !last.text().isBlank(),
                "Aligner 应产出非空对齐文本");
        System.out.printf(
                "PERF CitationAligner iters=%d total_ns=%d ns_per_op=%.1f last_citations=%s%n",
                ALIGN_ITERS, elapsed, elapsed * 1.0 / ALIGN_ITERS, last.citations());
    }

    @Test
    void constraintQuerySplitter_1e4_recordsNsPerOp() {
        for (int i = 0; i < WARMUP; i++) {
            ConstraintQuerySplitter.split(SPLIT_QUERY);
        }

        long start = System.nanoTime();
        List<String> last = List.of();
        for (int i = 0; i < SPLIT_ITERS; i++) {
            last = ConstraintQuerySplitter.split(SPLIT_QUERY);
        }
        long elapsed = System.nanoTime() - start;

        assertFalse(last.isEmpty(), "Splitter 应至少返回原查询");
        System.out.printf(
                "PERF ConstraintQuerySplitter iters=%d total_ns=%d ns_per_op=%.1f routes=%d%n",
                SPLIT_ITERS, elapsed, elapsed * 1.0 / SPLIT_ITERS, last.size());
    }

    /** 生成约 targetChars 长度的中文重复文本（BMP，1 char ≈ 1 码元）。 */
    private static String chineseRepeat(int targetChars) {
        final String unit = "服务台架构接入调度处理数据层台账预警引擎工单网关值班组一线工程师时效达成率目标";
        StringBuilder sb = new StringBuilder(targetChars + unit.length());
        while (sb.length() < targetChars) {
            sb.append(unit);
        }
        sb.setLength(targetChars);
        return sb.toString();
    }

    private static SourceReference src(String excerpt) {
        return new SourceReference(
                "md", "vector", "fixture.md", "doc-1", "chunk-1",
                0, 1, null, excerpt, 0.9);
    }
}
