package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.quality.ImageCrossModalEvaluator.CaseJudgement;
import com.slz.crm.quality.ImageCrossModalEvaluator.Evaluation;
import com.slz.crm.quality.ImageCrossModalEvaluator.Probe;
import com.slz.crm.quality.ImageCrossModalFixture.FixtureImage;
import com.slz.crm.quality.ImageCrossModalSuite.ImageCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * 图文跨模态评测的纯单测（image-cross-modal-eval 任务 4.1）：套件构造 / 判分口径 / 余弦阈值边界 / 无 key 跳过。
 *
 * <p><b>零真外呼</b>：本类不构造任何 Provider、不发任何网络请求——向量全为手写常量或内存库回函， 因此进 surefire（{@code mvn
 * test}）默认恒跑，也是"判分口径改动会被立刻发现"的那道尺子（真跑 IT 默认 skip，护不住口径）。
 *
 * <p><b>跳过口径可执行验证</b>：{@link ImageCrossModalBenchmarkIT#requireRealRun} 抽成静态方法后，本类直接断言 "未 opt-in /
 * 无 key → TestAbortedException"与"两者齐备 → 不抛"，把"默认零外呼"从注释变成断言。
 */
class ImageCrossModalSuiteTest {

  @Test
  @DisplayName("套件独立：IMAGE-SUITE-1.0 独立编号，不并入主套件、不动 SUITE_VERSION")
  void suiteIsIndependentFromMainSuite() {
    assertEquals("IMAGE-SUITE-1.0", ImageCrossModalSuite.SUITE_VERSION);
    assertEquals("6.0", RagBenchmarkSuite.SUITE_VERSION, "主套件版本号不得被本卡改动");

    List<RagBenchmarkCase> mainSuite = RagBenchmarkSuite.standard();
    // 完整性优先 + 下限：只保证主套件没被本卡删减/改写，不因凑数而删既有用例
    assertTrue(mainSuite.size() >= 72, "主套件用例数被改动: " + mainSuite.size());
    long mainImageCases =
        mainSuite.stream()
            .filter(benchmarkCase -> benchmarkCase.category() == RagBenchmarkCase.Category.IMAGE)
            .count();
    assertTrue(mainImageCases >= 6, "主套件 Category.IMAGE 既有 6 例被改动: " + mainImageCases);

    for (ImageCase imageCase : ImageCrossModalSuite.cases()) {
      assertTrue(imageCase.id().startsWith("IM-"), "独立编号口径为 IM-xx: " + imageCase.id());
    }
    for (RagBenchmarkCase benchmarkCase : mainSuite) {
      assertFalse(benchmarkCase.id().startsWith("IM-"), "图文套件用例混入主套件: " + benchmarkCase.id());
    }
  }

  @Test
  @DisplayName("套件构造：IM-01~IM-05 唯一、同图异图不同键、黄金 id 与同图切片一致")
  void suiteCasesAreWellFormed() {
    List<ImageCase> cases = ImageCrossModalSuite.cases();
    assertEquals(5, cases.size(), "本套件用例数（扩用例需同步升 IMAGE-SUITE 版本）");
    List<String> ids = new ArrayList<>();
    for (ImageCase imageCase : cases) {
      ids.add(imageCase.id());
      assertNotEquals(
          imageCase.sameImageKey(), imageCase.crossImageKey(), "同图与异图键不得相同: " + imageCase.id());
      assertEquals(
          java.util.Set.of(ImageCrossModalFixture.sourceChunkId(imageCase.sameImageKey())),
          imageCase.expectedChunkIds(),
          "黄金 id 必须等于同图切片的引用 id（IMAGE:{资产 id}）: " + imageCase.id());
      assertEquals(
          ImageCrossModalFixture.image(imageCase.queryImageKey()).caption(),
          imageCase.queryText(),
          "查询文本取查询图描述文本: " + imageCase.id());
    }
    assertEquals(ids.size(), ids.stream().distinct().count(), "用例编号不得重复");

    List<RagBenchmarkCase> benchmarkCases = ImageCrossModalSuite.benchmarkCases();
    assertEquals(cases.size(), benchmarkCases.size());
    for (RagBenchmarkCase benchmarkCase : benchmarkCases) {
      assertEquals(RagBenchmarkCase.Category.IMAGE, benchmarkCase.category());
      assertTrue(benchmarkCase.useKnowledgeBase());
      assertTrue(benchmarkCase.history().isEmpty(), "单轮用例不带多轮历史");
      assertFalse(benchmarkCase.expectedChunkIds().isEmpty());
    }
    assertEquals("IM-03", ImageCrossModalSuite.byId("IM-03").id());
    assertThrows(IllegalArgumentException.class, () -> ImageCrossModalSuite.byId("IM-99"));
  }

  @Test
  @DisplayName("fixture：6 图确定性 PNG，键唯一且可派生黄金 id")
  void fixtureIsDeterministic() {
    List<FixtureImage> images = ImageCrossModalFixture.images();
    assertEquals(6, images.size());
    assertEquals(
        images.size(), images.stream().map(FixtureImage::key).distinct().count(), "图像键不得重复");
    for (FixtureImage image : images) {
      byte[] png = image.png();
      assertTrue(png.length > 500, "PNG 过小可疑: " + image.key());
      assertEquals((byte) 0x89, png[0]);
      assertEquals('P', (char) png[1]);
      assertEquals('N', (char) png[2]);
      assertEquals('G', (char) png[3]);
      assertTrue(java.util.Arrays.equals(png, image.png()), "同一键必须重渲染出同一图: " + image.key());
      assertTrue(image.chunkId().startsWith("imgchunk-"));
      assertFalse(image.caption().isBlank());
    }
    // 差异图必须真的不同字节（否则"同图/异图"是同一张，判据自欺）
    assertFalse(
        java.util.Arrays.equals(
            ImageCrossModalFixture.image("flow-blue").png(),
            ImageCrossModalFixture.image("bars-blue").png()));
    assertThrows(IllegalArgumentException.class, () -> ImageCrossModalFixture.image("not-a-key"));
  }

  @Test
  @DisplayName("余弦：同向量 1、正交 0、零向量 0、维度不一致抛错")
  void cosineEdges() {
    assertEquals(
        1.0D, ImageCrossModalEvaluator.cosine(new float[] {1F, 2F}, new float[] {1F, 2F}), 1e-9);
    assertEquals(
        0.0D, ImageCrossModalEvaluator.cosine(new float[] {1F, 0F}, new float[] {0F, 1F}), 1e-9);
    assertEquals(
        0.0D, ImageCrossModalEvaluator.cosine(new float[] {0F, 0F}, new float[] {1F, 1F}), 1e-9);
    assertEquals(
        -1.0D, ImageCrossModalEvaluator.cosine(new float[] {1F, 0F}, new float[] {-2F, 0F}), 1e-9);
    // 维度不一致是接线错误（视觉/文本向量空间维度不同），必须炸而不是截断比较
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageCrossModalEvaluator.cosine(new float[] {1F, 0F, 0F}, new float[] {1F, 0F}));
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageCrossModalEvaluator.cosine(new float[] {}, new float[] {1F}));
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageCrossModalEvaluator.cosine(null, new float[] {1F}));
  }

  @Test
  @DisplayName("阈值边界：同图余弦 == 0.90 计命中、略低不计；异图 0.90 计误召、略低计拒答")
  void thresholdBoundaries() {
    List<ImageCase> singleCase = ImageCrossModalSuite.cases().subList(0, 1);

    Evaluation atBoundary =
        ImageCrossModalEvaluator.evaluate(
            "vision-query-by-image",
            singleCase,
            imageCase -> new Probe(0.90D, 0.8999D, true, false));
    CaseJudgement boundaryCase = atBoundary.cases().get(0);
    assertTrue(boundaryCase.sameImageHit(), "同图余弦 == 阈值应计命中（>=）");
    assertTrue(boundaryCase.crossImageRejected(), "异图余弦 < 阈值应计正确拒答");
    assertTrue(boundaryCase.pass());
    assertEquals(1.0D, atBoundary.metrics().sameImageHitRate(), 1e-9);
    assertEquals(0.0D, atBoundary.metrics().crossImageFalsePositiveRate(), 1e-9);

    Evaluation below =
        ImageCrossModalEvaluator.evaluate(
            "vision-query-by-image",
            singleCase,
            imageCase -> new Probe(0.8999D, 0.90D, false, true));
    CaseJudgement belowCase = below.cases().get(0);
    assertFalse(belowCase.sameImageHit(), "同图余弦低于阈值不得计命中");
    assertFalse(belowCase.crossImageRejected(), "异图余弦等于阈值不得计拒答（<）");
    assertFalse(belowCase.pass());
    assertEquals(1.0D, below.metrics().crossImageFalsePositiveRate(), 1e-9);
  }

  @Test
  @DisplayName("聚合口径：同图召回率 / 异图误召率 / 判别间隔 / 逐条通过率")
  void metricsAggregateAsExpected() {
    List<ImageCase> cases = ImageCrossModalSuite.cases();
    Evaluation evaluation =
        ImageCrossModalEvaluator.evaluate(
            "text-caption-baseline",
            cases,
            imageCase ->
                switch (imageCase.id()) {
                  // 全过：同图高、异图低
                  case "IM-01" -> new Probe(0.99D, 0.40D, true, false);
                  // 异图误召：文本降维路把描述文本同骨架的异图拉近
                  case "IM-02" -> new Probe(0.98D, 0.97D, true, true);
                  // 同图都没命中 + 异图误召
                  case "IM-03" -> new Probe(0.10D, 0.95D, false, true);
                  // 同图命中、异图拒答、间隔为正
                  case "IM-04" -> new Probe(0.95D, 0.60D, true, false);
                  // 同图命中但间隔为负（异图更像）→ 不得算通过
                  default -> new Probe(0.92D, 0.96D, true, true);
                });
    assertEquals(5, evaluation.metrics().caseCount());
    assertEquals(4D / 5D, evaluation.metrics().sameImageHitRate(), 1e-9);
    assertEquals(2D / 5D, evaluation.metrics().crossImageRejectionRate(), 1e-9);
    assertEquals(3D / 5D, evaluation.metrics().crossImageFalsePositiveRate(), 1e-9);
    // 检索层观测：IM-02/IM-03/IM-05 的异图切片进了 topK（误召的唯一口径是余弦判据，两者并列留痕）
    assertEquals(3D / 5D, evaluation.metrics().retrievalCrossImageRate(), 1e-9);
    assertEquals(2D / 5D, evaluation.metrics().passRate(), 1e-9);
    assertEquals(
        ((0.99D - 0.40D) + (0.98D - 0.97D) + (0.10D - 0.95D) + (0.95D - 0.60D) + (0.92D - 0.96D))
            / 5D,
        evaluation.metrics().meanDiscriminationMargin(),
        1e-9);
    assertEquals("text-caption-baseline", evaluation.path());
  }

  @Test
  @DisplayName("fake 向量回函端到端：内存 image 集合 → 余弦 → 判分，全程零外呼")
  void fakeVectorsEndToEndOverInMemoryStore() {
    ImageVectorStore store = new ImageVectorStore(new InMemoryVectorStore());
    store.upsert(
        ImageCrossModalFixture.imageRecord(
            ImageCrossModalFixture.image("flow-blue"), new float[] {1F, 0F, 0F, 0F}));
    store.upsert(
        ImageCrossModalFixture.imageRecord(
            ImageCrossModalFixture.image("bars-blue"), new float[] {0F, 1F, 0F, 0F}));
    // 文本对照集合的记录混在同一 backing 里也不得被 image 路召回（category 过滤口径）
    store.upsert(
        ImageCrossModalFixture.textRecord(
            ImageCrossModalFixture.image("table-scan"), new float[] {1F, 0F, 0F, 0F}));

    List<VectorSearchHit> hits =
        store.search(
            new VectorSearchRequest(
                new float[] {1F, 0F, 0F, 0F},
                5,
                null,
                Map.of(
                    "knowledgeBaseId",
                    ImageCrossModalFixture.EVAL_KB_ID,
                    "category",
                    ImageCrossModalFixture.IMAGE_CATEGORY)));
    assertEquals(2, hits.size(), "category=IMAGE 过滤后只剩两张内容图（文本记录被滤掉）");
    assertEquals(ImageCrossModalFixture.chunkId("flow-blue"), hits.get(0).chunkId());
    assertEquals(1.0D, hits.get(0).score(), 1e-9);
    assertEquals(0.0D, hits.get(1).score(), 1e-9);

    ImageCase oneCase = ImageCrossModalSuite.cases().get(0);
    Evaluation evaluation =
        ImageCrossModalEvaluator.evaluate(
            "vision-query-by-image",
            List.of(oneCase),
            imageCase ->
                new Probe(
                    hits.get(0).score(),
                    hits.get(1).score(),
                    hits.stream()
                        .anyMatch(
                            hit ->
                                hit.chunkId().equals(ImageCrossModalFixture.chunkId("flow-blue"))),
                    hits.stream()
                        .anyMatch(
                            hit ->
                                hit.chunkId()
                                    .equals(ImageCrossModalFixture.chunkId("bars-blue")))));
    assertTrue(evaluation.cases().get(0).pass(), "同图 1.0 / 异图 0.0 必须判通过");
    assertEquals(1.0D, evaluation.metrics().passRate(), 1e-9);
    assertEquals(1.0D, evaluation.metrics().retrievalRecallAtK(), 1e-9);
    // 异图切片也在 topK 里（集合只有两条候选），但余弦 0.0 → 判据拒答：检索层出现 ≠ 余弦误召
    assertEquals(1.0D, evaluation.metrics().retrievalCrossImageRate(), 1e-9);
    assertEquals(0.0D, evaluation.metrics().crossImageFalsePositiveRate(), 1e-9);
  }

  @Test
  @DisplayName("跳过口径：未 opt-in 或无 key 一律整类跳过（默认零外呼），两者齐备才执行")
  void realRunGateSkipsWithoutOptInOrKey() {
    assertThrows(
        TestAbortedException.class,
        () -> ImageCrossModalBenchmarkIT.requireRealRun(Map.of(), "sk-fake"));
    assertThrows(
        TestAbortedException.class,
        () ->
            ImageCrossModalBenchmarkIT.requireRealRun(
                Map.of("RAG_BENCHMARK_REAL", "1"), "sk-fake"));
    assertThrows(
        TestAbortedException.class,
        () -> ImageCrossModalBenchmarkIT.requireRealRun(Map.of("RAG_VISION_EMBED_REAL", "1"), ""));
    assertThrows(
        TestAbortedException.class,
        () ->
            ImageCrossModalBenchmarkIT.requireRealRun(Map.of("RAG_VISION_EMBED_REAL", "1"), null));
    assertDoesNotThrow(
        () ->
            ImageCrossModalBenchmarkIT.requireRealRun(
                Map.of("RAG_VISION_EMBED_REAL", "1"), "sk-fake"));
    assertEquals("RAG_VISION_EMBED_REAL", ImageCrossModalBenchmarkIT.OPT_IN_ENV);
  }
}
