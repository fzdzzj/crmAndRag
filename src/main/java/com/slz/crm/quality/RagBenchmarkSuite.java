package com.slz.crm.quality;

import com.slz.crm.quality.RagBenchmarkCase.Category;

import java.util.List;
import java.util.Set;

/**
 * 固定 RAG 质量基准集（覆盖文本/表格/图片/词法/边界五类）。
 *
 * <p>版本化固定、可比较：任何检索参数调整（分块/topK/重排/图文权重）都跑同一套基准，
 * 用指标涨跌判断优化是否引入质量回退（openspec：add-rag-quality-baseline）。</p>
 *
 * <p>黄金片段 id 约定：本类中的 {@code expectedChunkIds} 是<b>占位 id</b>，与
 * {@code src/test/resources/rag-quality/fixtures/} 语料中的 {@code 【GOLD:占位id】} 标记一一对应；
 * 真检索基准运行前由数据准备 runner（{@code RagBenchmarkDataPreparer}）把占位 id 替换为
 * 实际入库 chunkId，占位 id 无法对齐时显式失败，绝不静默按空集计分。</p>
 *
 * <p>修改本套件（增删用例/改黄金片段）= 提升 {@link #SUITE_VERSION}，否则历史基线不可比。</p>
 */
public final class RagBenchmarkSuite {

    /**
     * 基准集版本：写入每份 {@code RagQualityReport.Report}，跨变更比较时先核对版本一致。
     * v1.0 = add-rag-quality-baseline 落地的 18 条初始集。
     */
    public static final String SUITE_VERSION = "1.0";

    private RagBenchmarkSuite() {
    }

    /**
     * @return 标准基准集（18 条：TEXT 5 / TABLE 3 / IMAGE 2 / LEXICAL 6 / EDGE 2）
     */
    public static List<RagBenchmarkCase> standard() {
        return List.of(
                // ---- TEXT：纯文本文档问答 ----
                new RagBenchmarkCase("T-01", Category.TEXT, "销售合同审批流程是怎样的",
                        Set.of("sales-flow-1", "sales-flow-2"),
                        List.of("提交", "审批", "归档"), true),
                new RagBenchmarkCase("T-02", Category.TEXT, "回款计划如何制定",
                        Set.of("payment-plan-1"),
                        List.of("账期", "回款节点"), true),
                new RagBenchmarkCase("T-03", Category.TEXT, "合同归档有什么要求",
                        Set.of("sales-flow-3"),
                        List.of("纸质件", "电子件", "开票"), true),
                new RagBenchmarkCase("T-04", Category.TEXT, "合同生效后金额要改怎么办",
                        Set.of("sales-flow-4"),
                        List.of("变更审批", "相同审批链"), true),
                new RagBenchmarkCase("T-05", Category.TEXT, "回款逾期了会怎么处理",
                        Set.of("payment-plan-2"),
                        List.of("逾期15天", "逾期30天", "逾期60天"), true),
                // ---- TABLE：结构化行问答（rowIndex 锚点） ----
                new RagBenchmarkCase("TB-01", Category.TABLE, "第三季度各区域销售额",
                        Set.of("sales-q3-华东", "sales-q3-华北"),
                        List.of("华东", "华北"), true),
                new RagBenchmarkCase("TB-02", Category.TABLE, "华南三季度达成率多少",
                        Set.of("sales-q3-华南"),
                        List.of("华南", "达成率"), true),
                new RagBenchmarkCase("TB-03", Category.TABLE, "西南区域 Q3 卖了多少",
                        Set.of("sales-q3-西南"),
                        List.of("西南", "销售额"), true),
                // ---- IMAGE：图片理解问答（评测语料为图注文本，走文本路召回） ----
                new RagBenchmarkCase("I-01", Category.IMAGE, "这张架构图里的数据流走向",
                        Set.of("arch-diagram-1"),
                        List.of("数据流"), true),
                new RagBenchmarkCase("I-02", Category.IMAGE, "架构图的部署结构是什么样",
                        Set.of("arch-diagram-2"),
                        List.of("部署"), true),
                // ---- LEXICAL：词法精确型；L-01/L-02 为“向量相似度低但文本精确包含目标词”的构造 ----
                new RagBenchmarkCase("L-01", Category.LEXICAL, "XR-500 设备的售后对接人是谁",
                        Set.of("model-xr500"),
                        List.of("王建国"), true),
                new RagBenchmarkCase("L-02", Category.LEXICAL, "ZB-220 的备件放在哪个库位",
                        Set.of("model-zb220"),
                        List.of("A-03"), true),
                new RagBenchmarkCase("L-03", Category.LEXICAL, "合同编号 HT-2024-0889 签的是哪个项目",
                        Set.of("code-ht0889"),
                        List.of("蓝鲸计划"), true),
                new RagBenchmarkCase("L-04", Category.LEXICAL, "凤凰计划由哪个交付组实施",
                        Set.of("code-fenghuang"),
                        List.of("华北交付组"), true),
                new RagBenchmarkCase("L-05", Category.LEXICAL, "KQ-9000 的固件应该升级到哪个版本",
                        Set.of("model-kq9000"),
                        List.of("3.4.1"), true),
                new RagBenchmarkCase("L-06", Category.LEXICAL, "内部代号 BK-2024 对应哪个项目",
                        Set.of("code-bluewhale"),
                        List.of("蓝鲸计划"), true),
                // ---- EDGE：诚实生成语义（D16） ----
                // 边界1：闲聊，KB OFF——绝不该触发检索或注入未命中提示
                new RagBenchmarkCase("E-01", Category.EDGE, "你好，在吗",
                        Set.of(), List.of(), false),
                // 边界2：KB ON 但知识库无此主题——应诚实生成、不伪造来源（D16）
                new RagBenchmarkCase("E-02", Category.EDGE, "关于虚构主题 ZYX-9 的规定",
                        Set.of(), List.of(), true));
    }
}
