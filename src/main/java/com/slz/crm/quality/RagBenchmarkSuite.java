package com.slz.crm.quality;

import com.slz.crm.quality.RagBenchmarkCase.Category;
import java.util.List;
import java.util.Set;

/**
 * 固定 RAG 质量基准集（覆盖文本/表格/图片/词法/边界五类）。
 *
 * <p>版本化固定、可比较：任何检索参数调整（分块/topK/重排/图文权重）都跑同一套基准，
 * 用指标涨跌判断优化是否引入质量回退（openspec：add-rag-quality-baseline）。
 *
 * <p>黄金片段 id 约定：本类中的 {@code expectedChunkIds} 是<b>占位 id</b>，与 {@code
 * src/test/resources/rag-quality/fixtures/} 语料中的 {@code 【GOLD:占位id】} 标记一一对应； 真检索基准运行前由数据准备
 * runner（{@code RagBenchmarkDataPreparer}）把占位 id 替换为 实际入库 chunkId，占位 id 无法对齐时显式失败，绝不静默按空集计分。
 *
 * <p>修改本套件（增删用例/改黄金片段）= 提升 {@link #SUITE_VERSION}，否则历史基线不可比。
 *
 * <p><b>版本语义（expand-rag-benchmark 任务 2.2；expand-rag-benchmark-mismatch 任务 2.3）</b>：v1.0 = 18
 * 条初始集（历史锚点 {@code docs/rag-quality/baseline-v1.json}）；v2.0 = 54 条扩容集（新锚点 {@code
 * docs/rag-quality/baseline-v2.json}）；v3.0 = 新增 MISMATCH 失配组 9 条（54 → 63，新锚点 {@code
 * docs/rag-quality/baseline-v3.json}，本卡暂未授权真跑落锚）。任一版本跨版本<b>不可直接比较</b>—— 单条权重随总数而变，
 * 只与同版报告对比（比较前先核对报告 suiteVersion）。
 */
public final class RagBenchmarkSuite {

  /**
   * 基准集版本：写入每份 {@code RagQualityReport.Report}，跨变更比较时先核对版本一致。 v1.0 = add-rag-quality-baseline 落地的
   * 18 条初始集；v2.0 = expand-rag-benchmark 扩容的 54 条（TEXT 17 / TABLE 13 / IMAGE 6 / LEXICAL 14 / EDGE
   * 4），与 v1.0 不可直接比较；v3.0 = 新增 MISMATCH 失配组 9 条（63 条：TEXT 17 / TABLE 13 / IMAGE 6 / LEXICAL 14 /
   * EDGE 4 / MISMATCH 9，expand-rag-benchmark-mismatch 任务 2.3），激活卡以 baseline-v3.json 为关态基线对照。
   */
  public static final String SUITE_VERSION = "3.0";

  private RagBenchmarkSuite() {}

