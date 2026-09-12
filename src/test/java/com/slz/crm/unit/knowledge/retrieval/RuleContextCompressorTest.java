package com.slz.crm.unit.knowledge.retrieval;

import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.knowledge.retrieval.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则上下文压缩器单测（add-context-compression-and-enrichment 任务 2.1/2.4）：
 * 未超预算逐字保留、超预算压缩到预算内、承重句/首末句保留、编号完整性、去连续重复、
 * 正文内行首编号不破坏解析、编号保底下限。
 */
class RuleContextCompressorTest {

    private final RuleContextCompressor compressor = new RuleContextCompressor();

    /** 任务 2.4：未超预算不压缩，原文逐字保留（同引用返回同一内容）。 */
    @Test
    @DisplayName("未超预算：原文逐字返回")
    void withinBudgetShouldReturnVerbatim() {
        String context = "[1] 首句说明总体口径。中间补充内容。末句收尾。\n[2] 第二段首句。第二段末句。";

        assertThat(compressor.compress(context, TokenEstimator.estimate(context))).isEqualTo(context);
        assertThat(compressor.compress(context, TokenEstimator.estimate(context) + 100)).isEqualTo(context);
    }

    /** 任务 2.4：构造超预算上下文，断言压缩后 token 数 ≤ 预算且编号全集保留。 */
    @Test
    @DisplayName("超预算：压缩后不超过预算，编号不丢")
    void overBudgetShouldCompressWithinBudget() {
        String context = """
                [1] 首句说明总体口径。这一段是补充说明内容一句。这一段是补充说明内容二句。这一段是补充说明内容三句。这一段是补充说明内容四句。末句收尾。
                [2] 第二段首句说明。第二段补充内容一句。第二段补充内容二句。第二段补充内容三句。第二段补充内容四句。第二段末句结束。""";
        int budget = TokenEstimator.estimate(context) / 2;

        String compressed = compressor.compress(context, budget);

        assertThat(TokenEstimator.estimate(compressed)).isLessThanOrEqualTo(budget);
        assertThat(compressed).contains("[1]").contains("[2]");
        assertThat(compressed.length()).isLessThan(context.length());
    }

    /** 承重句口径：预算恰好只装下首句+承重句+末句时，非承重中间句被丢弃，承重句保留。 */
    @Test
    @DisplayName("承重句优先：数字句保留，普通句丢弃")
    void loadBearingSentencesShouldSurviveCompression() {
        String first = "首句说明总体口径。";
        String plain = "这一段是补充说明内容。";
        String loadBearing = "第3期付款节点为6月。";
        String plainTail = "补充说明续。";
        String last = "末句收尾。";
        String context = "[1] " + first + plain + loadBearing + plainTail + last;
        int budget = TokenEstimator.estimate("[1] ")
                + TokenEstimator.estimate(first) + TokenEstimator.estimate(loadBearing)
                + TokenEstimator.estimate(last);

        String compressed = compressor.compress(context, budget);

        assertThat(compressed)
                .contains(first)
                .contains(loadBearing)
                .contains(last)
                .doesNotContain(plain)
                .doesNotContain(plainTail);
    }

    /** 引用编号完整性：多段压缩后 [n] 编号序列与原段一一对应、不增删改。 */
    @Test
    @DisplayName("编号完整性：压缩后 [1]..[n] 全部保留且有序")
    void citationNumbersMustSurviveCompression() {
        String context = """
                [1] 首句含数字金额15万元。填充内容一句。填充内容二句。填充内容三句。填充内容四句。末句收尾。
                [2] 第二段首句。第二段填充一句。第二段填充二句。第二段填充三句。第二段填充四句。第二段末句。
                [3] 第三段首句。第三段填充一句。第三段填充二句。第三段填充三句。第三段填充四句。第三段末句。""";
        int budget = TokenEstimator.estimate(context) / 2;

        String compressed = compressor.compress(context, budget);

        assertThat(compressed).contains("[1] 首句").contains("[2] 第二段首句").contains("[3] 第三段首句");
        assertThat(compressed.indexOf("[1]")).isLessThan(compressed.indexOf("[2]"));
        assertThat(compressed.indexOf("[2]")).isLessThan(compressed.indexOf("[3]"));
    }

    /** 去连续重复段：超预算路径下连续重复行只保留一份。 */
    @Test
    @DisplayName("去连续重复：重复行只保留一份")
    void consecutiveDuplicateLinesShouldBeDeduplicated() {
        String duplicated = "表头：产品 数量 金额。\n";
        String context = "[1] 首句说明。" + duplicated + duplicated
                + "数据行说明内容一句。数据行说明内容二句。数据行说明内容三句。末句收尾。";
        int budget = TokenEstimator.estimate(context) - 3;

        String compressed = compressor.compress(context, budget);

        assertThat(countOccurrences(compressed, "表头：产品 数量 金额。")).isEqualTo(1);
    }

    /** 正文内行首 [7] 不构成新段：编号解析不被正文干扰，输出编号仍为 [1]。 */
    @Test
    @DisplayName("编号防破坏：正文行首编号不产生新段")
    void inlineHeaderLikeTextMustNotBreakNumbering() {
        String context = "[1] 首句说明。\n[7] 这是正文里的备注行，不是新段落。末句收尾。";
        int budget = TokenEstimator.estimate(context) - 1;

        String compressed = compressor.compress(context, budget);

        assertThat(compressed).contains("[1] ").contains("[7] 这是正文里的备注行");
        assertThat(compressed).doesNotContain("\n[2]").doesNotContain("[8]");
    }

    /** 无编号结构的上下文无法安全压缩：原文返回（完整性优先）。 */
    @Test
    @DisplayName("无编号结构：不压缩，原文返回")
    void contextWithoutNumberedSectionsShouldReturnVerbatim() {
        String context = "这是一段没有编号结构的普通文本，包含很多内容。".repeat(10);

        assertThat(compressor.compress(context, 10)).isEqualTo(context);
    }

    /** 编号保底下限：预算极小时各段至少保留段头+首句，编号全集不丢。 */
    @Test
    @DisplayName("保底下限：极小预算仍保留全部编号与首句")
    void minimalBudgetShouldKeepAllSectionHeaders() {
        String context = """
                [1] 首句说明总体口径。填充内容一句。填充内容二句。填充内容三句。末句收尾。
                [2] 第二段首句说明。第二段填充一句。第二段填充二句。第二段填充三句。第二段末句。""";

        String compressed = compressor.compress(context, 2);

        assertThat(compressed).contains("[1] 首句说明总体口径。").contains("[2] 第二段首句说明。");
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
