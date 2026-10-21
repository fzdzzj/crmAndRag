package com.slz.crm.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.retrieval.SourceReferenceFactory;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * multimodal-shared-space 任务 5.2：真视觉向量共享空间 IT（Testcontainers MySQL 真库 + 内存向量空间）。
 *
 * <p>验的是「独立 image 集合 + 同源切片标识 + 双清链」三件事在真库/真切片行上成立：视觉向量与描述切片共享 {@code documentId}/{@code
 * chunkId}（引用回查与融合键一致），文本集合与 image 集合各存各的（异构双空间并行），删除/重建按文档清 image 集合。
 *
 * <p>视觉 embedding 走 mock（本 IT 不做真外呼）；真外呼真跑见 {@code VisionEmbeddingRealIT}（{@code
 * RAG_VISION_EMBED_REAL=1} 显式 opt-in）。无 Docker 时整类跳过。
 */
class ImageVectorSpaceIT {

  private static final String KB_ID = "5";

  private static final String DESCRIPTION = "服务台架构图：接入层 -> 调度层 -> 数据层，SLA 台账按分层登记工单、响应时长与解决时长。";

  private static final float[] VISUAL_VECTOR = {0.9F, 0.2F, 0.1F, 0.4F};

  private static MySQLContainer<?> mysql;

  private static SqlSession sqlSession;

  private static DocumentVectorChunkMapper mapper;

  /** 确定性假嵌入：文本空间 8 维，与视觉空间 4 维刻意不同，证明两库维度解耦。 */
  private static final EmbeddingService EMBEDDING =
      new EmbeddingService(null, null) {
        @Override
        public float[] embed(String text) {
          float[] vector = new float[8];
          for (int index = 0; index < vector.length; index++) {
            vector[index] = ((text.length() + index) % 7) / 7.0f;
          }
          return vector;
        }
      };

