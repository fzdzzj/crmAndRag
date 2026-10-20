package com.slz.crm.unit.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.server.ai.CitationAligner;
import com.slz.crm.server.ai.CitationThresholds;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用对齐阈值注入单元（fix-citation-threshold-config 任务 3.1）。
 *
 * <p>同一 clause/sources 在不同阈值下必须给出 KEEP / REMAP / DROP 三种不同决策：夹具用纯 CJK 四字子句「甲乙丙丁戊」， 支撑分可手算（bigram
 * 覆盖率）：excerpt「甲乙丙」= 2/4 = 0.50，excerpt「甲乙丙丁」= 3/4 = 0.75，无 ASCII token 加成。
 */
class CitationThresholdsTest {

  private static final String CLAUSE_ANSWER = "甲乙丙丁戊[1]";

  /** 原编号片段：覆盖 2/4 bigram → 0.50。 */
  private static final SourceReference WEAK = src("甲乙丙");

  /** 最佳片段：覆盖 3/4 bigram → 0.75。 */
  private static final SourceReference STRONG = src("甲乙丙丁");

  private static final List<SourceReference> SOURCES = List.of(WEAK, STRONG);

  @Test
  @DisplayName("默认阈值 = 三个现常量（0.12/0.22/0.08），两参形态与三参默认形态逐字节等价")
  void defaultsMirrorCompileTimeConstants() {
    CitationThresholds defaults = CitationThresholds.defaults();

    assertThat(defaults.keepMin()).isEqualTo(CitationAligner.KEEP_MIN);
    assertThat(defaults.remapMin()).isEqualTo(CitationAligner.REMAP_MIN);
    assertThat(defaults.tieMargin()).isEqualTo(CitationAligner.TIE_MARGIN);

    // 零漂移回归：两参形态必须与显式默认阈值形态完全一致（文本 + citations 双口径）
    CitationAligner.Alignment legacy = CitationAligner.align(CLAUSE_ANSWER, SOURCES);
    CitationAligner.Alignment explicit =
        CitationAligner.align(CLAUSE_ANSWER, SOURCES, CitationThresholds.defaults());

    assertThat(legacy.text()).isEqualTo(explicit.text());
    assertThat(legacy.citations()).isEqualTo(explicit.citations());
    // null 阈值 = 默认阈值（调用侧解析失败兜底，不抛 NPE）
    CitationAligner.Alignment nullThresholds = CitationAligner.align(CLAUSE_ANSWER, SOURCES, null);
    assertThat(nullThresholds.text()).isEqualTo(legacy.text());
    assertThat(nullThresholds.citations()).isEqualTo(legacy.citations());
  }

  @Test
  @DisplayName("同一 clause/sources：默认阈值 REMAP[2]、放宽平局带宽 KEEP[1]、抬高重映射阈值 DROP")
  void sameClauseThreeStatesUnderDifferentThresholds() {
    // ① REMAP：默认阈值下原编号 0.50 明显落后于最佳 0.75（差 0.25 >= 0.08）→ 重映射到 [2]
    CitationAligner.Alignment remapped = CitationAligner.align(CLAUSE_ANSWER, SOURCES);
    assertThat(remapped.text()).isEqualTo("甲乙丙丁戊[2]");
    assertThat(remapped.citations()).containsExactly(2);

    // ② KEEP：平局带宽放宽到 0.5（0.50 + 0.50 >= 0.75）→ 保留原编号 [1]
    CitationAligner.Alignment kept =
        CitationAligner.align(CLAUSE_ANSWER, SOURCES, new CitationThresholds(0.12d, 0.22d, 0.50d));
    assertThat(kept.text()).isEqualTo(CLAUSE_ANSWER);
    assertThat(kept.citations()).containsExactly(1);

    // ③ DROP：保留阈值抬到 0.60（原编号 0.50 不再合格）且重映射阈值抬到 0.80（最佳 0.75 不够强）→ 删号
    CitationAligner.Alignment dropped =
        CitationAligner.align(CLAUSE_ANSWER, SOURCES, new CitationThresholds(0.60d, 0.80d, 0.08d));
    assertThat(dropped.text()).doesNotContain("[1]");
    assertThat(dropped.text()).isEqualTo("甲乙丙丁戊");
    assertThat(dropped.citations()).isEmpty();
  }

  @Test
  @DisplayName("构造校验：三阈值只收 [0,1] 内有限数，越界/NaN 在装配期即拒绝")
  void constructorRejectsOutOfRangeValues() {
    // 边界值与典型默认值放行
    assertThatCode(() -> new CitationThresholds(0.0d, 0.0d, 0.0d)).doesNotThrowAnyException();
    assertThatCode(() -> new CitationThresholds(1.0d, 1.0d, 1.0d)).doesNotThrowAnyException();
    assertThatCode(() -> new CitationThresholds(0.12d, 0.22d, 0.08d)).doesNotThrowAnyException();

    // 越界：逐字段拒绝
    assertThatThrownBy(() -> new CitationThresholds(-0.01d, 0.22d, 0.08d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keepMin");
    assertThatThrownBy(() -> new CitationThresholds(0.12d, 1.01d, 0.08d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remapMin");
    assertThatThrownBy(() -> new CitationThresholds(0.12d, 0.22d, 1.5d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tieMargin");

    // 非有限数拒绝
    assertThatThrownBy(() -> new CitationThresholds(Double.NaN, 0.22d, 0.08d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keepMin");
    assertThatThrownBy(() -> new CitationThresholds(0.12d, Double.POSITIVE_INFINITY, 0.08d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remapMin");
  }

  private static SourceReference src(String excerpt) {
    return new SourceReference(
        "md", "vector", "sla-terms.md", "doc-1", "chunk-1", 0, 1, null, excerpt, 0.9d);
  }
}
