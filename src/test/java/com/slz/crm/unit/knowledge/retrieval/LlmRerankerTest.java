package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.LlmReranker;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.VectorSearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * LLM 重排器单测（complete-hybrid-retrieval-and-rerank 任务 4.2）： 成功路径按 LLM 名次重排；失败/空输出/超时三条回退路径全部落回默认重排链。
 */
@ExtendWith(MockitoExtension.class)
class LlmRerankerTest {
  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  private DefaultWeightedReranker fallback;

  private LlmReranker reranker(long timeoutMs) {
    fallback = new DefaultWeightedReranker(new Bm25Scorer(), dynamicConfigProvider);
    return new LlmReranker(modelProvider, fallback, dynamicConfigProvider) {
      @Override
      protected long resolveTimeoutMs() {
        return timeoutMs;
      }
    };
  }

  /** 成功：LLM 输出 [3,1,2] → 按名次重排，分数单调递减，候选全集不丢。 */
  @Test
  void successfulListwiseOutputShouldReorderCandidates() {
    LlmReranker reranker = reranker(3000);
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("[3,1,2]", "qwen-max", 100L, 10L, 110L));
    List<RetrievalCandidate> candidates =
        List.of(candidate("c1"), candidate("c2"), candidate("c3"));

    List<RetrievalCandidate> reranked = reranker.rerank("查询", candidates);

    assertEquals("c3", reranked.get(0).hit().chunkId());
    assertEquals("c1", reranked.get(1).hit().chunkId());
    assertEquals("c2", reranked.get(2).hit().chunkId());
    assertEquals(1.0, reranked.get(0).rerankScore(), 1e-12);
    assertEquals(2.0 / 3, reranked.get(1).rerankScore(), 1e-12);
    assertEquals(1.0 / 3, reranked.get(2).rerankScore(), 1e-12);
  }

  /** 回退路径1——调用失败（Provider 抛异常）：回退默认链，不抛错。 */
  @Test
  void providerFailureShouldFallBackToDefaultChain() {
    LlmReranker reranker = reranker(3000);
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenThrow(new IllegalStateException("网络不可用"));
    List<RetrievalCandidate> candidates = List.of(candidate("c1"), candidate("c2"));

    List<RetrievalCandidate> reranked = reranker.rerank("查询", candidates);

    assertEquals(fallback.rerank("查询", candidates), reranked);
  }

  /** 回退路径2——空输出/不可解析（无编号）：回退默认链。 */
  @Test
  void blankOrUnparsableOutputShouldFallBackToDefaultChain() {
    LlmReranker reranker = reranker(3000);
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("我觉得都不错", "qwen-max", 100L, 10L, 110L));
    List<RetrievalCandidate> candidates = List.of(candidate("c1"), candidate("c2"));

    assertEquals(fallback.rerank("查询", candidates), reranker.rerank("查询", candidates));
  }

  /** 回退路径3——超时：等待超过 timeout-ms 即回退默认链（模拟 Provider 挂起）。 */
  @Test
  void timeoutShouldFallBackToDefaultChain() {
    LlmReranker reranker = reranker(50);
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(300);
              return ModelCallResult.ofText("[1,2]", "qwen-max", 100L, 10L, 110L);
            });
    List<RetrievalCandidate> candidates = List.of(candidate("c1"), candidate("c2"));

    long startedAt = System.currentTimeMillis();
    List<RetrievalCandidate> reranked = reranker.rerank("查询", candidates);

    assertEquals(fallback.rerank("查询", candidates), reranked);
    assertTrue(System.currentTimeMillis() - startedAt < 2000, "超时后应尽快回退而不是等满挂起时长");
  }

  /** 部分编号缺失时候选按原顺序补尾，全集不丢。 */
  @Test
  void partialOrderShouldAppendMissingCandidates() {
    LlmReranker reranker = reranker(3000);
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("[2]", "qwen-max", 100L, 10L, 110L));
    List<RetrievalCandidate> candidates =
        List.of(candidate("c1"), candidate("c2"), candidate("c3"));

    List<RetrievalCandidate> reranked = reranker.rerank("查询", candidates);

    assertEquals(3, reranked.size());
    assertEquals("c2", reranked.get(0).hit().chunkId());
    assertEquals("c1", reranked.get(1).hit().chunkId());
    assertEquals("c3", reranked.get(2).hit().chunkId());
  }

  private RetrievalCandidate candidate(String chunkId) {
    return new RetrievalCandidate(
        new VectorSearchHit(chunkId, "doc-1", 0.5, "候选片段-" + chunkId, Map.of()), 0.5);
  }
}
