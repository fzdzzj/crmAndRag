package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.platform.contract.VectorSearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** RRF 融合单测（complete-hybrid-retrieval-and-rerank 任务 3.2）：手算对照 Σ1/(k+rank)。 */
class RrfFusionTest {
  private final RrfFusion fusion = new RrfFusion();

  /** 两路命中同一块：RRF 分 = 各路排名倒数之和；排序据此重排。 */
  @Test
  void bothRoutesHittingSameChunkShouldSumReciprocals() {
    // 向量路：c1 rank1、c2 rank2；稀疏路：c2 rank1、c3 rank2（k=60）
    RetrievalCandidate c1 = candidate("c1", 0.9);
    RetrievalCandidate c2 = candidate("c2", 0.7);
    RetrievalCandidate c3 = candidate("c3", 4.0);
    List<RetrievalCandidate> fused =
        fusion.fuse(List.of(c1, c2), List.of(candidate("c2", 5.0), c3), 60);

    assertEquals(3, fused.size());
    assertEquals("c2", fused.getFirst().hit().chunkId(), "双路命中的 c2 融合分最高");
    assertEquals("c1", fused.get(1).hit().chunkId());
    assertEquals("c3", fused.get(2).hit().chunkId());
    assertEquals(
        1.0 / 62 + 1.0 / 61, fused.getFirst().rerankScore(), 1e-12, "c2 = 向量路rank2 + 稀疏路rank1");
    assertEquals(1.0 / 61, fused.get(1).rerankScore(), 1e-12, "c1 = 向量路rank1");
    assertEquals(1.0 / 62, fused.get(2).rerankScore(), 1e-12, "c3 = 稀疏路rank2");
  }

  /** 单路命中退化为该路排名：分数单调变换，顺序不变。 */
  @Test
  void singleRouteShouldDegradeToItsOwnRanking() {
    List<RetrievalCandidate> fused =
        fusion.fuse(
            List.of(candidate("a", 0.5), candidate("b", 0.8), candidate("c", 0.6)), List.of(), 60);

    assertEquals(3, fused.size());
    assertEquals("b", fused.get(0).hit().chunkId());
    assertEquals("c", fused.get(1).hit().chunkId());
    assertEquals("a", fused.get(2).hit().chunkId());
    assertEquals(1.0 / 61, fused.get(0).rerankScore(), 1e-12);
    assertEquals(1.0 / 62, fused.get(1).rerankScore(), 1e-12);
    assertEquals(1.0 / 63, fused.get(2).rerankScore(), 1e-12);
  }

  /** k 可调：k=1 时排名差异被放大（rank1 与 rank2 差距 1/2 vs 1/3）。 */
  @Test
  void customKShouldReshapeScores() {
    List<RetrievalCandidate> fused =
        fusion.fuse(List.of(candidate("a", 0.5)), List.of(candidate("a", 9.9)), 1);
    assertEquals(1, fused.size());
    assertEquals(1.0 / 2 + 1.0 / 2, fused.getFirst().rerankScore(), 1e-12);
  }

  /** 双空与单空退化。 */
  @Test
  void emptyRoutesShouldDegradeGracefully() {
    assertTrue(fusion.fuse(List.of(), List.of(), 60).isEmpty());
    assertEquals(
        "only",
        fusion.fuse(List.of(), List.of(candidate("only", 1.0)), 60).getFirst().hit().chunkId());
    assertEquals(
        "only", fusion.fuse(List.of(candidate("only", 1.0)), null, 60).getFirst().hit().chunkId());
    assertTrue(fusion.fuseAll(null, 60).isEmpty());
  }

  /**
   * N 路融合手算对照（enhance-query-transformation 任务 1.2）： r1: A(1) B(2) C(3)；r2: A(1) B(2)；r3: C(1)，k=10：
   * A = 1/11 + 1/11 = 2/11；B = 1/12 + 1/12 = 2/12；C = 1/13 + 1/11 → 排序 A &gt; C &gt; B。
   */
  @Test
  void multiRouteFusionShouldMatchHandCalculation() {
    RetrievalCandidate a = candidate("A", 0.9);
    RetrievalCandidate b = candidate("B", 0.8);
    RetrievalCandidate c = candidate("C", 0.7);
    List<RetrievalCandidate> fused =
        fusion.fuseAll(
            List.of(
                List.of(a, b, c),
                List.of(candidate("A", 0.95), candidate("B", 0.5)),
                List.of(candidate("C", 0.99))),
            10);

    assertEquals(3, fused.size());
    assertEquals("A", fused.get(0).hit().chunkId());
    assertEquals("C", fused.get(1).hit().chunkId());
    assertEquals("B", fused.get(2).hit().chunkId());
    assertEquals(1.0 / 11 + 1.0 / 11, fused.get(0).rerankScore(), 1e-12, "A = r1 rank1 + r2 rank1");
    assertEquals(1.0 / 13 + 1.0 / 11, fused.get(1).rerankScore(), 1e-12, "C = r1 rank3 + r3 rank1");
    assertEquals(1.0 / 12 + 1.0 / 12, fused.get(2).rerankScore(), 1e-12, "B = r1 rank2 + r2 rank2");
  }

  /** 跨路命中同块：融合分累加、hit 保留先出现路的条目；空路安全跳过。 */
  @Test
  void multiRouteShouldAccumulateAndKeepFirstRouteHit() {
    RetrievalCandidate routeOneHit = candidate("X", 0.6);
    RetrievalCandidate routeTwoHit = candidate("X", 0.99);
    List<RetrievalCandidate> fused =
        fusion.fuseAll(List.of(List.of(routeOneHit), List.of(), List.of(routeTwoHit)), 60);

    assertEquals(1, fused.size());
    assertEquals(1.0 / 61 + 1.0 / 61, fused.getFirst().rerankScore(), 1e-12);
    assertEquals(routeOneHit.hit(), fused.getFirst().hit(), "hit 保留先出现路（route0）的条目");
  }

  private RetrievalCandidate candidate(String chunkId, double score) {
    return new RetrievalCandidate(
        new VectorSearchHit(chunkId, "doc-1", score, "文本-" + chunkId, Map.of()), score);
  }
}
