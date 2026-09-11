package com.slz.crm.quality;

import com.slz.crm.quality.RagBenchmarkCase.Category;

import java.util.List;
import java.util.Set;

/**
 * 固定 RAG 质量基准集（task17：覆盖文本/表格/图片/边界四类）。
 *
 * <p>版本化固定、可比较：任何检索参数调整（分块/topK/重排/图文权重）都跑同一套基准，
 * 用指标涨跌判断优化是否引入质量回退。真实黄金片段 id 需与入库向量库的 chunkId 对齐，
 * CI/生产跑评估前由数据准备步骤灌入对应文档。</p>
 */
public final class RagBenchmarkSuite {

    private RagBenchmarkSuite() {
    }

    /**
     * @return 标准基准集（6 条，四类全覆盖 + 2 条边界）
     */
    public static List<RagBenchmarkCase> standard() {
        return List.of(
                new RagBenchmarkCase("T-01", Category.TEXT, "销售合同审批流程是怎样的",
                        Set.of("sales-flow-1", "sales-flow-2"),
                        List.of("提交", "审批", "归档"), true),
                new RagBenchmarkCase("T-02", Category.TEXT, "回款计划如何制定",
                        Set.of("payment-plan-1"),
                        List.of("账期", "回款节点"), true),
                new RagBenchmarkCase("TB-01", Category.TABLE, "第三季度各区域销售额",
                        Set.of("sales-q3-华东", "sales-q3-华北"),
                        List.of("华东", "华北"), true),
                new RagBenchmarkCase("I-01", Category.IMAGE, "这张架构图里的数据流走向",
                        Set.of("arch-diagram-1"),
                        List.of("数据流"), true),
                // 边界1：闲聊，KB OFF——绝不该触发检索或注入未命中提示
                new RagBenchmarkCase("E-01", Category.EDGE, "你好，在吗",
                        Set.of(), List.of(), false),
                // 边界2：KB ON 但知识库无此主题——应诚实生成、不伪造来源（D16）
                new RagBenchmarkCase("E-02", Category.EDGE, "关于虚构主题 ZYX-9 的规定",
                        Set.of(), List.of(), true));
    }
}