  @BeforeAll
  static void startContainerAndBuildMapper() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用：跳过真视觉共享空间 IT（必须在真库上跑才有意义）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_image_vector")
            .withUsername("crm")
            .withPassword("crm_it_pwd");
    mysql.start();
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
    PooledDataSource dataSource =
        new PooledDataSource(
            mysql.getDriverClassName(),
            mysql.getJdbcUrl(),
            mysql.getUsername(),
            mysql.getPassword());
    Configuration configuration = new Configuration();
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(
        new Environment("image-vector-it", new JdbcTransactionFactory(), dataSource));
    configuration.addMapper(DocumentVectorChunkMapper.class);
    SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
    sqlSession = factory.openSession(true);
    mapper = sqlSession.getMapper(DocumentVectorChunkMapper.class);
  }

  @AfterAll
  static void stopContainer() {
    if (sqlSession != null) {
      sqlSession.close();
    }
    if (mysql != null) {
      mysql.stop();
    }
  }

  @AfterEach
  void unbindImageStore() {
    ImageVectorCleanupRegistry.unbind();
  }

  @Test
  @DisplayName("视觉向量入库 → query-by-image 命中 image 集合 → SourceReference=IMAGE（文本路并行保留）")
  void visualVectorsIngestThenQueryByImageHitsImageSpace() throws Exception {
    Harness harness = new Harness();
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    InMemoryVectorStore imageBacking = new InMemoryVectorStore();
    ImageVectorCleanupRegistry.bind(new ImageVectorStore(imageBacking));

    List<DocumentChunk> slices =
        renumbered(harness.sliceIngest.buildSlices(pdfWithImages(2), "架构图.pdf", "crm", KB_ID));
    assertThat(slices).hasSize(2);

    List<DocumentVectorChunkEntity> children =
        DocumentIngestionSupport.chunkEmbedAndWrite(
            file("it-image-vector-doc"),
            slices,
            EMBEDDING,
            textStore,
            mapper,
            new DocumentService());

    assertThat(children).hasSize(2);
    // 文本路（方案 B 描述切片）照常入文本集合：两条路并行，未被视觉路替代
    assertThat(textStore.search(textQuery())).hasSize(2);

    // 视觉路：独立 image 集合，2 张图各一条视觉向量
    List<VectorSearchHit> imageHits = imageBacking.search(visualQuery());
    assertThat(imageHits).hasSize(2);
    for (VectorSearchHit hit : imageHits) {
      assertThat(hit.documentId()).isEqualTo("it-image-vector-doc");
      assertThat(hit.text()).isEqualTo(DESCRIPTION);
      assertThat(hit.metadata())
          .containsEntry(ImageSliceMetadata.KEY_SOURCE_TYPE, ImageSliceMetadata.SOURCE_TYPE_IMAGE)
          .containsEntry("knowledgeBaseId", KB_ID);
      assertThat(ImageSliceMetadata.storageKeyOf(hit.metadata())).isNotBlank();
      // 视觉向量与描述切片行主键同源：DB 里确实存在这一行（引用可回查）
      assertThat(children).extracting(row -> String.valueOf(row.getId())).contains(hit.chunkId());
    }

    VectorSearchHit top = imageHits.get(0);
    SourceReference reference = SourceReferenceFactory.from(top, top.score());
    assertThat(reference.sourceType()).isEqualTo("IMAGE");
    assertThat(reference.documentId()).isEqualTo(top.chunkId());
    assertThat(reference.chunkId()).isEqualTo("IMAGE:" + top.chunkId());
    assertThat(reference.excerpt()).isEqualTo(DESCRIPTION);
    assertThat(harness.recorded).extracting(TokenUsageRecord::type).isNotEmpty();
  }

  @Test
  @DisplayName("删除联动：image 集合按文档清空，文本集合与切片行按既有双清链并行处理")
  void deleteClearsImageSpaceByDocument() {
    Harness harness = new Harness();
    InMemoryVectorStore imageBacking = new InMemoryVectorStore();
    ImageVectorCleanupRegistry.bind(new ImageVectorStore(imageBacking));
    String documentId = "it-image-vector-delete";
    List<DocumentChunk> slices =
        renumbered(harness.sliceIngest.buildSlices(pdfWithImages(2), "删除用例.pdf", "crm", KB_ID));
    DocumentIngestionSupport.chunkEmbedAndWrite(
        file(documentId),
        slices,
        EMBEDDING,
        new InMemoryVectorStore(),
        mapper,
        new DocumentService());
    assertThat(imageBacking.search(visualQuery())).hasSize(2);

    ImageAssetCleanup.deleteAssets(documentId, mapper, harness.storage);

    assertThat(imageBacking.search(visualQuery())).isEmpty();
  }

  @Test
  @DisplayName("重建入库：先清旧视觉向量再写新向量，数量不叠加")
  void reingestClearsStaleVisualVectorsBeforeRewrite() throws Exception {
    Harness harness = new Harness();
    InMemoryVectorStore imageBacking = new InMemoryVectorStore();
    ImageVectorCleanupRegistry.bind(new ImageVectorStore(imageBacking));
    String documentId = "it-image-vector-reingest";
    List<DocumentChunk> slices =
        renumbered(harness.sliceIngest.buildSlices(pdfWithImages(2), "重建.pdf", "crm", KB_ID));

    DocumentIngestionSupport.chunkEmbedAndWrite(
        file(documentId),
        slices,
        EMBEDDING,
        new InMemoryVectorStore(),
        mapper,
        new DocumentService());
    assertThat(imageBacking.search(visualQuery())).hasSize(2);

    // 重建入库顺序与生产一致：清旧资产/视觉向量 → 物理删旧切片行 → 重切分重嵌入
    ImageAssetCleanup.deleteAssets(documentId, mapper, harness.storage);
    mapper.deletePhysicallyByDocumentId(documentId);
    List<DocumentChunk> again =
        renumbered(harness.sliceIngest.buildSlices(pdfWithImages(2), "重建.pdf", "crm", KB_ID));
    DocumentIngestionSupport.chunkEmbedAndWrite(
        file(documentId),
        again,
        EMBEDDING,
        new InMemoryVectorStore(),
        mapper,
        new DocumentService());

    assertThat(imageBacking.search(visualQuery())).hasSize(2);
  }

  // ---------- 夹具与装配 ----------

  /** 一次用例的装配：mock VLM/视觉 embedding + 双开关开启 + 内存存储 + 计量收集。 */
  private static final class Harness {
    private final InMemoryFileStorageService storage = new InMemoryFileStorageService();
    private final List<TokenUsageRecord> recorded = new ArrayList<>();
    private final PdfImageSliceIngestService sliceIngest;

    private Harness() {
      ModelProvider modelProvider = mock(ModelProvider.class);
      when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
          .thenReturn(ModelCallResult.ofText(DESCRIPTION, "qwen-vl-plus", 12L, 34L, 46L));
      when(modelProvider.embedImage(any(byte[].class), any()))
          .thenReturn(ModelCallResult.ofVector(VISUAL_VECTOR, "multimodal-embedding-v1", 7L, 11L));
      Map<String, Object> values = new LinkedHashMap<>();
      values.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
      values.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, 5);
      values.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
      TokenUsageRecorder recorder = recorded::add;
      this.sliceIngest =
          new PdfImageSliceIngestService(
              modelProvider, provider(config(values)), provider(recorder), provider(storage));
    }
  }

  private static DynamicConfigService config(Map<String, Object> values) {
    return new DynamicConfigService() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T get(String key, Class<T> type, T defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : (T) value;
      }
    };
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  private static UploadedFileEntity file(String documentId) {
    UploadedFileEntity entity = new UploadedFileEntity();
    entity.setDocumentId(documentId);
    entity.setOriginalFilename("架构图.pdf");
    entity.setFileType("pdf");
    entity.setKnowledgeBase(KB_ID);
    return entity;
  }

  private static List<DocumentChunk> renumbered(List<DocumentChunk> slices) {
    List<DocumentChunk> merged = new ArrayList<>(slices.size());
    for (int index = 0; index < slices.size(); index++) {
      merged.add(slices.get(index).withChunkIndex(index));
    }
    return merged;
  }

  private static VectorSearchRequest visualQuery() {
    return new VectorSearchRequest(VISUAL_VECTOR, 10, null, Map.of("knowledgeBaseId", KB_ID));
  }

  private static VectorSearchRequest textQuery() {
    return new VectorSearchRequest(
        EMBEDDING.embed(DESCRIPTION), 10, null, Map.of("knowledgeBaseId", KB_ID));
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
      throw new IllegalStateException("夹具 PDF 生成失败", exception);
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
