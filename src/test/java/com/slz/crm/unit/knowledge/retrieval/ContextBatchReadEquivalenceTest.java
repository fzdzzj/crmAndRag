package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 快照批读等价与有界降级测试（update-context-snapshot-batch-read）。
 *
 * <p>「原路径」在本测试内复刻（批读改造前的逐命中算法：每命中 selectById 查子块/父块、回退时逐命中 selectList 查邻居， 含原异常降级口径），与生产 {@link
 * ContextBuilder}（批读路径）在同一份内存快照行上逐场景对照， 断言输出上下文逐字等价；并锁定调用形状：五命中无父块成功路径快照 SQL 不超过 3
 * 条、批查异常只对受影响批次做一次有界逐条回查、 持续故障不产生无上限重试、大候选集合按生产侧 {@code
 * NeighborContextSupport.SNAPSHOT_BATCH_LIMIT} 拆批不生成无界 IN。 计数断言一律取「新路径构建前后差值」，避免参照实现自身的调用混入。
 *
 * <p>缺失 chunkIndex 元数据的命中在旧路径会于 {@code hitChunkIndex - 1} 处拆箱 NPE——该缺陷为独立正确性事项
 * （见提案），本测试不构造该输入、也不在新路径中顺手修复或掩盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextBatchReadEquivalenceTest {

  private static final String DOC_A = "doc-a";
  private static final String DOC_B = "doc-b";

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private DynamicConfigService dynamicConfigService;

  // ---------------------------------------------------------------- 等价场景

  /** 五命中单文档无父块：前后邻居、首末块边界全覆盖；输出逐字等价且快照 SQL = 2（≤ 3）。 */
  @Test
  void fiveHitsNoParentsAreByteEquivalentWithinThreeSnapshotSql() {
    FakeChunkMapper fake = new FakeChunkMapper();
    for (int index = 1; index <= 7; index++) {
      fake.addChildRow((long) index, DOC_A, index, "第" + index + "块内容", 1);
    }
    List<RetrievalCandidate> candidates = new ArrayList<>();
    for (int index = 2; index <= 6; index++) {
      candidates.add(candidate(String.valueOf(index), DOC_A, index, "第" + index + "块内容", 1));
    }
    ContextBuilder builder = newBuilder(fake);

    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    long[] before = fake.counters();
    String actual = builder.build(candidates);
    long[] delta = fake.deltasSince(before);

    assertThat(actual).isEqualTo(legacy);
    assertThat(actual).contains("[1] （前文承接）第1块内容").contains("（后文承接）第7块内容");
    assertThat(delta[FakeChunkMapper.BATCH]).isEqualTo(1);
    assertThat(delta[FakeChunkMapper.BY_ID]).isZero();
    assertThat(delta[FakeChunkMapper.LIST]).isEqualTo(1);
    assertThat(delta[FakeChunkMapper.SNAPSHOT]).isLessThanOrEqualTo(3);
  }

  /** 跨文档命中：批读行含另一文档的同序号行时，绝不把跨文档行拼入上下文（与旧路径一致）。 */
  @Test
  void crossDocumentNeighborsStayIsolatedAndEquivalent() {
    FakeChunkMapper fake = new FakeChunkMapper();
    for (int index = 1; index <= 4; index++) {
      fake.addChildRow((long) index, DOC_A, index, "A文第" + index + "块", 1);
      fake.addChildRow((long) (index + 100), DOC_B, index, "B文第" + index + "块", 2);
    }
    List<RetrievalCandidate> candidates =
        List.of(candidate("1", DOC_A, 1, "A文第1块", 1), candidate("102", DOC_B, 2, "B文第2块", 2));
    ContextBuilder builder = newBuilder(fake);

    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    String actual = builder.build(candidates);

    assertThat(actual).isEqualTo(legacy);
    assertThat(actual).isEqualTo("[1] A文第1块\n（后文承接）A文第2块\n[2] （前文承接）B文第1块\nB文第2块\n（后文承接）B文第3块");
  }

  /** 父块与邻居混合：有父块用父块全文、父块缺行/未挂父块/非数字 id/快照缺行回退邻居；输出逐字等价，快照 SQL ≤ 3。 */
  @Test
  void mixedParentAndNeighborPathsAreEquivalentWithinThreeSnapshotSql() {
    FakeChunkMapper fake = new FakeChunkMapper();
    fake.addChildRow(11L, DOC_A, 1, "子块甲", 1);
    fake.addParentRow(12L, DOC_A, "父块全文甲：完整回款流程说明。");
    fake.childRow(11L).setParentChunkId(12L); // 命中甲挂父块 12
    fake.addChildRow(21L, DOC_A, 2, "子块乙", 1);
    fake.addChildRow(31L, DOC_A, 3, "子块丙", 1);
    fake.childRow(31L).setParentChunkId(99L); // 挂父块 99，父块行缺失（脏引用）
    fake.addChildRow(41L, DOC_A, 4, "子块丁", 1);
    List<RetrievalCandidate> candidates =
        List.of(
            candidate("11", DOC_A, 1, "子块甲", 1),
            candidate("21", DOC_A, 2, "子块乙", 1),
            candidate("31", DOC_A, 3, "子块丙", 1),
            candidate("benchdoc-7", DOC_A, 4, "子块丁", 1), // 评测非数字占位 id
            candidate("555", DOC_A, 5, "子块戊", 1)); // 快照行整体缺失
    ContextBuilder builder = newBuilder(fake);

    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    long[] before = fake.counters();
    String actual = builder.build(candidates);
    long[] delta = fake.deltasSince(before);

    assertThat(actual).isEqualTo(legacy);
    assertThat(actual).contains("父块全文甲：完整回款流程说明。");
    assertThat(delta[FakeChunkMapper.BATCH]).isEqualTo(2); // 子块批 + 父块批
    assertThat(delta[FakeChunkMapper.SNAPSHOT]).isLessThanOrEqualTo(3);
  }

  /** 重复命中（同一 chunkId 两次）：编号独立、内容一致，输出与旧路径逐字等价。 */
  @Test
  void duplicateHitsAreEquivalent() {
    FakeChunkMapper fake = new FakeChunkMapper();
    fake.addChildRow(7L, DOC_A, 7, "第七块", 1);
    List<RetrievalCandidate> candidates =
        List.of(candidate("7", DOC_A, 7, "第七块", 1), candidate("7", DOC_A, 7, "第七块", 1));
    ContextBuilder builder = newBuilder(fake);

    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    String actual = builder.build(candidates);

    assertThat(actual).isEqualTo(legacy);
    assertThat(actual).isEqualTo("[1] 第七块\n[2] 第七块");
  }

  /** 同页优先与同页主键小者：同序号多行时新旧路径取同一行。 */
  @Test
  void samePageTieBreakIsEquivalent() {
    FakeChunkMapper fake = new FakeChunkMapper();
    // 命中块 chunkIndex=2，同序号两行都是 prev 目标（chunkIndex=1）：同页行主键反而更大
    DocumentVectorChunkEntity crossPage = fake.addChildRow(1L, DOC_A, 1, "跨页邻居", 7);
    DocumentVectorChunkEntity samePage = fake.addChildRow(2L, DOC_A, 1, "同页邻居", 2);
    assertThat(samePage.getId()).isGreaterThan(crossPage.getId());
    ContextBuilder builder = newBuilder(fake);

    List<RetrievalCandidate> candidates = List.of(candidate("9", DOC_A, 2, "命中块", 2));
    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    String actual = builder.build(candidates);

    assertThat(actual).isEqualTo(legacy);
    assertThat(actual).contains("同页邻居").doesNotContain("跨页邻居");
  }

  /** 邻居开关关闭（parent-expand on）：无父块命中回退纯拼接，不访问邻居查询，输出逐字等价。 */
  @Test
  void neighborsOffKeepsPlainAssemblyEquivalent() {
    FakeChunkMapper fake = new FakeChunkMapper();
    fake.addChildRow(11L, DOC_A, 1, "子块甲", 1);
    fake.addParentRow(12L, DOC_A, "父块全文甲。");
    fake.childRow(11L).setParentChunkId(12L);
    fake.addChildRow(21L, DOC_A, 2, "子块乙", 1);
    List<RetrievalCandidate> candidates =
        List.of(candidate("11", DOC_A, 1, "子块甲", 1), candidate("21", DOC_A, 2, "子块乙", 1));
    ContextBuilder builder = newBuilder(fake);
    // 专属桩在 newBuilder 的通配桩之后注册，Mockito 以最后注册为准
    when(dynamicConfigService.get("rag.context.neighbors", Integer.class, 1)).thenReturn(0);

    String legacy = legacyParentExpandNeighborsOff(candidates, fake.proxy());
    long[] before = fake.counters();
    String actual = builder.build(candidates);

    assertThat(actual).isEqualTo(legacy);
    assertThat(actual).isEqualTo("[1] 父块全文甲。\n[2] 子块乙");
    assertThat(fake.deltasSince(before)[FakeChunkMapper.LIST]).isZero();
  }

  /** 空命中：保持既有空结果，不访问快照表。 */
  @Test
  void emptyCandidatesProduceEmptyContextWithoutSnapshotAccess() {
    FakeChunkMapper fake = new FakeChunkMapper();
    ContextBuilder builder = newBuilder(fake);

    String actual = builder.build(List.of());

    assertThat(actual).isEmpty();
    assertThat(fake.snapshotSqlCalls()).isZero();
  }

  /** parent-expand=off：邻居模式批读与旧逐命中路径逐字等价。 */
  @Test
  void parentExpandOffNeighborModeIsEquivalent() {
    FakeChunkMapper fake = new FakeChunkMapper();
    for (int index = 1; index <= 5; index++) {
      fake.addChildRow((long) index, DOC_A, index, "第" + index + "块", 1);
    }
    List<RetrievalCandidate> candidates =
        List.of(candidate("2", DOC_A, 2, "第2块", 1), candidate("4", DOC_A, 4, "第4块", 1));
    ContextBuilder builder = newBuilder(fake);
    // 专属桩在 newBuilder 的通配桩之后注册，Mockito 以最后注册为准
    when(dynamicConfigService.get("rag.context.parent-expand", String.class, "on"))
        .thenReturn("off");

    String legacy = legacyNeighborsOnly(candidates, fake.proxy());
    long[] before = fake.counters();
    String actual = builder.build(candidates);

    assertThat(actual).isEqualTo(legacy);
    assertThat(fake.deltasSince(before)[FakeChunkMapper.LIST]).isEqualTo(1);
  }

  // ---------------------------------------------------------------- 失败与批次边界

  /** 批查异常：受影响批次有界逐条回查一次（旧路径形状），输出与旧路径逐字等价。 */
  @Test
  void transientBatchFailureFallsBackBoundedOncePerAffectedId() {
    FakeChunkMapper fake = new FakeChunkMapper();
    for (int index = 1; index <= 7; index++) {
      fake.addChildRow((long) index, DOC_A, index, "第" + index + "块", 1);
    }
    fake.failBatchIdsOnCall = ordinal -> ordinal == 1; // 子块批查第 1 次抛异常
    List<RetrievalCandidate> candidates = new ArrayList<>();
    for (int index = 2; index <= 6; index++) {
      candidates.add(candidate(String.valueOf(index), DOC_A, index, "第" + index + "块", 1));
    }
    ContextBuilder builder = newBuilder(fake);

    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    long[] before = fake.counters();
    String actual = builder.build(candidates);
    long[] delta = fake.deltasSince(before);

    assertThat(actual).isEqualTo(legacy);
    assertThat(delta[FakeChunkMapper.BATCH]).isEqualTo(1);
    assertThat(delta[FakeChunkMapper.BY_ID]).isEqualTo(5L); // 受影响主键逐条恰好一次，无重试放大
    assertThat(delta[FakeChunkMapper.LIST]).isEqualTo(1);
  }

  /** 持续故障：批查与逐条回查全部失败 → 按无父块/无邻居语义降级，查询总次数有界（不随重试放大）。 */
  @Test
  void persistentFailureDegradesWithBoundedQueries() {
    FakeChunkMapper fake = new FakeChunkMapper();
    for (int index = 1; index <= 3; index++) {
      fake.addChildRow((long) index, DOC_A, index, "第" + index + "块", 1);
    }
    fake.failBatchIdsOnCall = ordinal -> true;
    fake.failSelectByIdOnCall = ordinal -> true;
    fake.failListOnCall = ordinal -> true;
    List<RetrievalCandidate> candidates =
        List.of(candidate("2", DOC_A, 2, "第2块", 1), candidate("3", DOC_A, 3, "第3块", 1));
    ContextBuilder builder = newBuilder(fake);

    String actual = builder.build(candidates);

    assertThat(actual).isEqualTo("[1] 第2块\n[2] 第3块");
    assertThat(fake.selectBatchIdsCalls).isEqualTo(1); // 恰好一次批查
    assertThat(fake.selectByIdCalls).isEqualTo(2); // 受影响主键各回查一次
    assertThat(fake.selectListCalls).isEqualTo(1 + 2); // 一次批查 + 受影响命中各回查一次
  }

  /** 邻居批查异常：受影响批次按旧路径逐命中回查一次，仍成功则输出与旧路径逐字等价。 */
  @Test
  void transientNeighborBatchFailureFallsBackBoundedOncePerHit() {
    FakeChunkMapper fake = new FakeChunkMapper();
    for (int index = 1; index <= 5; index++) {
      fake.addChildRow((long) index, DOC_A, index, "第" + index + "块", 1);
    }
    List<RetrievalCandidate> candidates = new ArrayList<>();
    for (int index = 2; index <= 4; index++) {
      candidates.add(candidate(String.valueOf(index), DOC_A, index, "第" + index + "块", 1));
    }
    ContextBuilder builder = newBuilder(fake);

    // 参照实现先在无故障状态下取得旧路径正确输出，再对批读路径注入一次性邻居批查故障
    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    long base = fake.selectListCalls;
    fake.failListOnCall = ordinal -> ordinal == base + 1; // 批读路径的邻居批查第 1 次抛异常
    long[] before = fake.counters();
    String actual = builder.build(candidates);
    long[] delta = fake.deltasSince(before);

    assertThat(actual).isEqualTo(legacy);
    assertThat(delta[FakeChunkMapper.LIST]).isEqualTo(1 + 3); // 批查 1 次 + 受影响命中各回查 1 次
  }

  /** 大候选集合：501 命中拆为 ≤500 的有限批次（无界 IN 反例），失败批只影响受影响主键，输出仍与旧路径逐字等价。 */
  @Test
  void largeCandidateSetSplitsIntoBoundedBatchesAndKeepsSuccessfulBatch() {
    FakeChunkMapper fake = new FakeChunkMapper();
    for (int index = 1; index <= 501; index++) {
      fake.addChildRow((long) index, DOC_A, index, "第" + index + "块", 1);
    }
    fake.failBatchIdsOnCall = ordinal -> ordinal == 2; // 第 2 批（1 个 id）批查失败 → 逐条回查
    List<RetrievalCandidate> candidates = new ArrayList<>();
    for (int index = 1; index <= 501; index++) {
      candidates.add(candidate(String.valueOf(index), DOC_A, index, "第" + index + "块", 1));
    }
    ContextBuilder builder = newBuilder(fake);
    // 501 命中的未压缩上下文远超默认 4096 token 预算；本案只对照批读拼装，预算放大到不触发压缩
    when(dynamicConfigService.get("rag.context.token-budget", Integer.class, 4096))
        .thenReturn(Integer.MAX_VALUE);

    String legacy = legacyParentExpand(candidates, fake.proxy(), true);
    long[] before = fake.counters();
    String actual = builder.build(candidates);
    long[] delta = fake.deltasSince(before);

    assertByteEquivalent(legacy, actual);
    assertThat(fake.selectBatchIdBatchSizes).containsExactly(500, 1);
    assertThat(delta[FakeChunkMapper.BATCH]).isEqualTo(2);
    assertThat(delta[FakeChunkMapper.BY_ID]).isEqualTo(1); // 仅第 2 批受影响，回查恰好一次
    assertThat(delta[FakeChunkMapper.LIST]).isEqualTo(2); // 501 命中拆 500+1 两批邻居查询
  }

  /** 逐字等价断言（带首差异定位）：AssertJ 对超长字符串的失败消息会截断，大候选场景用本断言给出行级首差异与上下文窗口。 */
  private static void assertByteEquivalent(String expected, String actual) {
    if (!expected.equals(actual)) {
      String[] expectedLines = expected.split("\n", -1);
      String[] actualLines = actual.split("\n", -1);
      StringBuilder detail =
          new StringBuilder("输出不等价：行数 期望")
              .append(expectedLines.length)
              .append(" 实际")
              .append(actualLines.length)
              .append("；");
      for (int index = 0; index < Math.min(expectedLines.length, actualLines.length); index++) {
        if (!expectedLines[index].equals(actualLines[index])) {
          detail
              .append("首差异行 ")
              .append(index)
              .append(" 期望[")
              .append(expectedLines[index])
              .append("] 实际[")
              .append(actualLines[index])
              .append("] 实际窗口[");
          int from = Math.max(0, index - 1);
          int to = Math.min(actualLines.length, index + 5);
          for (int window = from; window < to; window++) {
            detail.append(window).append('=').append(actualLines[window]).append(" / ");
          }
          detail.append(']');
          break;
        }
      }
      throw new AssertionError(detail.toString());
    }
    assertThat(actual).isEqualTo(expected);
  }

  // ---------------------------------------------------------------- 装配

  private ContextBuilder newBuilder(FakeChunkMapper fake) {
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    return new ContextBuilder(
        fake.proxy(), dynamicConfigProvider, new RuleContextCompressor(), null);
  }

  private RetrievalCandidate candidate(
      String chunkId, String docId, int chunkIndex, String text, long pageNo) {
    Map<String, Object> metadata =
        Map.of("chunkIndex", chunkIndex, "filename", "sales.txt", "pageNo", pageNo);
    return new RetrievalCandidate(new VectorSearchHit(chunkId, docId, 0.8d, text, metadata), 0.8d);
  }

  // ---------------------------------------------------------------- 原路径复刻（批读改造前的逐命中算法）

  /** 原路径（parent-expand on）：每命中 selectById 查子块/父块，回退逐命中邻居拼装；异常按无父块降级。 */
  private static String legacyParentExpand(
      List<RetrievalCandidate> candidates, DocumentVectorChunkMapper mapper, boolean neighbors) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        builder.append('\n');
      }
      builder.append('[').append(index + 1).append("] ");
      VectorSearchHit hit = candidates.get(index).hit();
      String parentText = legacyLookupParentText(hit, mapper);
      if (parentText != null) {
        builder.append(parentText.strip());
      } else if (neighbors) {
        legacyAppendWithNeighbors(builder, hit, mapper);
      } else {
        builder.append(hit.text().strip());
      }
    }
    return builder.toString();
  }

  /** 原路径（parent-expand on + neighbors off）：父块或纯文本。 */
  private static String legacyParentExpandNeighborsOff(
      List<RetrievalCandidate> candidates, DocumentVectorChunkMapper mapper) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        builder.append('\n');
      }
      builder.append('[').append(index + 1).append("] ");
      VectorSearchHit hit = candidates.get(index).hit();
      String parentText = legacyLookupParentText(hit, mapper);
      if (parentText != null) {
        builder.append(parentText.strip());
      } else {
        builder.append(hit.text().strip());
      }
    }
    return builder.toString();
  }

  /** 原路径（parent-expand off）：纯邻居模式逐命中拼装。 */
  private static String legacyNeighborsOnly(
      List<RetrievalCandidate> candidates, DocumentVectorChunkMapper mapper) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        builder.append('\n');
      }
      builder.append('[').append(index + 1).append("] ");
      legacyAppendWithNeighbors(builder, candidates.get(index).hit(), mapper);
    }
    return builder.toString();
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 复刻旧生产代码的异常降级口径，勿改
  private static String legacyLookupParentText(
      VectorSearchHit hit, DocumentVectorChunkMapper mapper) {
    String chunkId = hit.chunkId();
    String result = null;
    if (chunkId != null && chunkId.chars().allMatch(Character::isDigit)) {
      try {
        DocumentVectorChunkEntity child = mapper.selectById(Long.parseLong(chunkId));
        if (child != null && child.getParentChunkId() != null) {
          DocumentVectorChunkEntity parent = mapper.selectById(child.getParentChunkId());
          if (parent != null && parent.getChunkText() != null && !parent.getChunkText().isBlank()) {
            result = parent.getChunkText();
          }
        }
      } catch (Exception exception) {
        // 原路径：告警后按无父块降级（日志在测试复刻中省略，不影响输出等价断言）
      }
    }
    return result;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 复刻旧生产代码的异常降级口径，勿改
  private static void legacyAppendWithNeighbors(
      StringBuilder builder, VectorSearchHit hit, DocumentVectorChunkMapper mapper) {
    String hitText = hit.text() == null ? "" : hit.text().strip();
    List<DocumentVectorChunkEntity> neighbors = legacyFetchNeighbors(hit, mapper);
    Integer hitChunkIndex = legacyChunkIndex(hit);
    DocumentVectorChunkEntity prev = legacyNeighborAt(neighbors, hitChunkIndex - 1, hit);
    DocumentVectorChunkEntity next = legacyNeighborAt(neighbors, hitChunkIndex + 1, hit);
    if (prev != null && !prev.getChunkText().isBlank()) {
      builder.append("（前文承接）").append(prev.getChunkText().strip()).append('\n');
    }
    builder.append(hitText);
    if (next != null && !next.getChunkText().isBlank()) {
      builder.append('\n').append("（后文承接）").append(next.getChunkText().strip());
    }
  }

  private static List<DocumentVectorChunkEntity> legacyFetchNeighbors(
      VectorSearchHit hit, DocumentVectorChunkMapper mapper) {
    Integer chunkIndex = legacyChunkIndex(hit);
    List<DocumentVectorChunkEntity> result = List.of();
    if (chunkIndex != null
        && chunkIndex >= 0
        && hit.documentId() != null
        && !hit.documentId().isBlank()) {
      List<Integer> targets = new ArrayList<>();
      if (chunkIndex > 0) {
        targets.add(chunkIndex - 1);
      }
      targets.add(chunkIndex + 1);
      try {
        result =
            mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        DocumentVectorChunkEntity>()
                    .eq("document_id", hit.documentId())
                    .eq("chunk_role", "CHILD")
                    .in("chunk_index", targets));
      } catch (Exception exception) {
        // 原路径：按无邻居降级
      }
    }
    return result;
  }

  private static DocumentVectorChunkEntity legacyNeighborAt(
      List<DocumentVectorChunkEntity> rows, int targetIndex, VectorSearchHit hit) {
    Integer hitPageNo = legacyPositivePageNo(hit);
    DocumentVectorChunkEntity best = null;
    if (targetIndex >= 0) {
      for (DocumentVectorChunkEntity row : rows) {
        if (row.getChunkIndex() == null
            || row.getChunkIndex() != targetIndex
            || row.getChunkText() == null
            || row.getDocumentId() == null
            || !row.getDocumentId().equals(hit.documentId())) {
          continue;
        }
        if (best == null || legacyBetterNeighbor(row, best, hitPageNo)) {
          best = row;
        }
      }
    }
    return best;
  }

  private static boolean legacyBetterNeighbor(
      DocumentVectorChunkEntity candidate, DocumentVectorChunkEntity current, Integer hitPageNo) {
    boolean candidateSamePage =
        candidate.getPageNo() != null && candidate.getPageNo().equals(hitPageNo);
    boolean currentSamePage = current.getPageNo() != null && current.getPageNo().equals(hitPageNo);
    boolean result;
    if (candidateSamePage != currentSamePage) {
      result = candidateSamePage;
    } else {
      result =
          candidate.getId() != null
              && current.getId() != null
              && candidate.getId() < current.getId();
    }
    return result;
  }

  private static Integer legacyChunkIndex(VectorSearchHit hit) {
    Object value = hit.metadata().get("chunkIndex");
    return value instanceof Number number ? number.intValue() : null;
  }

  private static Integer legacyPositivePageNo(VectorSearchHit hit) {
    Object value = hit.metadata().get("pageNo");
    Integer result = null;
    if (value instanceof Number number) {
      long pageNo = number.longValue();
      if (pageNo > 0) {
        result = (int) pageNo;
      }
    }
    return result;
  }

  // ---------------------------------------------------------------- 内存 mapper double（含故障注入与计数）

  /**
   * 内存 DocumentVectorChunkMapper double：按 QueryWrapper 的 document_id/chunk_role/chunk_index 条件过滤；
   * 按调用序号注入 selectBatchIds/selectById/selectList 失败（模拟批查异常与持续故障），记录调用次数与批大小。
   */
  private static final class FakeChunkMapper {

    private static final int BATCH = 0;
    private static final int BY_ID = 1;
    private static final int LIST = 2;
    private static final int SNAPSHOT = 3;

    private final List<DocumentVectorChunkEntity> rows = new ArrayList<>();

    IntPredicate failBatchIdsOnCall = ordinal -> false;
    IntPredicate failSelectByIdOnCall = ordinal -> false;
    IntPredicate failListOnCall = ordinal -> false;

    long selectBatchIdsCalls;
    long selectByIdCalls;
    long selectListCalls;
    final List<Integer> selectBatchIdBatchSizes = new ArrayList<>();

    DocumentVectorChunkEntity addChildRow(
        Long id, String docId, int chunkIndex, String text, int pageNo) {
      DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
      entity.setId(id);
      entity.setDocumentId(docId);
      entity.setChunkIndex(chunkIndex);
      entity.setChunkText(text);
      entity.setChunkRole("CHILD");
      entity.setPageNo(pageNo);
      rows.add(entity);
      return entity;
    }

    DocumentVectorChunkEntity addParentRow(Long id, String docId, String text) {
      DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
      entity.setId(id);
      entity.setDocumentId(docId);
      entity.setChunkIndex(999);
      entity.setChunkText(text);
      entity.setChunkRole("PARENT");
      rows.add(entity);
      return entity;
    }

    DocumentVectorChunkEntity childRow(Long id) {
      return rows.stream().filter(row -> id.equals(row.getId())).findFirst().orElseThrow();
    }

    long snapshotSqlCalls() {
      return selectBatchIdsCalls + selectByIdCalls + selectListCalls;
    }

    long[] counters() {
      return new long[] {selectBatchIdsCalls, selectByIdCalls, selectListCalls, snapshotSqlCalls()};
    }

    long[] deltasSince(long[] before) {
      return new long[] {
        selectBatchIdsCalls - before[BATCH],
        selectByIdCalls - before[BY_ID],
        selectListCalls - before[LIST],
        snapshotSqlCalls() - before[SNAPSHOT]
      };
    }

    DocumentVectorChunkMapper proxy() {
      return (DocumentVectorChunkMapper)
          Proxy.newProxyInstance(
              DocumentVectorChunkMapper.class.getClassLoader(),
              new Class<?>[] {DocumentVectorChunkMapper.class},
              handler());
    }

    private InvocationHandler handler() {
      return (proxy, method, args) -> {
        switch (method.getName()) {
          case "selectById" -> {
            long ordinal = ++selectByIdCalls;
            if (failSelectByIdOnCall.test((int) ordinal)) {
              throw new IllegalStateException("selectById 注入故障");
            }
            return selectById(args.length > 0 ? args[0] : null);
          }
          case "selectBatchIds" -> {
            long ordinal = ++selectBatchIdsCalls;
            selectBatchIdBatchSizes.add(((List<?>) args[0]).size());
            if (failBatchIdsOnCall.test((int) ordinal)) {
              throw new IllegalStateException("selectBatchIds 注入故障");
            }
            return selectBatchIds(args[0]);
          }
          case "selectList" -> {
            long ordinal = ++selectListCalls;
            if (failListOnCall.test((int) ordinal)) {
              throw new IllegalStateException("selectList 注入故障");
            }
            return selectList(args[0]);
          }
          case "toString" -> {
            return "FakeChunkMapper(" + rows.size() + " rows)";
          }
          default -> {
            return defaultReturn(method.getReturnType());
          }
        }
      };
    }

    private DocumentVectorChunkEntity selectById(Object id) {
      Long target = asLong(id);
      if (target == null) {
        return null;
      }
      return rows.stream().filter(row -> target.equals(row.getId())).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<DocumentVectorChunkEntity> selectBatchIds(Object ids) {
      List<DocumentVectorChunkEntity> matched = new ArrayList<>();
      for (Object id : (Iterable<Object>) ids) {
        DocumentVectorChunkEntity row = selectById(id);
        if (row != null) {
          matched.add(row);
        }
      }
      return matched;
    }

    private List<DocumentVectorChunkEntity> selectList(Object wrapper) {
      Map<String, List<Object>> criteria = parseCriteria(wrapper);
      List<DocumentVectorChunkEntity> matched = new ArrayList<>();
      for (DocumentVectorChunkEntity row : rows) {
        if (matches(criteria, row)) {
          matched.add(row);
        }
      }
      return matched;
    }

    private Map<String, List<Object>> parseCriteria(Object wrapper) {
      Map<String, List<Object>> criteria = new LinkedHashMap<>();
      if (!(wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper)) {
        return criteria;
      }
      String sql = String.valueOf(abstractWrapper.getSqlSegment());
      Map<String, Object> params = new LinkedHashMap<>(abstractWrapper.getParamNameValuePairs());
      Matcher inMatcher = Pattern.compile("([\\w_]+)\\s+IN\\s*\\(([^)]*)\\)").matcher(sql);
      while (inMatcher.find()) {
        List<Object> values = new ArrayList<>();
        Matcher token = Pattern.compile("MPGENVAL\\d+").matcher(inMatcher.group(2));
        while (token.find()) {
          values.add(resolveParam(params, token.group()));
        }
        criteria.put(inMatcher.group(1).toLowerCase(java.util.Locale.ROOT), values);
      }
      Matcher eqMatcher = Pattern.compile("([\\w_]+)\\s*=\\s*#\\{([^}]*\\.(\\w+))\\}").matcher(sql);
      while (eqMatcher.find()) {
        criteria.put(
            eqMatcher.group(1).toLowerCase(java.util.Locale.ROOT),
            List.of(resolveParam(params, eqMatcher.group(3))));
      }
      return criteria;
    }

    private boolean matches(Map<String, List<Object>> criteria, DocumentVectorChunkEntity row) {
      for (Map.Entry<String, List<Object>> entry : criteria.entrySet()) {
        switch (entry.getKey()) {
          case "document_id" -> {
            if (!entry.getValue().contains(row.getDocumentId())) {
              return false;
            }
          }
          case "chunk_role" -> {
            if (!entry.getValue().contains(row.getChunkRole())) {
              return false;
            }
          }
          case "chunk_index" -> {
            boolean hit = false;
            for (Object value : entry.getValue()) {
              Long number = asLong(value);
              if (number != null
                  && row.getChunkIndex() != null
                  && number.longValue() == row.getChunkIndex().longValue()) {
                hit = true;
                break;
              }
            }
            if (!hit) {
              return false;
            }
          }
          default -> {
            // 未识别列：宽容放行
          }
        }
      }
      return true;
    }

    private static Object resolveParam(Map<String, Object> params, String tokenName) {
      Object exact = params.get(tokenName);
      if (exact != null || params.containsKey(tokenName)) {
        return exact;
      }
      for (Map.Entry<String, Object> entry : params.entrySet()) {
        if (entry.getKey().endsWith("." + tokenName) || entry.getKey().equals(tokenName)) {
          return entry.getValue();
        }
      }
      return null;
    }

    private static Long asLong(Object value) {
      if (value instanceof Number number) {
        return number.longValue();
      }
      if (value instanceof String text) {
        try {
          return Long.parseLong(text);
        } catch (NumberFormatException exception) {
          return null;
        }
      }
      return null;
    }

    private static Object defaultReturn(Class<?> returnType) {
      if (!returnType.isPrimitive()) {
        return null;
      }
      if (returnType == boolean.class) {
        return false;
      }
      if (returnType == long.class) {
        return 0L;
      }
      return 0;
    }
  }
}