  /**
   * @return 标准基准集（63 条：TEXT 17 / TABLE 13 / IMAGE 6 / LEXICAL 14 / EDGE 4 / MISMATCH 9）
   */
  public static List<RagBenchmarkCase> standard() {
    return List.of(
        // ---- TEXT：纯文本文档问答 ----
        new RagBenchmarkCase(
            "T-01",
            Category.TEXT,
            "销售合同审批流程是怎样的",
            Set.of("sales-flow-1", "sales-flow-2"),
            List.of("提交", "审批", "归档"),
            true),
        new RagBenchmarkCase(
            "T-02",
            Category.TEXT,
            "回款计划如何制定",
            Set.of("payment-plan-1"),
            List.of("账期", "回款节点"),
            true),
        new RagBenchmarkCase(
            "T-03",
            Category.TEXT,
            "合同归档有什么要求",
            Set.of("sales-flow-3"),
            List.of("纸质件", "电子件", "开票"),
            true),
        new RagBenchmarkCase(
            "T-04",
            Category.TEXT,
            "合同生效后金额要改怎么办",
            Set.of("sales-flow-4"),
            List.of("变更审批", "相同审批链"),
            true),
        new RagBenchmarkCase(
            "T-05",
            Category.TEXT,
            "回款逾期了会怎么处理",
            Set.of("payment-plan-2"),
            List.of("逾期15天", "逾期30天", "逾期60天"),
            true),
        // ---- TEXT 新增：同义改写 ×4（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "T-06",
            Category.TEXT,
            "合同提交后谁先做初审",
            Set.of("sales-flow-1"),
            List.of("销售主管", "初审"),
            true),
        new RagBenchmarkCase(
            "T-07",
            Category.TEXT,
            "合同生效后想改金额该怎么办",
            Set.of("sales-flow-4"),
            List.of("变更审批", "相同审批链"),
            true),
        new RagBenchmarkCase(
            "T-08",
            Category.TEXT,
            "回款计划由谁在什么时候制定",
            Set.of("payment-plan-1"),
            List.of("销售", "财务", "5个工作日"),
            true),
        new RagBenchmarkCase(
            "T-09",
            Category.TEXT,
            "客户欠款超期一个月会怎样",
            Set.of("payment-plan-2"),
            List.of("部门总监", "暂停新合同审批"),
            true),
        // ---- TEXT 新增：跨 chunk 关联 ×4（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "T-10",
            Category.TEXT,
            "合同从审批到归档的完整链条是什么",
            Set.of("sales-flow-1", "sales-flow-2", "sales-flow-3"),
            List.of("初审", "法务", "终审", "归档"),
            true),
        new RagBenchmarkCase(
            "T-11",
            Category.TEXT,
            "客户从建档、评级到回款策略的衔接流程是什么",
            Set.of("customer-onboard-1", "customer-onboard-2", "payment-plan-1"),
            List.of("建档", "分级", "回款策略"),
            true),
        new RagBenchmarkCase(
            "T-12",
            Category.TEXT,
            "设备维保发现故障后 SLA 的响应、升级与赔偿怎么算",
            Set.of("maint-zb220-m", "sla-response-1", "sla-response-2", "sla-credit-1"),
            List.of("响应时效", "升级机制", "赔偿"),
            true),
        new RagBenchmarkCase(
            "T-13",
            Category.TEXT,
            "数量折扣与客户类型折扣的叠加规则是什么",
            Set.of("price-tiers", "price-discount-1"),
            List.of("叠加顺序", "数量折扣", "客户类型折扣"),
            true),
        // ---- TEXT 新增：多条件组合 ×4（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "T-14",
            Category.TEXT,
            "客户主体变更后重新签合同要满足什么条件",
            Set.of("customer-onboard-4", "sales-flow-4"),
            List.of("变更申请", "风控复核", "重新签合同"),
            true),
        new RagBenchmarkCase(
            "T-15",
            Category.TEXT,
            "战略客户合同终审通过后的报备要求是什么",
            Set.of("customer-onboard-3", "sales-flow-2"),
            List.of("报备", "集团客户部"),
            true),
        new RagBenchmarkCase(
            "T-16",
            Category.TEXT,
            "逾期 60 天且客户经营困难的回款怎么处理",
            Set.of("payment-plan-2"),
            List.of("法务催款函", "债务重组", "特批"),
            true),
        new RagBenchmarkCase(
            "T-17",
            Category.TEXT,
            "华南区跨区销售并涉及价格保护需要什么手续",
            Set.of("policy-002", "policy-003", "policy-004"),
            List.of("跨区报备", "价格保护"),
            true),
        // ---- TABLE：结构化行问答（rowIndex 锚点） ----
        new RagBenchmarkCase(
            "TB-01",
            Category.TABLE,
            "第三季度各区域销售额",
            Set.of("sales-q3-华东", "sales-q3-华北"),
            List.of("华东", "华北"),
            true),
        new RagBenchmarkCase(
            "TB-02",
            Category.TABLE,
            "华南三季度达成率多少",
            Set.of("sales-q3-华南"),
            List.of("华南", "达成率"),
            true),
        new RagBenchmarkCase(
            "TB-03",
            Category.TABLE,
            "西南区域 Q3 卖了多少",
            Set.of("sales-q3-西南"),
            List.of("西南", "销售额"),
            true),
        // ---- TABLE 新增：多列交叉 ×4（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "TB-04",
            Category.TABLE,
            "XR-500 季度维保需要多少工时、几个人参与",
            Set.of("maint-xr500-q"),
            List.of("4 人时", "2 人"),
            true),
        new RagBenchmarkCase(
            "TB-05",
            Category.TABLE,
            "华东区 Q3 的销售额与达成率分别是多少",
            Set.of("sales-q3-华东"),
            List.of("1280", "112%"),
            true),
        new RagBenchmarkCase(
            "TB-06",
            Category.TABLE,
            "KQ-9000 月度维保的工时与参与人数",
            Set.of("maint-kq9000-m"),
            List.of("3 人时", "2 人"),
            true),
        new RagBenchmarkCase(
            "TB-07",
            Category.TABLE,
            "P1 级故障的响应时效与解决时效是多少",
            Set.of("sla-response-1"),
            List.of("15 分钟", "4 小时"),
            true),
        // ---- TABLE 新增：数值区间 ×3（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "TB-08",
            Category.TABLE,
            "Q3 销售额在 800 万到 1000 万之间的区域有哪些",
            Set.of("sales-q3-华北", "sales-q3-华南"),
            List.of("华北", "华南"),
            true),
        new RagBenchmarkCase(
            "TB-09",
            Category.TABLE,
            "折扣率超过 15% 的折扣申请要走什么审批",
            Set.of("price-discount-1", "price-discount-2"),
            List.of("价格委员会", "复核"),
            true),
        new RagBenchmarkCase(
            "TB-10",
            Category.TABLE,
            "计划工时超过 2 人时的维保项有哪些",
            Set.of("maint-xr500-q", "maint-kq9000-m"),
            List.of("XR-500", "KQ-9000"),
            true),
        // ---- TABLE 新增：聚合比较 ×3（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "TB-11", Category.TABLE, "Q3 哪个区域销售额最高", Set.of("sales-q3-华东"), List.of("华东"), true),
        new RagBenchmarkCase(
            "TB-12",
            Category.TABLE,
            "华南与西南哪个区域达成率更高",
            Set.of("sales-q3-华南", "sales-q3-西南"),
            List.of("华南"),
            true),
        new RagBenchmarkCase(
            "TB-13",
            Category.TABLE,
            "XR-500 年度维保工时是季度维保的几倍",
            Set.of("maint-xr500-y", "maint-xr500-q"),
            List.of("4 倍"),
            true),
        // ---- IMAGE：图片理解问答（评测语料为图注文本，走文本路召回） ----
        new RagBenchmarkCase(
            "I-01", Category.IMAGE, "这张架构图里的数据流走向", Set.of("arch-diagram-1"), List.of("数据流"), true),
        new RagBenchmarkCase(
            "I-02", Category.IMAGE, "架构图的部署结构是什么样", Set.of("arch-diagram-2"), List.of("部署"), true),
        // ---- IMAGE 新增：图注/架构/流程（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "I-03",
            Category.IMAGE,
            "客户建档流程图的图注里，建档完成后会自动触发什么",
            Set.of("customer-flow-diagram"),
            List.of("信用评级", "回款策略"),
            true),
        new RagBenchmarkCase(
            "I-04",
            Category.IMAGE,
            "折扣审批决策树图注中，超底价要谁审批",
            Set.of("price-flow-diagram"),
            List.of("副总经理", "价格委员会"),
            true),
        new RagBenchmarkCase(
            "I-05",
            Category.IMAGE,
            "服务台架构图注分了几层，数据层是什么",
            Set.of("sla-arch-diagram"),
            List.of("四层", "台账与预警引擎"),
            true),
        new RagBenchmarkCase(
            "I-06",
            Category.IMAGE,
            "维保流程图注里季度大保有什么特殊安排",
            Set.of("maint-flow-diagram"),
            List.of("厂商工程师", "保养报告"),
            true),
        // ---- LEXICAL：词法精确型；L-01/L-02 为“向量相似度低但文本精确包含目标词”的构造 ----
        new RagBenchmarkCase(
            "L-01",
            Category.LEXICAL,
            "XR-500 设备的售后对接人是谁",
            Set.of("model-xr500"),
            List.of("王建国"),
            true),
        new RagBenchmarkCase(
            "L-02",
            Category.LEXICAL,
            "ZB-220 的备件放在哪个库位",
            Set.of("model-zb220"),
            List.of("A-03"),
            true),
        new RagBenchmarkCase(
            "L-03",
            Category.LEXICAL,
            "合同编号 HT-2024-0889 签的是哪个项目",
            Set.of("code-ht0889"),
            List.of("蓝鲸计划"),
            true),
        new RagBenchmarkCase(
            "L-04",
            Category.LEXICAL,
            "凤凰计划由哪个交付组实施",
            Set.of("code-fenghuang"),
            List.of("华北交付组"),
            true),
        new RagBenchmarkCase(
            "L-05",
            Category.LEXICAL,
            "KQ-9000 的固件应该升级到哪个版本",
            Set.of("model-kq9000"),
            List.of("3.4.1"),
            true),
        new RagBenchmarkCase(
            "L-06",
            Category.LEXICAL,
            "内部代号 BK-2024 对应哪个项目",
            Set.of("code-bluewhale"),
            List.of("蓝鲸计划"),
            true),
        // ---- LEXICAL 新增：编号精确 ×3（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "L-07",
            Category.LEXICAL,
            "政策 RSP-2024-04 保护的是哪类业务",
            Set.of("policy-004"),
            List.of("价格保护"),
            true),
        new RagBenchmarkCase(
            "L-08",
            Category.LEXICAL,
            "客户编号 CUS-2026-0088 的客户经理是谁",
            Set.of("customer-code-0088"),
            List.of("周海燕"),
            true),
        new RagBenchmarkCase(
            "L-09",
            Category.LEXICAL,
            "工单 WO-2026-0521 是哪台设备的维保工单",
            Set.of("maint-xr500-q"),
            List.of("XR-500"),
            true),
        // ---- LEXICAL 新增：人名 ×3（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "L-10", Category.LEXICAL, "价格政策的负责人是谁", Set.of("price-owner"), List.of("孙丽华"), true),
        new RagBenchmarkCase(
            "L-11", Category.LEXICAL, "SLA 责任工程师是谁", Set.of("sla-owner"), List.of("何建军"), true),
        new RagBenchmarkCase(
            "L-12", Category.LEXICAL, "华东区的区域经理是谁", Set.of("policy-001"), List.of("吴国强"), true),
        // ---- LEXICAL 新增：版本号 ×2（expand-rag-benchmark 任务 2.1） ----
        new RagBenchmarkCase(
            "L-13",
            Category.LEXICAL,
            "客户管理 SOP 当前是哪个版本",
            Set.of("customer-version"),
            List.of("v4.2"),
            true),
        new RagBenchmarkCase(
            "L-14", Category.LEXICAL, "价格政策的版本号是多少", Set.of("price-owner"), List.of("v2.7"), true),
        // ---- MISMATCH：查询-文档词汇失配（expand-rag-benchmark-mismatch 任务 2.1） ----
        // question 用口语/行话之外的话面，gold chunk 用技术术语/编号/书面语；二者词面 2-gram 交集≈空（机械判据见 §0），
        // 纯召回词法命中考题救不动，考察 multi-query/HyDE/衍生问题等改写是否补漏召；答案点宽写（E 组先例）。
        new RagBenchmarkCase(
            "M-01",
            Category.MISMATCH,
            "货都卖出去一个多月了钱还没到账，有没有什么办法先把钱挪回来用",
            Set.of("jargon-factoring"),
            List.of("应收账款保理", "转让融资", "提前回笼"),
            true),
        new RagBenchmarkCase(
            "M-02",
            Category.MISMATCH,
            "银行那边批给我们的额度，到底还有多少是能直接动的",
            Set.of("jargon-credit"),
            List.of("授信敞口", "剩余可用", "支用前核对"),
            true),
        new RagBenchmarkCase(
            "M-03",
            Category.MISMATCH,
            "手里压了一张还没到期的票据，想尽早换成现钱该找谁",
            Set.of("jargon-discount"),
            List.of("承兑汇票贴现", "背书转让", "扣除利息提前兑付"),
            true),
        new RagBenchmarkCase(
            "M-04",
            Category.MISMATCH,
            "三号车间那台专门把铁板接到一起的机器，隔多久要做一回保养",
            Set.of("eq-041"),
            List.of("数控焊接机", "两周一次点检", "清理焊渣"),
            true),
        new RagBenchmarkCase(
            "M-05",
            Category.MISMATCH,
            "库房门口来回搬货的小车，一趟最多能驮多重的东西",
            Set.of("eq-107"),
            List.of("自动引导搬运车", "托盘转运", "载重六百公斤"),
            true),
        new RagBenchmarkCase(
            "M-06",
            Category.MISMATCH,
            "产线最后面那台把每箱口自动合起来的设备，一分钟能处理多少只",
            Set.of("eq-172"),
            List.of("自动封箱机", "胶带封口", "每分钟四十五件"),
            true),
        new RagBenchmarkCase(
            "M-07",
            Category.MISMATCH,
            "出差在外地过夜住店花的钱，公司这边能给报销吗",
            Set.of("expense-travel"),
            List.of("住宿费", "城市标准限额", "凭发票"),
            true),
        new RagBenchmarkCase(
            "M-08",
            Category.MISMATCH,
            "出发办事之前能不能先从单位支点钱垫着，回头多退少补",
            Set.of("expense-advance"),
            List.of("备用金借款", "垫付", "对冲结算"),
            true),
        new RagBenchmarkCase(
            "M-09",
            Category.MISMATCH,
            "报销单递上去之后，要过几层手续钱才会打到账上",
            Set.of("expense-claim"),
            List.of("逐级审核", "财务初审", "终审放款"),
            true),
        // ---- EDGE：诚实生成语义（D16） ----
        // 边界1：闲聊，KB OFF——绝不该触发检索或注入未命中提示
        new RagBenchmarkCase("E-01", Category.EDGE, "你好，在吗", Set.of(), List.of(), false),
        // 边界2：KB ON 但知识库无此主题——应诚实生成、不伪造来源（D16）
        new RagBenchmarkCase("E-02", Category.EDGE, "关于虚构主题 ZYX-9 的规定", Set.of(), List.of(), true),
        // ---- EDGE 新增（expand-rag-benchmark 任务 2.1） ----
        // 边界3：近义闲聊（KB OFF）：换个措辞的寒暄仍属闲聊，不得触发检索
        new RagBenchmarkCase("E-03", Category.EDGE, "嗨，在忙吗", Set.of(), List.of(), false),
        // 边界4：近邻主题误导（KB ON）：知识库有"设备/租赁/政策"等近邻主题，
        // 但"量子计算设备"不存在——应诚实拒答、不借近邻内容编造（D16）
        new RagBenchmarkCase("E-04", Category.EDGE, "量子计算设备的租赁政策是什么", Set.of(), List.of(), true));
  }
}
