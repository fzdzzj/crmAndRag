package com.slz.crm.quality;

import java.util.Map;

/**
 * 基线五跑阶梯的 run profile（run-baseline-ladder 任务组 2）。
 *
 * <p>每个 profile 定义报告落盘名、切分策略（fixed/semantic，进 {@code rag.chunking.strategy}）、
 * 当跑开关矩阵（DynamicConfig 桩背书，production 键域）、稀疏路/上下文是否装配。矩阵快照随报告 JSON
 * 落盘，凭报告可复现该跑（spec：rag-quality 基线阶梯可复现）。</p>
 *
 * <p>键域即生产读取键：{@code rag.retrieval.fusion.mode} / {@code rag.context.neighbors} /
 * {@code rag.context.compressor.mode} / {@code rag.context.token-budget} /
 * {@code rag.context.parent-expand} / {@code rag.chunking.strategy}（详见各消费类常量）。</p>
 */
enum RagBenchmarkRun {

    /** 回退态（现 runner 原样，旧构造器）：纯向量单路 + plainNumbered；跑1 锚点。 */
    V1("docs/rag-quality/baseline-v1.json", "fixed", Map.of(), false, false),

    /** 跑2 +融合=rrf + 稀疏路 on（其余回退）。 */
    HYBRID("docs/rag-quality/baseline-after-hybrid.json", "fixed",
            Map.of("rag.retrieval.fusion.mode", "rrf"), true, false),

    /** 跑3 承 3.1 + neighbors=1 + compressor=rule + parent-expand=off（token-budget 紧致以触发压缩）。 */
    CONTEXT("docs/rag-quality/baseline-after-context.json", "fixed",
            Map.of("rag.retrieval.fusion.mode", "rrf",
                    "rag.context.neighbors", 1,
                    "rag.context.compressor.mode", "rule",
                    "rag.context.token-budget", 1024,
                    "rag.context.parent-expand", "off"), true, true),

    /** 跑4 承 4.1 + chunking=semantic + parent-expand=on（fixtures 语义重嵌入）。 */
    CHUNKING("docs/rag-quality/baseline-after-chunking.json", "semantic",
            Map.of("rag.retrieval.fusion.mode", "rrf",
                    "rag.context.neighbors", 1,
                    "rag.context.compressor.mode", "rule",
                    "rag.context.token-budget", 1024,
                    "rag.context.parent-expand", "on",
                    "rag.chunking.strategy", "semantic"), true, true),

    /** 跑5（视机械触发起因判定执行）：+ multi-query on；触发不成立时只回填结论不执行。 */
    QUERY("docs/rag-quality/baseline-after-query.json", "semantic",
            Map.of("rag.retrieval.fusion.mode", "rrf",
                    "rag.context.neighbors", 1,
                    "rag.context.compressor.mode", "rule",
                    "rag.context.token-budget", 1024,
                    "rag.context.parent-expand", "on",
                    "rag.chunking.strategy", "semantic",
                    "rag.retrieval.multi-query.enabled", true), true, true);

    final String outFile;
    final String chunking;
    final Map<String, Object> matrix;
    final boolean sparseOn;
    final boolean contextOn;

    RagBenchmarkRun(String outFile, String chunking, Map<String, Object> matrix,
                    boolean sparseOn, boolean contextOn) {
        this.outFile = outFile;
        this.chunking = chunking;
        this.matrix = matrix;
        this.sparseOn = sparseOn;
        this.contextOn = contextOn;
    }

    /** 按名称解析 profile（默认 v1）。 */
    static RagBenchmarkRun from(String name) {
        for (RagBenchmarkRun run : values()) {
            if (run.name().equalsIgnoreCase(name)) {
                return run;
            }
        }
        return V1;
    }
}