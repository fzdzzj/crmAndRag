package com.slz.crm.unit.ai;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.server.ai.CitationAligner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CitationAligner 行为夹具。
 *
 * <p>fix-citation-alignment 任务 1.2：覆盖 I-05 形 remap / 已对齐不动 / 平局保原号 /
 * 无支撑删号 / 空 sources·无编号·越界 / 不补漏引。</p>
 */
class CitationAlignerTest {

    /** sla-terms.md 【GOLD:sla-arch-diagram】词面（含「四层」「台账与预警引擎」）。 */
    private static final String GOLD_ARCH = "服务台架构图注：接入层工单网关 → 调度层三班值班组 → "
            + "处理层一线/二线/三线工程师 → 数据层 SLA 台账与预警引擎；图注标注各层职责：网关负责工单登记与分流，"
            + "调度组负责时效监控与升级，处理线负责故障处置，台账负责达成率统计与赔偿核算。"
            + "预警引擎在时效阈值 80% 时提醒责任工程师，100% 时自动升级；"
            + "服务台整体时效达成率目标为 95%，连续两个月低于目标启动服务改进专项。";

    private static final String UNRELATED_A = "图注：机房平面示意图，标注 UPS 与精密空调位置，无架构分层说明。";
    private static final String UNRELATED_B = "图注：访客通道闸机布局，仅描述安防动线，不涉及服务台分层。";

    @Test
    void i05Shape_wrongCitationRemapsToSupportingRank3() {
        // sources[0]/[1] 无关图注，sources[2] 黄金块；答案错引 [1] → 必须变成 [3]
        List<SourceReference> sources = List.of(
                src(UNRELATED_A),
                src(UNRELATED_B),
                src(GOLD_ARCH));
        String answer = "服务台分为接入层、调度层、处理层、数据层四层，数据层含台账与预警引擎[1]。";

        CitationAligner.Alignment alignment = CitationAligner.align(answer, sources);

        assertThat(alignment.text()).contains("[3]");
        assertThat(alignment.text()).doesNotContain("[1]");
        assertThat(alignment.citations()).containsExactly(3);
    }

    @Test
    void alreadyAligned_keepsTextByteForByte() {
        List<SourceReference> sources = List.of(
                src(UNRELATED_A),
                src(UNRELATED_B),
                src(GOLD_ARCH));
        String answer = "服务台分为接入层、调度层、处理层、数据层四层，数据层含台账与预警引擎[3]。";

        CitationAligner.Alignment alignment = CitationAligner.align(answer, sources);

        assertThat(alignment.text()).isEqualTo(answer);
        assertThat(alignment.citations()).containsExactly(3);
    }

    @Test
    void nearTie_keepsOriginalCitation() {
        // 两块几乎同词面，得分接近 → 平局保原号 [1]
        String shared = "服务台四层架构含台账与预警引擎，接入层负责工单登记。";
        List<SourceReference> sources = List.of(
                src(shared + "补充说明甲。"),
                src(shared + "补充说明乙。"));
        String answer = "服务台四层架构含台账与预警引擎[1]。";

        CitationAligner.Alignment alignment = CitationAligner.align(answer, sources);

        assertThat(alignment.text()).isEqualTo(answer);
        assertThat(alignment.citations()).containsExactly(1);
    }

    @Test
    void noSupport_dropsCitation() {
        List<SourceReference> sources = List.of(
                src(UNRELATED_A),
                src(UNRELATED_B));
        String answer = "今天天气晴朗适合户外活动[1]。";

        CitationAligner.Alignment alignment = CitationAligner.align(answer, sources);

        assertThat(alignment.text()).doesNotContain("[1]");
        assertThat(alignment.text()).contains("今天天气晴朗适合户外活动");
        assertThat(alignment.citations()).isEmpty();
    }

    @Test
    void emptySources_blankAnswer_noCitation_outOfRange() {
        // 空 sources：原文 + 空 citations
        assertThat(CitationAligner.align("答案[1]", List.of()).text()).isEqualTo("答案[1]");
        assertThat(CitationAligner.align("答案[1]", List.of()).citations()).isEmpty();
        assertThat(CitationAligner.align("答案[1]", null).citations()).isEmpty();

        // 空白答案
        assertThat(CitationAligner.align("  ", List.of(src(GOLD_ARCH))).text()).isEqualTo("  ");
        assertThat(CitationAligner.align("", List.of(src(GOLD_ARCH))).citations()).isEmpty();
        assertThat(CitationAligner.align(null, List.of(src(GOLD_ARCH))).text()).isEmpty();

        // 无编号：原文不动
        String plain = "服务台分为四层，含台账与预警引擎。";
        List<SourceReference> sources = List.of(src(UNRELATED_A), src(UNRELATED_B), src(GOLD_ARCH));
        CitationAligner.Alignment noCite = CitationAligner.align(plain, sources);
        assertThat(noCite.text()).isEqualTo(plain);
        assertThat(noCite.citations()).isEmpty();

        // 越界号：仅在有强支撑块时 REMAP
        CitationAligner.Alignment oobRemap = CitationAligner.align(
                "服务台分为四层，数据层含台账与预警引擎[9]。", sources);
        assertThat(oobRemap.text()).contains("[3]");
        assertThat(oobRemap.text()).doesNotContain("[9]");
        assertThat(oobRemap.citations()).containsExactly(3);

        // 越界且无支撑 → DROP
        CitationAligner.Alignment oobDrop = CitationAligner.align("完全无关的陈述[9]。", sources);
        assertThat(oobDrop.text()).doesNotContain("[9]");
        assertThat(oobDrop.citations()).isEmpty();
    }

    @Test
    void doesNotInventMissingCitations() {
        // 陈述了黄金块事实但没有任何 [n] → 不对齐器凭空插入
        List<SourceReference> sources = List.of(
                src(UNRELATED_A),
                src(UNRELATED_B),
                src(GOLD_ARCH));
        String answer = "服务台分为接入层、调度层、处理层、数据层四层，数据层含台账与预警引擎。";

        CitationAligner.Alignment alignment = CitationAligner.align(answer, sources);

        assertThat(alignment.text()).isEqualTo(answer);
        assertThat(alignment.citations()).isEmpty();
        assertThat(alignment.text()).doesNotContain("[");
    }

    private static SourceReference src(String excerpt) {
        return new SourceReference(
                "md", "vector", "sla-terms.md", "doc-1", "chunk-1",
                0, 1, null, excerpt, 0.9d);
    }
}
