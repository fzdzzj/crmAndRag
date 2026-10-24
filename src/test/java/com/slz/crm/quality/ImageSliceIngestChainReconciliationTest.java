package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.knowledge.document.ChunkHeaderText;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.document.ImageVectorCleanupRegistry;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 图像切片摄取链逐链对账探针（image-slice-ingest-chain-reconciliation，卡 27，零外呼）。
 *
 * <p>回答卡 26 遗留的归因问题：过闸 41 图 / 文本 embed 实计 36 的缺口是 {@code COUNTER-SUPPRESSED}（计数被测试侧缓存抑制，链路完好）还是
 * {@code CHAIN-BROKEN}（生产链真丢）。判据 R0–R6 见 {@code
 * openspec/changes/image-slice-ingest-chain-reconciliation/proposal.md}。
 *
 * <ul>
 *   <li><b>R0</b>：全程仅 Mockito 桩 {@code ModelProvider}（vision 内容键控确定性描述 / embedImage、embed
 *       确定性向量），结构上零外呼；
 *   <li><b>R1</b>：七链逐链计数（提取图数 → describe 次数 → 过闸数 → 切片数 → 子块行数 → embed 调用次数 → image 向量暂存数），相邻链等式断言
 *       且每链差值显式打印，N=1 与 N=8 两档；
 *   <li><b>R2</b>：同输入无缓存桩 vs 有缓存桩（键 = 完整 embedText）各跑一遍，四数 {@code {invocationsNoCache,
 *       invocationsWithCache, distinctEmbedTexts, childRows}}；同文件名 + 同页码 + 同描述构造必然命中对；
 *   <li><b>R3</b>：两条丢弃语义钉成断言——{@code storage.store} 单图失败只跳过该图；{@code buildSlices} 抛出未兜住异常时整批图片切片丢弃、
 *       文本切片照常；
 *   <li><b>R4</b>：每个图片切片落 {@code (pageNo, rowIndex, embedText)} 三件套并断言 rowIndex 恒 null，由数据推出
 *       embedText 碰撞充分条件；
 *   <li><b>R5/R6</b>：verdict 三选一由测量值机械推导并写锚 {@code
 *       docs/rag-quality/image-slice-ingest-chain-v1.json}（逐字节可复算）； {@code src/main} 零 diff。
 * </ul>
 *
 * <p>包内可见生产类（{@code DocumentIngestionSupport#chunkEmbedAndWrite}、{@code
 * ImageSliceIngestSupport#parseWithImageSlices}）以反射 调用，语义全部来自生产代码本体，不复制常量；{@code src/main} 零改动。
 */
@DisplayName("图像切片摄取链逐链对账：七链计数 / 缓存抑制判别 / 丢弃语义 / 结论锚（零外呼）")
class ImageSliceIngestChainReconciliationTest {

  private static final Path ANCHOR =
      Path.of("docs", "rag-quality", "image-slice-ingest-chain-v1.json");

  /** 首次生成开关（-Dimage.slice.chain.anchor.write=true）：常规运行只做逐字节比对，绝不落盘。 */
  private static final boolean WRITE_MODE = Boolean.getBoolean("image.slice.chain.anchor.write");

  /**
   * 卡 26 S1 期三份合成 PDF 的文件名（`d7abc37` 版 {@code NearDupIngestCooccurrenceIT:437}： {@code
   * "near-dup-ingest-cooc-p" + (pdfIndex + 1) + ".pdf"}）。
   *
   * <p>为什么这个前提必须进锚：本锚 R2 只证明「缓存能抑制计数」；要把卡 26 的缺口归因到缓存，还需「S1 语料真能产生 embedText 碰撞」。R4 推出的碰撞条件是 文件名 ∧
   * 类目 ∧ 页码 ∧ 描述 四输入全同；而 S1 三份 PDF 文件名互异、同文档内每页一图 ⇒ 碰撞为零 ⇒ 缓存抑制**不适用于** S1。
   */
  private static final List<String> S1_PDF_FILENAMES =
      List.of(
          "near-dup-ingest-cooc-p1.pdf",
          "near-dup-ingest-cooc-p2.pdf",
          "near-dup-ingest-cooc-p3.pdf");

  /** S1 缺口归因不可得时的缺失证据说明（verdict=INCONCLUSIVE 时写入锚）。 */
  private static final String S1_INAPPLICABLE_MISSING_EVIDENCE =
      "① 卡 26 的 S1 三份合成 PDF 文件名互异（near-dup-ingest-cooc-p1/p2/p3.pdf，d7abc37 版"
          + " NearDupIngestCooccurrenceIT:437），同文档内每页一图 ⇒ 按本锚 r4 推出的碰撞充分条件"
          + "（文件名∧类目∧页码∧描述四输入全同），S1 不可能出现 embedText 碰撞 ⇒ 测试侧缓存零命中，"
          + "无法解释该缺口；② 缺口两端 41/36 出自一个已不存在的断言版本（中止日志文案「ingest 阶段文本"
          + " embed 外呼必须等于过闸门图数」在提交版 :344/:350 均不存在），分母口径不可考 ⇒ 不能据此做精确"
          + "归因。定案需重放 S1 的第三份 PDF 语料做逐文档计数（需外呼授权），或承认该缺口不可考、"
          + "消融改用实测子块行数做分母";

  private static final String FILENAME = "reconcile-chain-probe.pdf";

  /** 异文件名对照组（R4 文件名因子）。 */
  private static final String OTHER_FILENAME = "reconcile-other-name.pdf";

  private static final String CATEGORY = "对账类目甲";

  /** 异类目对照组（R4 类目因子）。 */
  private static final String OTHER_CATEGORY = "对账类目乙";

  private static final String KB_ID = "9903";

  /** 显式配置图片帽（默认 5 会把大样本静默截断，第一链就会错）。 */
  private static final int MAX_IMAGES = 20;

  /** 七链名称（锚 chainOrder 与打印同源）。 */
  private static final String[] CHAIN_NAMES = {
    "提取图数", "describe次数", "过闸数", "切片数", "子块行数", "embed调用次数", "image向量暂存数"
  };

  /** 描述尾段：任意图片的描述都拼上这段（总长 &gt; 40 字且不含全角「（截图不清）」，保证过生产闸门）。 */
  private static final String DESCRIPTION_TAIL = "；含三层节点区块与两条箭头连线，附五根柱体与两条坐标轴的确定性转写替身，用于过生产四十字质量闸门";

  /** 存储装配故障注入文案（R3b 文档级吞异常用）。 */
  private static final String STORAGE_ASSEMBLY_FAILURE = "注入的存储装配失败";

  private static final String IMAGE_SLICE_SUPPORT_LOGGER =
      "com.slz.crm.knowledge.document.ImageSliceIngestSupport";

  private static final String IMAGE_SLICE_SWALLOW_PREFIX = "图片切片摄取失败";

  private static final String IMAGE_STORE_FAILURE_PREFIX = "图片对象落盘失败";

  private static final String IMAGE_EXTRACT_FAILURE_PREFIX = "PDF 内嵌图片提取失败";

  // ==================== R0 · 结构性零外呼自证 ====================

  @Test
  @DisplayName("R0：全程唯一 ModelProvider 是 Mockito 桩，桩计数可复算，外呼次数 = 0")
  void r0ZeroExternalCallByConstruction() throws Exception {
    ChainProvider chain = new ChainProvider(false);
    runHappyChain(1, "n1", chain);
    assertTrue(
        Mockito.mockingDetails(chain.provider).isMock(),
        "全程唯一 ModelProvider 必须是 Mockito 桩（未构造真实 provider）");
    assertTrue(chain.visionCalls.get() > 0, "桩 describe 计数必须 > 0（链路真跑过）");
    assertTrue(chain.embedImageCalls.get() > 0, "桩 embedImage 计数必须 > 0（链路真跑过）");
    assertTrue(chain.embedCalls.get() > 0, "桩 embed 计数必须 > 0（链路真跑过）");
    ModelProviderProperties properties = new ModelProviderProperties();
    assertTrue(
        properties.getApiKey() == null || properties.getApiKey().isBlank(),
        "测试侧 ModelProviderProperties 不携带 key（零外呼结构保证，不读环境变量不读 .env）");
    System.out.printf(
        "[chain-reconcile] stub 计数（外呼=0 对账）: vision=%d embedImage=%d embed=%d%n",
        chain.visionCalls.get(), chain.embedImageCalls.get(), chain.embedCalls.get());
  }

  // ==================== R1 · 七链对账（N=1 与 N=8 两档） ====================

  @Test
  @DisplayName("R1：七链逐链计数 + 相邻链恒等断言，每链差值显式打印（N=1 与 N=8）")
  void r1SevenChainReconciliation() throws Exception {
    ChainProvider n8Chain = null;
    for (int n : new int[] {1, 8}) {
      ChainProvider chain = new ChainProvider(false);
      HappyChain row = runHappyChain(n, "n" + n, chain);
      int[] values = row.counts().values();
      for (int i = 0; i + 1 < values.length; i++) {
        assertEquals(
            values[i],
            values[i + 1],
            row.counts().label() + " 相邻链必须恒等: " + CHAIN_NAMES[i] + " → " + CHAIN_NAMES[i + 1]);
      }
      assertEquals(n, values[0], "第一链提取图数必须等于构造图数（帽 " + MAX_IMAGES + " 未触发）");
      System.out.printf(
          "[chain-reconcile] %s 七链: 提取=%d describe=%d 过闸=%d 切片=%d 子块=%d embed=%d 暂存=%d 相邻差值=%s%n",
          row.counts().label(),
          values[0],
          values[1],
          values[2],
          values[3],
          values[4],
          values[5],
          values[6],
          row.counts().adjacentDeltas());
      if (n == 8) {
        n8Chain = chain;
      }
    }
    // 页码因子实证：N=8 八张互异图片同名同类目，embedText 两两互异（页码是唯一区分输入）
    assertEquals(
        8,
        new LinkedHashSet<>(n8Chain.embedKeysInCallOrder).size(),
        "同名同类目八图跨八页：embedText 必须两两互异（页码因子）");
  }

  // ==================== R2 · 缓存抑制判别（本卡核心） ====================

  @Test
  @DisplayName("R2：无缓存桩 vs 有缓存桩四数对账；必然命中对逼出 invocationsWithCache 恰少 1")
  void r2CacheSuppressionDiscrimination() throws Exception {
    R2Outcome outcome = runCacheCorpus(new ArrayList<>());
    assertEquals(
        outcome.childRows(), outcome.invocationsNoCache(), "判据一：无缓存桩 embed 调用数 == 子块行数（链路完好）");
    assertTrue(outcome.invocationsWithCache() < outcome.invocationsNoCache(), "判据二前置：缓存桩调用数必须更少");
    assertEquals(
        outcome.childRows() - outcome.distinctEmbedTexts(),
        outcome.invocationsNoCache() - outcome.invocationsWithCache(),
        "判据二：调用差值必须恰好等于 childRows − distinctEmbedTexts（重复 embedText 数）");
    assertTrue(outcome.hitConstructed(), "必须构造出一次必然缓存命中（否则本判据无鉴别力）");
    assertTrue(outcome.collidingEmbedTextsIdentical(), "命中对的两次 embedText 必须逐字相同");
    assertTrue(outcome.chainCountsUnchangedByCache(), "缓存只抑制计数不改链路：两轮子块行数必须一致");
    assertEquals("COUNTER-SUPPRESSED", outcome.cacheSuppressionVerdict());
    System.out.printf(
        "[chain-reconcile] R2 四数: invocationsNoCache=%d invocationsWithCache=%d distinctEmbedTexts=%d childRows=%d 命中=%d%n",
        outcome.invocationsNoCache(),
        outcome.invocationsWithCache(),
        outcome.distinctEmbedTexts(),
        outcome.childRows(),
        outcome.cacheHits());
  }

  // ==================== R3 · 两条丢弃语义钉住 ====================

  @Test
  @DisplayName("R3a：storage.store 单图失败 → 只跳过该图（切片 N−1），其余照常、文本切片未受影响")
  void r3StorageFailureDropsSingleImageOnly() throws Exception {
    R3StorageOutcome outcome = runStorageFailure(new ArrayList<>());
    assertEquals(3, outcome.images(), "构造 3 图");
    assertEquals(3, outcome.describeCalls(), "三图都被 describe（落盘失败发生在描述之后）");
    assertEquals(3, outcome.storeCalls(), "三图都尝试落盘（= 过闸数口径）");
    assertEquals(2, outcome.slices(), "切片数 = N−1：仅失败图被跳过");
    assertEquals(List.of(1, 3), outcome.survivingPageNos(), "存活的恰是未失败的两图");
    assertEquals(2, outcome.childRows(), "下游子块行随切片数（N−1）");
    assertEquals(2, outcome.embedCalls(), "下游 embed 调用随子块行（N−1）");
    assertEquals(2, outcome.imageStaged(), "视觉向量暂存随子块行（N−1）");
    assertEquals(1, outcome.warnCount(), "落盘失败 WARN 恰一条（PdfImageSliceIngestService:310）");
    assertEquals(2, outcome.mergedTextChunks(), "文本切片未受影响：合并结果仍含两行文本切片");
    assertEquals(2, outcome.mergedImageSlices(), "合并结果含存活的两条图片切片");
    System.out.printf(
        "[chain-reconcile] R3a 单图丢弃: 描述=%d 落盘尝试=%d 切片=%d 存活页=%s 子块=%d embed=%d 暂存=%d WARN=%d%n",
        outcome.describeCalls(),
        outcome.storeCalls(),
        outcome.slices(),
        outcome.survivingPageNos(),
        outcome.childRows(),
        outcome.embedCalls(),
        outcome.imageStaged(),
        outcome.warnCount());
  }

  @Test
  @DisplayName("R3b：buildSlices 抛出未兜住异常 → 整批图片切片全丢，文本切片照常；提取器抛走内层 catch")
  void r3DocumentLevelSwallowDropsWholeImageBatch() throws Exception {
    R3DocOutcome outcome = runDocumentLevel(new ArrayList<>());
    assertTrue(outcome.imageSlicesLost(), "整批图片切片全丢");
    assertEquals(2, outcome.textChunksPreserved(), "文本切片照常入库（StubTextDocumentService 两行）");
    assertEquals(1, outcome.warnCount(), "ImageSliceIngestSupport WARN 恰一条（:132 文档级 catch）");
    assertEquals(0, outcome.describeCalls(), "异常在进入逐图循环前抛出：零描述外呼");
    assertEquals(0, outcome.corruptSlices(), "提取器抛（非 PDF 字节）：生产 buildSlices 返回空表");
    assertEquals(0, outcome.corruptDescribeCalls(), "提取失败在 describe 之前：零描述外呼");
    assertEquals(
        1, outcome.corruptWarnCount(), "内层文档级 WARN 恰一条（PdfImageSliceIngestService:138/140）");
    System.out.printf(
        "[chain-reconcile] R3b 文档级丢弃: 图片切片全丢=%s 文本=%d WARN=%d 描述=%d | 提取器抛: 切片=%d 描述=%d WARN=%d%n",
        outcome.imageSlicesLost(),
        outcome.textChunksPreserved(),
        outcome.warnCount(),
        outcome.describeCalls(),
        outcome.corruptSlices(),
        outcome.corruptDescribeCalls(),
        outcome.corruptWarnCount());
  }

  // ==================== R4 · 分母口径三件套 ====================

  @Test
  @DisplayName("R4：每个图片切片 (pageNo,rowIndex,embedText) 三件套，rowIndex 恒 null")
  void r4DenominatorTriples() throws Exception {
    ChainProvider chain = new ChainProvider(false);
    HappyChain row = runHappyChain(8, "n8", chain);
    assertEquals(8, row.triples().size(), "八图 → 八条三件套");
    for (int i = 0; i < row.triples().size(); i++) {
      Triple triple = row.triples().get(i);
      assertEquals(i + 1, triple.pageNo(), "图片切片页码 = 图所在页（第 " + (i + 1) + " 条）");
      assertTrue(triple.rowIndex() == null, "图片切片 rowIndex 恒 null（R4 口径）");
    }
    assertEquals(
        8,
        new LinkedHashSet<>(row.triples().stream().map(Triple::embedText).toList()).size(),
        "八条 embedText 两两互异（页码区分）");
  }

  // ==================== 结论锚 · 逐字节可复算 ====================

  @Test
  @DisplayName("锚：由本次全链重算逐字节复算（改坏任一数字或 verdict 即红）；常规运行不落盘")
  void anchorIsByteReproducible() throws Exception {
    String json = buildAnchorJson();
    assertFalse(json.contains("\r"), "锚 JSON 必须纯 LF（缩进器显式 \\n，Windows 系统行尾不进锚）");
    byte[] expected = json.getBytes(StandardCharsets.UTF_8);
    if (WRITE_MODE) {
      Files.write(ANCHOR, expected);
      System.out.println("[chain-reconcile] 锚已写入（write 模式）: " + ANCHOR);
      return;
    }
    assertTrue(Files.exists(ANCHOR), "结论锚缺失，先以 -Dimage.slice.chain.anchor.write=true 生成一次");
    assertArrayEquals(expected, Files.readAllBytes(ANCHOR), "锚与本次全链重算逐字节不一致：锚被改动或链路行为漂移");
  }

  // ==================== 链路桩 ====================

  /**
   * 链路桩：vision 按图片字节内容键控返回确定性描述（同一张图跨文档同描述）；embedImage/embed 返回确定性向量；embed 可选按完整 embedText
   * 缓存并记录键序（卡 26 CachedBudgetTrackingProvider 的同型机制，仅存在于测试桩）。
   */
  private static final class ChainProvider {

    final AtomicInteger visionCalls = new AtomicInteger();
    final AtomicInteger embedImageCalls = new AtomicInteger();
    final AtomicInteger embedCalls = new AtomicInteger();
    final AtomicInteger embedCacheHits = new AtomicInteger();

    /** embed 收到的完整 embedText 按调用序（含缓存命中轮次），R2/R4 的键级证据。 */
    final List<String> embedKeysInCallOrder = new ArrayList<>();

    private final Map<String, float[]> embedCache;
    final ModelProvider provider;

    ChainProvider(boolean withCache) {
      this.embedCache = withCache ? new LinkedHashMap<>() : null;
      this.provider = build();
    }

    private ModelProvider build() {
      ModelProvider mock = Mockito.mock(ModelProvider.class);
      Mockito.when(mock.vision(ArgumentMatchers.any(Prompt.class), ArgumentMatchers.any()))
          .thenAnswer(
              invocation -> {
                visionCalls.incrementAndGet();
                return ModelCallResult.ofText(
                    describeOf(pngOf(invocation)), "stub-vision", 12L, 24L, 36L);
              });
      Mockito.when(
              mock.embedImage(ArgumentMatchers.any(byte[].class), ArgumentMatchers.anyString()))
          .thenAnswer(
              invocation -> {
                int call = embedImageCalls.incrementAndGet();
                return ModelCallResult.ofVector(
                    new float[] {1F, call, 0.5F, 0.25F}, "stub-image-embed", 8L, 8L);
              });
      Mockito.when(mock.embed(ArgumentMatchers.any(EmbeddingRequest.class)))
          .thenAnswer(
              invocation -> {
                EmbeddingRequest request = invocation.getArgument(0);
                String key = request.getInstructions().get(0);
                embedKeysInCallOrder.add(key);
                float[] computed = new float[] {0.25F, embedCalls.get() + 1, 0.75F, 1F};
                if (embedCache != null) {
                  float[] cached = embedCache.get(key);
                  if (cached != null) {
                    embedCacheHits.incrementAndGet();
                    return ModelCallResult.ofVector(cached, "stub-embed", 4L, 8L);
                  }
                  embedCache.put(key, computed);
                }
                embedCalls.incrementAndGet();
                return ModelCallResult.ofVector(computed, "stub-embed", 4L, 8L);
              });
      return mock;
    }

    /** 从 vision Prompt 取出图片 PNG 字节（生产链把 ExtractedImage.png 包进 ByteArrayResource media）。 */
    private static byte[] pngOf(org.mockito.invocation.InvocationOnMock invocation) {
      Prompt prompt = invocation.getArgument(0);
      Media media = ((UserMessage) prompt.getInstructions().get(0)).getMedia().get(0);
      return media.getDataAsByteArray();
    }
  }

  /** 描述 = 内容键控前缀 + 固定尾段：同一张图（同字节）跨文档必同描述，是 R2 必然命中的构造基础。 */
  private static String describeOf(byte[] png) {
    return "确定性对账描述[" + Integer.toHexString(Arrays.hashCode(png)) + "]" + DESCRIPTION_TAIL;
  }

  /** 计数存储桩：store 全计数（含失败尝试 = 过闸数口径），可对指定 objectName 注入落盘失败（R3a）。 */
  private static final class CountingStorage implements FileStorageService {

    final AtomicInteger storeCalls = new AtomicInteger();
    final Set<String> failOnObjectNames = new LinkedHashSet<>();

    @Override
    public String store(InputStream content, String filename, String contentType) {
      storeCalls.incrementAndGet();
      if (failOnObjectNames.contains(filename)) {
        throw new IllegalStateException("注入的落盘失败: " + filename);
      }
      return "stub-key-" + filename;
    }

    @Override
    public InputStream open(String storageKey) {
      return InputStream.nullInputStream();
    }

    @Override
    public void delete(String storageKey) {}

    @Override
    public boolean exists(String storageKey) {
      return false;
    }
  }

  /** image 集合计数桩：绑定进 ImageVectorCleanupRegistry，chunkEmbedAndWrite 内部 flush 消费暂存向量时计数。 */
  private static final class RecordingImageStore implements CrmVectorStore {

    final List<VectorRecord> records = new ArrayList<>();

    @Override
    public void upsert(VectorRecord record) {
      records.add(record);
    }

    @Override
    public void upsertAll(List<VectorRecord> batch) {
      records.addAll(batch);
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
      return List.of();
    }

    @Override
    public void deleteByDocumentId(String documentId) {}
  }

  /** 文本切片桩：两行确定性文本切片（R3 验证图片旁路失败不打断文本切片）。 */
  static final class StubTextDocumentService extends DocumentService {

    @Override
    public List<DocumentChunk> process(InputStream content, String filename, String category) {
      return textChunks(category);
    }

    @Override
    public List<DocumentChunk> processOrEmpty(
        InputStream content, String filename, String category) {
      return textChunks(category);
    }
  }

  private static List<DocumentChunk> textChunks(String category) {
    return List.of(
        new DocumentChunk("对账用文本切片甲", 0, 1, null, category, List.of(), null, Map.of()),
        new DocumentChunk("对账用文本切片乙", 1, 1, null, category, List.of(), null, Map.of()));
  }

  // ==================== 测量记录 ====================

  /** 一次文档全链测量的七个计数（顺序 = CHAIN_NAMES）。 */
  private record ChainCounts(String label, int[] values) {

    List<Integer> adjacentDeltas() {
      List<Integer> deltas = new ArrayList<>();
      for (int i = 0; i + 1 < values.length; i++) {
        deltas.add(values[i + 1] - values[i]);
      }
      return deltas;
    }
  }

  /** R4 三件套。 */
  private record Triple(int pageNo, Integer rowIndex, String embedText) {}

  /** 一档（n1/n8）的测量结果。 */
  private record HappyChain(ChainCounts counts, List<Triple> triples) {}

  /** R2 四数与命中证据。 */
  private record R2Outcome(
      int invocationsNoCache,
      int invocationsWithCache,
      int distinctEmbedTexts,
      int childRows,
      int cacheHits,
      boolean hitConstructed,
      boolean collidingEmbedTextsIdentical,
      boolean chainCountsUnchangedByCache,
      String cacheSuppressionVerdict) {}

  /** R3a 单图丢弃语义测量。 */
  private record R3StorageOutcome(
      int images,
      int describeCalls,
      int storeCalls,
      int slices,
      List<Integer> survivingPageNos,
      int childRows,
      int embedCalls,
      int imageStaged,
      int warnCount,
      int mergedTextChunks,
      int mergedImageSlices) {}

  /** R3b 文档级丢弃语义测量（ImageSliceIngestSupport:132 外层 + buildSlices:138 内层）。 */
  private record R3DocOutcome(
      boolean imageSlicesLost,
      int textChunksPreserved,
      int warnCount,
      int describeCalls,
      int corruptSlices,
      int corruptDescribeCalls,
      int corruptWarnCount) {}

  // ==================== 测量流程 ====================

  private static Map<String, Object> configMatrix() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, MAX_IMAGES);
    return matrix;
  }

  /** 只读 ObjectProvider 包装单值（null 照原样返回，与既有 FixtureTest 同法）。 */
  private static <T> ObjectProvider<T> providerOf(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }

      @Override
      public T getIfAvailable() {
        return value;
      }

      @Override
      public T getIfUnique() {
        return value;
      }
    };
  }

  private static PdfImageSliceIngestService newService(
      ChainProvider chain, ObjectProvider<FileStorageService> storageProvider) {
    return new PdfImageSliceIngestService(
        chain.provider,
        providerOf(RagBenchmarkPipelineFactory.dynamicConfig(configMatrix())),
        providerOf((TokenUsageRecorder) null),
        storageProvider);
  }

  /** 确定性合成 PNG（≥64px，逐字节可复现）。 */
  private static byte[] png(int ordinal) throws Exception {
    int width = 96 + (ordinal % 4) * 8;
    BufferedImage image = new BufferedImage(width, 100, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(
          new Color(
              (ordinal * 37 + 11) % 256, (ordinal * 53 + 29) % 256, (ordinal * 71 + 47) % 256));
      graphics.fillRect(0, 0, width, 100);
      graphics.setColor(Color.BLACK);
      graphics.fillRect(8, 8, 16 + ordinal * 3, 12);
      graphics.fillRect(40, 60 - ordinal, 20, 10);
    } finally {
      graphics.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", out)) {
      throw new IllegalStateException("PNG 编码失败");
    }
    return out.toByteArray();
  }

  private static List<byte[]> pngs(int count) throws Exception {
    List<byte[]> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      result.add(png(i));
    }
    return result;
  }

  private static UploadedFileEntity file(String filename) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId("reconcile-chain-doc");
    file.setOriginalFilename(filename);
    file.setFileType("pdf");
    file.setKnowledgeBase(KB_ID);
    return file;
  }

  /** chunkMapper 内存 double：insertBatch 按行序回填自增主键（persistBatch 语义要求），其余安全空实现。 */
  private static DocumentVectorChunkMapper chunkMapperWithIdBackfill() {
    long[] nextId = {7700L};
    return (DocumentVectorChunkMapper)
        Proxy.newProxyInstance(
            DocumentVectorChunkMapper.class.getClassLoader(),
            new Class<?>[] {DocumentVectorChunkMapper.class},
            (proxy, method, args) -> {
              if ("insertBatch".equals(method.getName())) {
                List<?> rows = (List<?>) args[0];
                for (Object row : rows) {
                  ((DocumentVectorChunkEntity) row).setId(nextId[0]++);
                }
                return rows.size();
              }
              Class<?> returnType = method.getReturnType();
              if (!returnType.isPrimitive()) {
                return null;
              }
              return returnType == boolean.class ? Boolean.FALSE : 0;
            });
  }

  /** 反射调包私有 {@code DocumentIngestionSupport#chunkEmbedAndWrite}（生产入库装配尾段本体）。 */
  @SuppressWarnings("unchecked")
  private static List<DocumentVectorChunkEntity> chunkEmbedAndWrite(
      List<DocumentChunk> chunks, String filename, ChainProvider chain) throws Exception {
    Method method =
        Class.forName("com.slz.crm.knowledge.document.DocumentIngestionSupport")
            .getDeclaredMethod(
                "chunkEmbedAndWrite",
                UploadedFileEntity.class,
                List.class,
                EmbeddingService.class,
                CrmVectorStore.class,
                DocumentVectorChunkMapper.class,
                DocumentService.class);
    method.setAccessible(true);
    return (List<DocumentVectorChunkEntity>)
        method.invoke(
            null,
            file(filename),
            chunks,
            new EmbeddingService(chain.provider, new ModelProviderProperties()),
            Mockito.mock(CrmVectorStore.class),
            chunkMapperWithIdBackfill(),
            new DocumentService());
  }

  /** 反射调包私有 {@code ImageSliceIngestSupport#parseWithImageSlices}（生产合并入口本体）。 */
  @SuppressWarnings("unchecked")
  private static List<DocumentChunk> parseWithImageSlices(
      DocumentService documentService,
      PdfImageSliceIngestService imageService,
      byte[] content,
      String filename,
      String category)
      throws Exception {
    Method method =
        Class.forName("com.slz.crm.knowledge.document.ImageSliceIngestSupport")
            .getDeclaredMethod(
                "parseWithImageSlices",
                DocumentService.class,
                PdfImageSliceIngestService.class,
                InputStream.class,
                byte[].class,
                String.class,
                String.class,
                String.class);
    method.setAccessible(true);
    return (List<DocumentChunk>)
        method.invoke(
            null,
            documentService,
            imageService,
            new ByteArrayInputStream(content),
            content,
            filename,
            category,
            KB_ID);
  }

  /**
   * WARN 捕获：动作执行期间对指定 logger 挂 ListAppender，按级别 + 消息前缀计数（完查后摘除）。
   *
   * <p>反射调用 {@code parseWithImageSlices} 的受检异常在动作内包成运行时异常，此处统一还原。
   */
  private static <T> Warned<T> captureWarn(
      String loggerName, String messagePrefix, WarnSupplier<T> action) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      T value;
      try {
        value = action.get();
      } catch (Exception exception) {
        throw new IllegalStateException("WARN 捕获动作失败", exception);
      }
      int warns =
          (int)
              appender.list.stream()
                  .filter(
                      event ->
                          event.getLevel() == Level.WARN
                              && event.getFormattedMessage().startsWith(messagePrefix))
                  .count();
      return new Warned<>(value, warns);
    } finally {
      logger.detachAppender(appender);
    }
  }

  /** WARN 捕获动作（受检异常版本）。 */
  @FunctionalInterface
  private interface WarnSupplier<T> {
    T get() throws Exception;
  }

  /** 值 + 期间的 WARN 计数。 */
  private record Warned<T>(T value, int warnCount) {}

  /**
   * 一档（n 张图，单文档）的完整七链测量：buildSlices（生产）→ chunkEmbedAndWrite（生产，反射）。
   *
   * <p>image 向量暂存数 = 绑定 image store 在本档测量期间新增的记录数（flush 由 chunkEmbedAndWrite 内部消费）。
   */
  private HappyChain runHappyChain(int n, String label, ChainProvider chain) throws Exception {
    RecordingImageStore imageStore = bindImageStore();
    try {
      byte[] pdf = NearDupIngestGateProbeIT.buildPdfs(pngs(n)).get(0);
      CountingStorage storage = new CountingStorage();
      PdfImageSliceIngestService service = newService(chain, providerOf(storage));
      int extracted = RealPdfAssets.extract(pdf, MAX_IMAGES).size();
      List<DocumentChunk> slices = service.buildSlices(pdf, FILENAME, CATEGORY, KB_ID);
      for (DocumentChunk slice : slices) {
        assertTrue(ImageSliceMetadata.isImageSlice(slice.extraMetadata()), "图片切片必须带资产元数据");
      }
      List<DocumentVectorChunkEntity> children = chunkEmbedAndWrite(slices, FILENAME, chain);
      for (DocumentVectorChunkEntity child : children) {
        assertTrue(child.getRowIndex() == null, "图片切片行 rowIndex 恒 null");
      }
      ChainCounts counts =
          new ChainCounts(
              label,
              new int[] {
                extracted,
                chain.visionCalls.get(),
                storage.storeCalls.get(),
                slices.size(),
                children.size(),
                chain.embedCalls.get(),
                imageStore.records.size()
              });
      return new HappyChain(counts, triplesOf(children, FILENAME));
    } finally {
      ImageVectorCleanupRegistry.unbind();
    }
  }

  /** R4 三件套：生产子块行 → (pageNo, rowIndex, embedText)，embedText 用生产 ChunkHeaderText.wrap。 */
  private static List<Triple> triplesOf(List<DocumentVectorChunkEntity> children, String filename) {
    List<Triple> triples = new ArrayList<>();
    for (DocumentVectorChunkEntity child : children) {
      triples.add(
          new Triple(
              child.getPageNo(),
              child.getRowIndex(),
              ChunkHeaderText.wrap(
                  filename,
                  child.getCategory(),
                  child.getPageNo(),
                  child.getRowIndex(),
                  child.getChunkText())));
    }
    return triples;
  }

  private static RecordingImageStore bindImageStore() {
    RecordingImageStore store = new RecordingImageStore();
    ImageVectorCleanupRegistry.bind(store);
    return store;
  }

  /**
   * R2 语料：四份合成文档，两轮（无缓存 / 有缓存）各跑一遍全链。
   *
   * <p>doc1 = 文件名甲/类目甲，页 1-2 = 图 A、图 B；doc2 = <b>同文件名同页码</b>，页 1-2 = 图 A、图 C（A 的 embedText 与 doc1 图
   * A 逐字相同 → 必然命中）；doc3 = 异文件名（文件名因子）；doc4 = 异类目（类目因子）。子块行 = 2+2+1+1 = 6。
   */
  private R2Outcome runCacheCorpus(List<ChainProvider> collector) throws Exception {
    byte[] pngA = png(0);
    byte[] doc1 = NearDupIngestGateProbeIT.buildPdfs(List.of(pngA, png(1))).get(0);
    byte[] doc2 = NearDupIngestGateProbeIT.buildPdfs(List.of(pngA, png(2))).get(0);
    byte[] doc3 = NearDupIngestGateProbeIT.buildPdfs(List.of(pngA)).get(0);
    byte[] doc4 = NearDupIngestGateProbeIT.buildPdfs(List.of(pngA)).get(0);
    List<byte[]> pdfs = List.of(doc1, doc2, doc3, doc4);
    List<String> filenames = List.of(FILENAME, FILENAME, OTHER_FILENAME, FILENAME);
    List<String> categories = List.of(CATEGORY, CATEGORY, CATEGORY, OTHER_CATEGORY);

    ChainProvider plain = new ChainProvider(false);
    collector.add(plain);
    int plainChildren = runCorpus(plain, pdfs, filenames, categories);
    int plainDistinct = new LinkedHashSet<>(plain.embedKeysInCallOrder).size();

    ChainProvider cached = new ChainProvider(true);
    collector.add(cached);
    int cachedChildren = runCorpus(cached, pdfs, filenames, categories);
    int cachedDistinct = new LinkedHashSet<>(cached.embedKeysInCallOrder).size();

    boolean hitConstructed = cached.embedCacheHits.get() == 1;
    boolean collidingIdentical =
        cached.embedKeysInCallOrder.size() >= 3
            && cached.embedKeysInCallOrder.get(0).equals(cached.embedKeysInCallOrder.get(2));
    int invocationsNoCache = plain.embedCalls.get();
    int invocationsWithCache = cached.embedCalls.get();
    boolean chainUnchanged = plainChildren == cachedChildren && plainDistinct == cachedDistinct;
    boolean cacheSuppressed =
        invocationsWithCache < invocationsNoCache
            && invocationsNoCache - invocationsWithCache == cachedChildren - cachedDistinct;
    String verdict =
        invocationsNoCache == cachedChildren && cacheSuppressed
            ? "COUNTER-SUPPRESSED"
            : (invocationsNoCache == cachedChildren ? "INCONCLUSIVE" : "CHAIN-BROKEN");
    return new R2Outcome(
        invocationsNoCache,
        invocationsWithCache,
        cachedDistinct,
        cachedChildren,
        cached.embedCacheHits.get(),
        hitConstructed,
        collidingIdentical,
        chainUnchanged,
        verdict);
  }

  /** 四文档全链跑一遍，返回子块行总数（每档存储/服务在方法内自建，计数器按累计值读）。 */
  private int runCorpus(
      ChainProvider chain, List<byte[]> pdfs, List<String> filenames, List<String> categories)
      throws Exception {
    RecordingImageStore imageStore = bindImageStore();
    try {
      CountingStorage storage = new CountingStorage();
      PdfImageSliceIngestService service = newService(chain, providerOf(storage));
      int children = 0;
      for (int i = 0; i < pdfs.size(); i++) {
        List<DocumentChunk> slices =
            service.buildSlices(pdfs.get(i), filenames.get(i), categories.get(i), KB_ID);
        children += chunkEmbedAndWrite(slices, filenames.get(i), chain).size();
        for (DocumentChunk slice : slices) {
          assertTrue(slice.rowIndex() == null, "图片切片 rowIndex 恒 null（R2 语料同样适用 R4 口径）");
        }
      }
      assertEquals(6, children, "四文档子块行总数必须 = 2+2+1+1（缓存不得改变链路计数）");
      return children;
    } finally {
      ImageVectorCleanupRegistry.unbind();
    }
  }

  /** R3a：注入第 2 页图的落盘失败（objectName = image-2-0.png），测单图丢弃语义与下游一致性。 */
  private R3StorageOutcome runStorageFailure(List<ChainProvider> collector) throws Exception {
    RecordingImageStore imageStore = bindImageStore();
    try {
      byte[] pdf = NearDupIngestGateProbeIT.buildPdfs(pngs(3)).get(0);
      ChainProvider chain = new ChainProvider(false);
      collector.add(chain);
      CountingStorage storage = new CountingStorage();
      storage.failOnObjectNames.add("image-2-0.png");
      PdfImageSliceIngestService service = newService(chain, providerOf(storage));
      Warned<List<DocumentChunk>> captured =
          captureWarn(
              PdfImageSliceIngestService.class.getName(),
              IMAGE_STORE_FAILURE_PREFIX,
              () -> service.buildSlices(pdf, FILENAME, CATEGORY, KB_ID));
      List<DocumentChunk> slices = captured.value();
      List<DocumentVectorChunkEntity> children = chunkEmbedAndWrite(slices, FILENAME, chain);
      List<Integer> survivingPageNos = slices.stream().map(DocumentChunk::pageNo).toList();

      // 文本切片未受影响：生产合并入口跑同一语料（全新桩），结果 = 2 文本 + 2 存活图片切片
      ChainProvider mergeChain = new ChainProvider(false);
      collector.add(mergeChain);
      CountingStorage mergeStorage = new CountingStorage();
      mergeStorage.failOnObjectNames.add("image-2-0.png");
      PdfImageSliceIngestService mergeService = newService(mergeChain, providerOf(mergeStorage));
      List<DocumentChunk> merged =
          parseWithImageSlices(
              new StubTextDocumentService(), mergeService, pdf, FILENAME, CATEGORY);
      int mergedText =
          (int)
              merged.stream()
                  .filter(chunk -> !ImageSliceMetadata.isImageSlice(chunk.extraMetadata()))
                  .count();
      int mergedImages =
          (int)
              merged.stream()
                  .filter(chunk -> ImageSliceMetadata.isImageSlice(chunk.extraMetadata()))
                  .count();
      return new R3StorageOutcome(
          3,
          chain.visionCalls.get(),
          storage.storeCalls.get(),
          slices.size(),
          survivingPageNos,
          children.size(),
          chain.embedCalls.get(),
          imageStore.records.size(),
          captured.warnCount(),
          mergedText,
          mergedImages);
    } finally {
      ImageVectorCleanupRegistry.unbind();
    }
  }

  /**
   * R3b：两条文档级丢弃路径。
   *
   * <p>(a) {@code ImageSliceIngestSupport:132} 外层文档级 catch——给真实 {@code PdfImageSliceIngestService}
   * 注入 {@code getIfAvailable()} 直接抛出的存储 provider（该调用位于 buildSlices try 块之外，任何 per-image catch
   * 都兜不住），经生产合并入口 {@code parseWithImageSlices} 跑：整批图片切片丢弃、文本切片照常、WARN 恰一条。
   *
   * <p>(b) 提取器抛（buildSlices:138 内层文档级 catch）——非 PDF 字节走真实 buildSlices：返回空表、零描述外呼、WARN 恰一条。
   */
  private R3DocOutcome runDocumentLevel(List<ChainProvider> collector) throws Exception {
    byte[] pdf = NearDupIngestGateProbeIT.buildPdfs(pngs(1)).get(0);
    // (a) 外层：buildSlices 直接抛出
    ChainProvider chain = new ChainProvider(false);
    collector.add(chain);
    ObjectProvider<FileStorageService> throwingStorage =
        new ObjectProvider<>() {
          @Override
          public FileStorageService getObject() {
            throw new IllegalStateException(STORAGE_ASSEMBLY_FAILURE);
          }

          @Override
          public FileStorageService getIfAvailable() {
            throw new IllegalStateException(STORAGE_ASSEMBLY_FAILURE);
          }

          @Override
          public FileStorageService getIfUnique() {
            throw new IllegalStateException(STORAGE_ASSEMBLY_FAILURE);
          }
        };
    PdfImageSliceIngestService throwingService = newService(chain, throwingStorage);
    Warned<List<DocumentChunk>> captured =
        captureWarn(
            IMAGE_SLICE_SUPPORT_LOGGER,
            IMAGE_SLICE_SWALLOW_PREFIX,
            () ->
                parseWithImageSlices(
                    new StubTextDocumentService(), throwingService, pdf, FILENAME, CATEGORY));
    List<DocumentChunk> merged = captured.value();
    int textCount =
        (int)
            merged.stream()
                .filter(chunk -> !ImageSliceMetadata.isImageSlice(chunk.extraMetadata()))
                .count();
    int imageCount =
        (int)
            merged.stream()
                .filter(chunk -> ImageSliceMetadata.isImageSlice(chunk.extraMetadata()))
                .count();

    // (b) 内层：提取器抛（真实 buildSlices 吞掉，返回空表）
    ChainProvider corruptChain = new ChainProvider(false);
    collector.add(corruptChain);
    CountingStorage corruptStorage = new CountingStorage();
    PdfImageSliceIngestService corruptService =
        newService(corruptChain, providerOf(corruptStorage));
    byte[] corrupt = "这不是一份PDF".getBytes(StandardCharsets.UTF_8);
    Warned<List<DocumentChunk>> corruptCaptured =
        captureWarn(
            PdfImageSliceIngestService.class.getName(),
            IMAGE_EXTRACT_FAILURE_PREFIX,
            () -> corruptService.buildSlices(corrupt, "reconcile-broken.pdf", CATEGORY, KB_ID));
    return new R3DocOutcome(
        imageCount == 0 && textCount == 2,
        textCount,
        captured.warnCount(),
        chain.visionCalls.get(),
        corruptCaptured.value().size(),
        corruptChain.visionCalls.get(),
        corruptCaptured.warnCount());
  }

  // ==================== 锚构建 ====================

  /** 全链重算 → 结论锚 JSON（LF 缩进，逐字节可复算；verdict 由测量值机械推导）。 */
  private String buildAnchorJson() throws Exception {
    List<ChainProvider> collector = new ArrayList<>();
    ChainProvider n1Chain = new ChainProvider(false);
    collector.add(n1Chain);
    HappyChain n1 = runHappyChain(1, "n1", n1Chain);
    ChainProvider n8Chain = new ChainProvider(false);
    collector.add(n8Chain);
    HappyChain n8 = runHappyChain(8, "n8", n8Chain);
    R2Outcome r2 = runCacheCorpus(collector);
    R3StorageOutcome r3a = runStorageFailure(collector);
    R3DocOutcome r3b = runDocumentLevel(collector);

    boolean chainIntact =
        n1.counts().adjacentDeltas().stream().allMatch(delta -> delta == 0)
            && n8.counts().adjacentDeltas().stream().allMatch(delta -> delta == 0)
            && r2.invocationsNoCache() == r2.childRows();
    boolean cacheSuppressed =
        r2.invocationsWithCache() < r2.invocationsNoCache()
            && r2.invocationsNoCache() - r2.invocationsWithCache()
                == r2.childRows() - r2.distinctEmbedTexts()
            && r2.hitConstructed()
            && r2.collidingEmbedTextsIdentical();
    // 前提：缓存抑制要能解释卡 26 的缺口，S1 语料必须真能产生 embedText 碰撞（R4 的碰撞条件含「文件名相同」）
    boolean suppressionAppliesToS1 = S1_PDF_FILENAMES.stream().distinct().count() == 1;
    String verdict;
    String brokenLink = null;
    String missingEvidence = null;
    if (!chainIntact) {
      verdict = "CHAIN-BROKEN";
      brokenLink = firstBrokenLink(n1, n8);
    } else if (cacheSuppressed && suppressionAppliesToS1) {
      verdict = "COUNTER-SUPPRESSED";
    } else if (cacheSuppressed) {
      verdict = "INCONCLUSIVE";
      missingEvidence = S1_INAPPLICABLE_MISSING_EVIDENCE;
    } else {
      verdict = "INCONCLUSIVE";
      missingEvidence = "链路完好但缓存抑制关系不成立（差值 != childRows − distinctEmbedTexts）";
    }

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();
    root.put("anchor", "image-slice-ingest-chain-v1");
    root.put("card", "image-slice-ingest-chain-reconciliation");
    root.put("verdict", verdict);
    root.put("brokenLink", brokenLink);
    root.put("missingEvidence", missingEvidence);

    ObjectNode zero = root.putObject("zeroExternalCall");
    zero.put("realProviderConstructed", false);
    zero.put(
        "structuralGuarantee",
        "全程仅 Mockito 桩 ModelProvider；未构造真实 provider、未读取 DASHSCOPE_API_KEY 或 .env；外呼次数=0 由桩计数器复算");
    ObjectNode counters = zero.putObject("stubCallCounters");
    int vision = 0;
    int embedImage = 0;
    int embed = 0;
    int hits = 0;
    for (ChainProvider chain : collector) {
      vision += chain.visionCalls.get();
      embedImage += chain.embedImageCalls.get();
      embed += chain.embedCalls.get();
      hits += chain.embedCacheHits.get();
    }
    counters.put("vision", vision);
    counters.put("embedImage", embedImage);
    counters.put("embed", embed);
    counters.put("embedCacheHits", hits);

    ObjectNode r1 = root.putObject("r1");
    ArrayNode chainOrder = r1.putArray("chainOrder");
    for (String name : CHAIN_NAMES) {
      chainOrder.add(name);
    }
    ArrayNode rows = r1.putArray("rows");
    for (HappyChain row : List.of(n1, n8)) {
      ObjectNode node = rows.addObject();
      int[] values = row.counts().values();
      node.put("label", row.counts().label());
      node.put("extracted", values[0]);
      node.put("describeCalls", values[1]);
      node.put("gatePassed", values[2]);
      node.put("slices", values[3]);
      node.put("childRows", values[4]);
      node.put("embedCalls", values[5]);
      node.put("imageStaged", values[6]);
      ArrayNode deltas = node.putArray("deltas");
      for (Integer delta : row.counts().adjacentDeltas()) {
        deltas.add(delta);
      }
    }
    ArrayNode skipPoints = r1.putArray("skipPoints");
    skipPoint(
        skipPoints, "gatePassed<describeCalls", "PdfImageSliceIngestService:286", "未过质量闸门，单图跳过");
    skipPoint(
        skipPoints,
        "gatePassed<describeCalls",
        "PdfImageSliceIngestService:290",
        "描述调用失败 catch，单图跳过");
    skipPoint(
        skipPoints, "slices<gatePassed", "PdfImageSliceIngestService:310", "对象落盘失败 catch，单图跳过");
    skipPoint(
        skipPoints,
        "slices=0（整批）",
        "PdfImageSliceIngestService:138",
        "buildSlices 内层文档级 catch：提取/装配异常吞掉，文本不受影响");
    skipPoint(
        skipPoints,
        "slices=0（整批）",
        "ImageSliceIngestSupport:132",
        "文档级 catch(Exception)：buildSlices 抛出时整批图片切片丢弃，文本照常");

    ObjectNode r2Node = root.putObject("r2");
    r2Node.put("invocationsNoCache", r2.invocationsNoCache());
    r2Node.put("invocationsWithCache", r2.invocationsWithCache());
    r2Node.put("distinctEmbedTexts", r2.distinctEmbedTexts());
    r2Node.put("childRows", r2.childRows());
    r2Node.put(
        "corpus",
        "doc1=同文件名同页码[图A,图B] / doc2=同文件名同页码[图A,图C]（A 的 embedText 逐字相同） / doc3=异文件名 / doc4=异类目");
    r2Node.put("hitConstructed", r2.hitConstructed());
    r2Node.put("collidingEmbedTextsIdentical", r2.collidingEmbedTextsIdentical());
    r2Node.put("chainCountsUnchangedByCache", r2.chainCountsUnchangedByCache());
    r2Node.put("cacheSuppressionVerdict", r2.cacheSuppressionVerdict());
    // 适用性前提（fix2）：机制可复现 ≠ 能解释卡 26 的缺口——归因还需 S1 语料真能碰撞
    r2Node.put("collisionAppliesToS1", S1_PDF_FILENAMES.stream().distinct().count() == 1);
    r2Node.put(
        "s1ApplicabilityBasis",
        "S1 三份合成 PDF 文件名互异（near-dup-ingest-cooc-p1/p2/p3.pdf，d7abc37 版 NearDupIngestCooccurrenceIT:437）"
            + "且同文档内每页一图 ⇒ 按 r4 碰撞充分条件（文件名∧类目∧页码∧描述四输入全同），S1 不可能出现 embedText 碰撞");

    ObjectNode r3 = root.putObject("r3");
    ObjectNode perImage = r3.putObject("storageFailurePerImage");
    perImage.put("images", r3a.images());
    perImage.put("describeCalls", r3a.describeCalls());
    perImage.put("storeCalls", r3a.storeCalls());
    perImage.put("slices", r3a.slices());
    ArrayNode surviving = perImage.putArray("survivingPageNos");
    for (Integer pageNo : r3a.survivingPageNos()) {
      surviving.add(pageNo);
    }
    perImage.put("childRows", r3a.childRows());
    perImage.put("embedCalls", r3a.embedCalls());
    perImage.put("imageStaged", r3a.imageStaged());
    perImage.put("warnCount", r3a.warnCount());
    perImage.put("textChunksPreserved", r3a.mergedTextChunks());
    ObjectNode docLevel = r3.putObject("documentLevelSwallow");
    docLevel.put("imageSlicesLost", r3b.imageSlicesLost());
    docLevel.put("textChunksPreserved", r3b.textChunksPreserved());
    docLevel.put("warnCount", r3b.warnCount());
    docLevel.put("describeCalls", r3b.describeCalls());
    ObjectNode inner = r3.putObject("extractorThrowInnerLayer");
    inner.put("slices", r3b.corruptSlices());
    inner.put("describeCalls", r3b.corruptDescribeCalls());
    inner.put("warnCount", r3b.corruptWarnCount());

    ObjectNode r4 = root.putObject("r4");
    int rowIndexNullCount =
        n1.triples().size() + n8.triples().size() + r2.childRows() + r3a.slices();
    r4.put("allRowIndexNull", true);
    r4.put("rowIndexNullCount", rowIndexNullCount);
    r4.put("tripleCount", n8.triples().size());
    ArrayNode triples = r4.putArray("triples");
    for (Triple triple : n8.triples()) {
      ObjectNode node = triples.addObject();
      node.put("pageNo", triple.pageNo());
      node.putNull("rowIndex");
      node.put("embedText", triple.embedText());
    }
    r4.put(
        "sufficientCollisionCondition",
        "两个不同图片切片的 embedText 相同 ⇔ ChunkHeaderText.wrap 的四个区分输入全同：原始文件名相同 ∧ 类目相同 ∧ 图片所在页码相同 ∧ 描述文本逐字相同"
            + "（rowIndex 图片切片恒 null，不参与区分）。数据实证：四输入全同 ⇒ 相同（doc1 图A vs doc2 图A 跨文档命中）；描述不同 ⇒ 不同（doc1 图B vs doc2 图C 同页同名）；"
            + "文件名不同 ⇒ 不同（doc3 图A vs doc1 图A 同页同描述）；类目不同 ⇒ 不同（doc4 图A vs doc1 图A 同页同描述）；页码不同 ⇒ 不同（n1/n8 八图同名同类目跨八页两两互异）");

    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    return mapper.writer(printer).writeValueAsString(root) + "\n";
  }

  private static void skipPoint(ArrayNode array, String chain, String line, String semantics) {
    ObjectNode node = array.addObject();
    node.put("chain", chain);
    node.put("line", line);
    node.put("semantics", semantics);
  }

  /** 第一个差值 &lt; 0 的链位（CHAIN-BROKEN 时的 brokenLink）。 */
  private static String firstBrokenLink(HappyChain n1, HappyChain n8) {
    for (HappyChain row : List.of(n1, n8)) {
      List<Integer> deltas = row.counts().adjacentDeltas();
      for (int i = 0; i < deltas.size(); i++) {
        if (deltas.get(i) < 0) {
          return CHAIN_NAMES[i] + " → " + CHAIN_NAMES[i + 1];
        }
      }
    }
    return "unknown";
  }
}
