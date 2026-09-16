package com.slz.crm.unit.knowledge.document;

import com.slz.crm.knowledge.document.ChunkingStrategy;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.document.FixedChunkingStrategy;
import com.slz.crm.knowledge.document.ParagraphChunkingStrategy;
import com.slz.crm.platform.contract.DynamicConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 段落感知切分（add-paragraph-chunking）：I-05 形两段、无换行≡fixed、超长单段覆盖。
 */
class ParagraphChunkingStrategyTest {

    /** 2.1 I-05 形：赔偿/何建军 + 空行 + 图注；paragraph 下图注整块，不被 320 滑窗横切。 */
    @Test
    void paragraphKeepsCaptionParagraphIntactLikeI05() throws Exception {
        String liability = "【GOLD:sla-credit】SLA未达标赔偿条款：单月内P1级响应或解决时效不达标一次，赔偿当月服务费的10%；"
                + "P2级累计不达标两次，赔偿当月服务费的5%；赔偿金额单月封顶为当月服务费的30%。"
                + "赔偿以服务费抵扣方式执行，由客户在次月对账单确认后自动抵扣；因客户原因导致的服务中断不计入不达标统计。"
                + "责任工程师为何建军（分机8305），负责P1/P2级故障的处置调度与升级决策；服务台三班轮值，值班表每周五发布。";
        String caption = "服务台架构图注：接入层工单网关 → 调度层三班值班组 → 处理层一线/二线/三线工程师 → 数据层 SLA 台账与预警引擎；"
                + "图注标注各层职责：网关负责工单登记与分流，调度组负责时效监控与升级，处理线负责故障处置，台账负责达成率统计与赔偿核算。"
                + "预警引擎在时效达到80%时提醒责任工程师，100%时自动升级；服务台整体时效达成率目标为95%。";
        assertThat(liability.length()).isLessThan(320);
        assertThat(caption.length()).isLessThan(320);
        assertThat(liability.length() + 2 + caption.length()).isGreaterThan(320);

        String text = liability + "\n\n" + caption;

        // fixed：首窗横切图注（含何建军 + 图注开头，但缺图注结尾）——对照根因
        List<String> fixedTexts = new FixedChunkingStrategy().split(text).stream()
                .map(ChunkingStrategy.StrategyChunk::text).toList();
        assertThat(fixedTexts.get(0))
                .contains("何建军")
                .contains("服务台架构图注")
                .doesNotContain("时效达成率目标为95%");

        DocumentService service = paragraphService();
        List<String> texts = service.process(
                        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "i05-shape.txt", "sla")
                .stream().map(c -> c.text()).toList();

        assertThat(texts).anySatisfy(chunk -> assertThat(chunk).isEqualTo(caption));
        assertThat(texts).anySatisfy(chunk -> assertThat(chunk)
                .contains("台账与预警引擎")
                .contains("时效达成率目标为95%")
                .doesNotContain("何建军"));
    }

    /** 2.2 无换行长文：paragraph 与 fixed 逐字相同。 */
    @Test
    void paragraphMatchesFixedWhenNoNewlines() {
        String text = "销售过程管理是CRM系统中的关键能力，客户、商机、合同与回款数据需要保持一致。".repeat(40);
        assertThat(text).doesNotContain("\n");

        List<String> paragraph = new ParagraphChunkingStrategy().split(text).stream()
                .map(ChunkingStrategy.StrategyChunk::text).toList();
        List<String> fixed = new FixedChunkingStrategy().split(text).stream()
                .map(ChunkingStrategy.StrategyChunk::text).toList();

        assertThat(paragraph).containsExactlyElementsOf(fixed);
    }

    /** 2.3 超长单段（无段落界）：段内仍 320/40 滑窗，拼接覆盖全文、不丢字。 */
    @Test
    void oversizedSingleParagraphStillCoversFullText() {
        String text = "甲".repeat(900);
        assertThat(text.length()).isGreaterThan(320);
        assertThat(text).doesNotContain("\n");

        List<ChunkingStrategy.StrategyChunk> chunks = new ParagraphChunkingStrategy().split(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(320));

        // 滑窗有重叠：首块 + 后续块去掉 overlap 前缀应还原全文
        String rebuilt = rebuildWithOverlap(chunks.stream().map(ChunkingStrategy.StrategyChunk::text).toList(), 40);
        assertThat(rebuilt).isEqualTo(text);

        // 与 fixed 逐字相同（无换行）
        assertThat(chunks.stream().map(ChunkingStrategy.StrategyChunk::text).toList())
                .containsExactlyElementsOf(new FixedChunkingStrategy().split(text).stream()
                        .map(ChunkingStrategy.StrategyChunk::text).toList());
    }

    /** 显式 paragraph 配置下 DocumentService 走段落策略；无参仍 fixed。 */
    @Test
    void documentServiceResolvesParagraphOnlyWhenConfigured() throws Exception {
        String liability = "责任工程师为何建军。".repeat(20); // ~180
        String caption = "服务台架构图注完整段：" + "层".repeat(160);
        String text = liability + "\n\n" + caption;
        assertThat(liability.length()).isLessThan(320);
        assertThat(caption.length()).isLessThan(320);
        assertThat(text.length()).isGreaterThan(320);

        List<String> noArg = new DocumentService()
                .process(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "a.txt", "crm")
                .stream().map(c -> c.text()).toList();
        // 无参 = fixed，首块应横切
        assertThat(noArg.get(0)).contains("何建军").contains("服务台架构图注");

        List<String> paragraph = paragraphService()
                .process(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "a.txt", "crm")
                .stream().map(c -> c.text()).toList();
        assertThat(paragraph).anySatisfy(c -> assertThat(c).isEqualTo(caption));
    }

    @SuppressWarnings("unchecked")
    private static DocumentService paragraphService() {
        ObjectProvider<DynamicConfigService> provider = mock(ObjectProvider.class);
        DynamicConfigService config = mock(DynamicConfigService.class);
        when(provider.getIfAvailable()).thenReturn(config);
        when(config.get(eq(DocumentService.STRATEGY_KEY), eq(String.class), any()))
                .thenReturn(DocumentService.STRATEGY_PARAGRAPH);
        return new DocumentService(provider);
    }

    /** 按固定重叠把滑窗块还原为原文（块 i>0 的前 overlap 与前块尾重叠）。 */
    private static String rebuildWithOverlap(List<String> chunks, int overlap) {
        if (chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            assertThat(chunk.length()).isGreaterThan(overlap);
            sb.append(chunk.substring(Math.min(overlap, chunk.length())));
        }
        return sb.toString();
    }
}
