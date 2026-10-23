package com.slz.crm.knowledge.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.platform.config.CurrentUserResolver;
import com.slz.crm.platform.config.DynamicConfigCache;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.config.DynamicConfigProperties;
import com.slz.crm.platform.config.audit.DynamicConfigAuditRecorder;
import com.slz.crm.platform.config.entity.DynamicConfigHistoryEntity;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigHistoryMapper;
import com.slz.crm.platform.config.mapper.DynamicConfigItemMapper;
import com.slz.crm.platform.config.service.DynamicConfigAdminService;
import com.slz.crm.platform.config.service.DynamicConfigServiceImpl;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 图片四开关「门控关系 + 运行期热生效」零真外呼实证（retrieval-and-contract-hardening G2 任务 2.2/2.3）。
 *
 * <p><b>证的是哪三句话</b>（{@code docs/multimodal-gray-rollout.md} §2 / §3 第 5 步 / §6 第 1 条 / §11 第 1 条）：
 * ①「只开 A 而 B 仍 false ⇒ 链路完全不动」②「白名单是唯一灰度粒度」③「运行期热生效、无需重启；改回 false 即停」。
 *
 * <p><b>七条断言写在一个测试方法内</b>：A1 / A2 / A2b / A3 / F1 / F2 / N1 顺序执行，全程只用一个 {@link
 * PdfImageSliceIngestService} 实例、一个 {@link DynamicConfigService} 实例、一个 {@link DynamicConfigCache}
 * 实例——三个实例在首尾各做一次 {@code assertSame}， 这就是"未重启"的证据（无 Spring 上下文可重建，也没有任何一处重新 new）。
 *
 * <p><b>配置写入走生产服务本体</b>：{@code DynamicConfigAdminService#updateValue}（含越权闸 → 类型校验 → 版本乐见锁 → 写历史 →
 * 写后失效）， 越权闸的操作人由 {@link UserContextHolder} 注入超管。测试<b>不直接改任何 map</b>：写的是 {@link
 * DynamicConfigItemMapper} 的 <b>mock 持久化</b>（内存行表），读的是真实 {@code DynamicConfigCache →
 * DynamicConfigServiceImpl} 链。
 *
 * <p><b>零真外呼</b>：{@link ModelProvider} 是计数替身（vision 返回定长描述文本、embedImage 返回定长向量），本类不出现真实模型客户端、
 * 不读环境变量、不读 {@code .env}；{@code embedImage} 的调用次数就是断言对象。
 *
 * <p><b>为什么不依赖 Docker/H2</b>：本 IT 的关注点是"同一实例上翻键 → 摄取侧行为变没变"，{@code buildSlices} 已被构造为真实生产路径 （真
 * {@code PdfImageExtractor} + 真存储接口 + 真配置链），配置持久化用 mock 行表即可承载；两张配置表的 H2 兼容性已由 {@code
 * DynamicConfigSchemaH2Test} 单独覆盖，无需在此重复。
 */
class ImageVectorSwitchHotFlipIT {

  /** 测试知识库 id（白名单命中/不命中都靠它）。 */
  private static final String KB_ID = "7";

  /** fixture PDF 里嵌入的图数 = A3 期望的 {@code embedImage} 次数。 */
  private static final int IMAGE_COUNT = 3;

  /** 描述文本（必须过 {@code VisionIngestSupport} 质量闸门的最短长度）。 */
  private static final String DESCRIPTION =
      "服务台架构图：接入层 -> 调度层 -> 数据层，SLA 台账按分层登记工单、响应时长与解决时长，" + "右上角标注值班班组与升级路径，底部列出三档时限。";

  /** 视觉向量（定长，非零长度才会计入暂存）。 */
  private static final float[] VISUAL_VECTOR = {0.9F, 0.2F, 0.1F, 0.4F};

  private final AtomicInteger embedImageCalls = new AtomicInteger();

  private final AtomicInteger visionCalls = new AtomicInteger();

  /** mock 持久化：配置键 → 行（服务写后原位 mutate，故这里不需要回写逻辑）。 */
  private final Map<String, DynamicConfigItemEntity> rows = new LinkedHashMap<>();

  private PdfImageSliceIngestService sliceIngest;

  private DynamicConfigService configService;

  private DynamicConfigAdminService adminService;

  private DynamicConfigCache cache;

  private byte[] pdf;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void wireProductionClasses() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), DynamicConfigItemEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        DynamicConfigHistoryEntity.class);
    DynamicConfigItemMapper itemMapper = mock(DynamicConfigItemMapper.class);
    DynamicConfigHistoryMapper historyMapper = mock(DynamicConfigHistoryMapper.class);
    when(itemMapper.selectOne(any()))
        .thenAnswer(
            invocation -> {
              LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
              // MyBatis-Plus 的 paramNameValuePairs 在渲染 SQL 段时才填充，故必须先取 sqlSegment 再读参数
              String sql = wrapper.getSqlSegment();
              DynamicConfigItemEntity row = rows.get(firstStringParam(wrapper));
              return row == null
                      || (sql.contains("is_deleted") && Boolean.TRUE.equals(row.getIsDeleted()))
                  ? null
                  : row;
            });
    when(itemMapper.selectList(any()))
        .thenAnswer(
            invocation ->
                rows.values().stream()
                    .filter(row -> !Boolean.TRUE.equals(row.getIsDeleted()))
                    .toList());
    when(itemMapper.insert(any(DynamicConfigItemEntity.class)))
        .thenAnswer(
            invocation -> {
              DynamicConfigItemEntity row = invocation.getArgument(0);
              if (row.getId() == null) {
                row.setId((long) rows.size() + 1L);
              }
              rows.put(row.getConfigKey(), row);
              return 1;
            });
    // 行实例由服务原位 mutate（见 DynamicConfigAdminService#updateItem → cache.invalidate），故这里只需回 1 表示命中
    when(itemMapper.update(any(), any())).thenReturn(1);
    when(historyMapper.insert(any(DynamicConfigHistoryEntity.class))).thenReturn(1);

    DynamicConfigKeyRegistry registry = new DynamicConfigKeyRegistry(new ObjectMapper());
    DynamicConfigProperties properties = new DynamicConfigProperties();
    properties.setRefreshIntervalMs(60_000L);
    cache = new DynamicConfigCache(itemMapper, registry, properties);
    configService = new DynamicConfigServiceImpl(registry, cache);
    ObjectProvider<DynamicConfigAuditRecorder> auditProvider = mock(ObjectProvider.class);
    when(auditProvider.getIfAvailable(any())).thenReturn(null);
    adminService =
        new DynamicConfigAdminService(
            itemMapper, historyMapper, registry, cache, new CurrentUserResolver(), auditProvider);

    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
        .thenAnswer(
            invocation -> {
              visionCalls.incrementAndGet();
              return ModelCallResult.ofText(DESCRIPTION, "qwen-vl-plus", 12L, 34L, 46L);
            });
    when(modelProvider.embedImage(any(byte[].class), any()))
        .thenAnswer(
            invocation -> {
              embedImageCalls.incrementAndGet();
              return ModelCallResult.ofVector(VISUAL_VECTOR, "multimodal-embedding-v1", 7L, 11L);
            });
    TokenUsageRecorder recorder = usage -> {};
    InMemoryFileStorageService storage = new InMemoryFileStorageService();
    sliceIngest =
        new PdfImageSliceIngestService(
            modelProvider, provider(configService), provider(recorder), provider(storage));
    pdf = pdfWithImages(IMAGE_COUNT);
  }

  @Test
  @DisplayName("A1/A2/A2b/A3/F1/F2/N1：同一实例上翻四键，摄取侧行为逐轮可观测（零真外呼、未重启）")
  void switchGateAndHotFlipAssertions() {
    PdfImageSliceIngestService serviceInstance = sliceIngest;
    DynamicConfigService configInstance = configService;
    DynamicConfigCache cacheInstance = cache;

    // 前置：三键都还没写覆盖 ⇒ 生产默认关、零外呼零切片
    assertFalse(sliceIngest.enabled(KB_ID), "总开关默认必须为 false");
    assertFalse(sliceIngest.imageVectorEnabled(), "视觉向量开关默认必须为 false");
    assertEquals(0, runRound().embedImageCalls(), "默认关时不得有任何视觉 embedding");

    // A1：B=false + 白名单空 + A=true ⇒ 链路完全不动
    write(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, "true");
    Round a1 = runRound();
    assertEquals(0, a1.embedImageCalls(), "A1：总开关 false 时 embedImage 必须为 0");
    assertEquals(0, a1.visionCalls(), "A1：总开关 false 时连描述外呼都不该有");
    assertEquals(0, a1.slices(), "A1：总开关 false 时不得产出任何图片切片");
    assertEquals(0, a1.stagedPoints(), "A1：image 集合必须 0 点");

    // A2：B=true + 白名单不命中 + A=true ⇒ 白名单是唯一灰度粒度
    write(PdfImageSliceIngestService.ENABLED_KEY, "true");
    write(PdfImageSliceIngestService.KB_ALLOWLIST_KEY, "kb-not-in-allowlist");
    Round a2 = runRound();
    assertEquals(0, a2.embedImageCalls(), "A2：白名单不命中时 embedImage 必须为 0");
    assertEquals(0, a2.stagedPoints(), "A2：白名单不命中时 image 集合必须 0 点");

    // A2b：B=true + 命中 + A=false ⇒ 两个开关各管一段
    write(PdfImageSliceIngestService.KB_ALLOWLIST_KEY, KB_ID);
    write(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, "false");
    Round a2b = runRound();
    assertEquals(IMAGE_COUNT, a2b.slices(), "A2b：方案 B 描述切片必须 > 0（本 fixture 恰为图数）");
    assertTrue(a2b.visionCalls() > 0, "A2b：描述路照常外呼");
    assertEquals(0, a2b.embedImageCalls(), "A2b：视觉开关 false 时 embedImage 必须为 0");
    assertEquals(0, a2b.stagedPoints(), "A2b：视觉开关 false 时 image 集合必须 0 点");

    // A3：B=true + 命中 + A=true ⇒ 两路并行，视觉向量逐图落暂存
    write(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, "true");
    Round a3 = runRound();
    assertEquals(IMAGE_COUNT, a3.slices(), "A3：描述切片必须 == 图数");
    assertEquals(IMAGE_COUNT, a3.embedImageCalls(), "A3：embedImage 必须 == 图数 N（不是 >0）");
    assertEquals(IMAGE_COUNT, a3.stagedPoints(), "A3：image 集合点数必须 == 图数 N");

    // F1：写回 false → 跑（0）→ 运行期写 true → 再跑（>0），全程不重启
    write(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, "false");
    assertEquals(0, runRound().embedImageCalls(), "F1 第一轮：改回 false 即停");
    write(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, "true");
    Round f1 = runRound();
    assertTrue(f1.embedImageCalls() > 0, "F1 第二轮：运行期写 true 后必须重新外呼");
    assertEquals(IMAGE_COUNT, f1.embedImageCalls(), "F1 第二轮：恢复后仍逐图一次");

    // F2：紧接写回 false → 再跑 ⇒ 回到 0
    write(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, "false");
    Round f2 = runRound();
    assertEquals(0, f2.embedImageCalls(), "F2：写回 false 后必须回到 0");
    assertEquals(0, f2.stagedPoints(), "F2：写回 false 后 image 集合必须回到 0 点");

    // N1：反向自检，防全 0 假绿
    assertTrue(a3.embedImageCalls() > a1.embedImageCalls(), "N1：A3 计数必须严格大于 A1 计数");

    // 未重启证据：三个关键实例首尾同一（无上下文可重建，也没有任何一处重新装配）
    assertSame(serviceInstance, sliceIngest, "PdfImageSliceIngestService 实例被替换 ⇒ 等于重启");
    assertSame(configInstance, configService, "DynamicConfigService 实例被替换 ⇒ 等于重启");
    assertSame(cacheInstance, cache, "DynamicConfigCache 实例被替换 ⇒ 等于重启");
    System.out.println(
        "[G2] 未重启证据：service@"
            + System.identityHashCode(serviceInstance)
            + " config@"
            + System.identityHashCode(configInstance)
            + " cache@"
            + System.identityHashCode(cacheInstance)
            + "（7 轮共用，首尾 assertSame 通过）");
  }

  /** 一轮摄取：清测试侧暂存与计数 → 调生产 {@code buildSlices} → 读回四个观测量。 */
  private Round runRound() {
    ImageVisualVectorSupport.end();
    embedImageCalls.set(0);
    visionCalls.set(0);
    List<DocumentChunk> slices = sliceIngest.buildSlices(pdf, "hot-flip-fixture.pdf", "crm", KB_ID);
    int staged = ImageVisualVectorSupport.staged().size();
    Round round = new Round(embedImageCalls.get(), visionCalls.get(), slices.size(), staged);
    ImageVisualVectorSupport.end();
    return round;
  }

  /** 走生产管理端服务写配置（超管身份经 {@link UserContextHolder} 注入）。 */
  private void write(String key, String value) {
    UserContextHolder.runWith(
        superAdmin(), () -> adminService.updateValue(key, value, "hot-flip-it"));
  }

  private static UserContext superAdmin() {
    return new UserContext(1L, 1L, 1L, DataScopeLevel.ALL, "超级管理员");
  }

  /** 一轮的四个观测量。 */
  private record Round(int embedImageCalls, int visionCalls, int slices, int stagedPoints) {}

  private static String firstStringParam(LambdaQueryWrapper<?> wrapper) {
    String key = null;
    for (Object value : wrapper.getParamNameValuePairs().values()) {
      if (value instanceof String text) {
        key = text;
        break;
      }
    }
    return key;
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  private static byte[] pdfWithImages(int count) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (PDDocument document = new PDDocument()) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          for (int index = 0; index < count; index++) {
            PDImageXObject xobject = LosslessFactory.createFromImage(document, diagram(index));
            content.drawImage(xobject, 40, 120 + index * 260, 300, 200);
          }
        }
        document.save(out);
      }
      return out.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("fixture PDF 生成失败", exception);
    }
  }

  private static BufferedImage diagram(int seed) {
    BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 300, 200);
      graphics.setColor(new Color(0x2C, 0x6D, 0xB4));
      graphics.fillRoundRect(10 + seed * 5, 20, 120, 60, 16, 16);
      graphics.setColor(Color.BLACK);
      graphics.drawLine(20, 140, 280, 140 + seed);
    } finally {
      graphics.dispose();
    }
    return image;
  }
}
