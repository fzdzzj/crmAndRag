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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * add-kb-image-slice-retrieval 任务 4.1：图片切片入库 → 检索命中 → SourceReference=IMAGE → 删除联动清理 IT。
 *
 * <p>真库（Testcontainers MySQL 8.0.36，与生产同版本）+ 一次性库（Flyway 全量迁移）；VLM 走 mock（**本卡无真外呼真跑**），
 * 嵌入走向量长度派生的确定性假模型，向量库为内存实现（不连 Qdrant）。无 Docker 时整类跳过。
 */
class ImageSliceRetrievalIT {

  private static final String DOCUMENT_ID = "it-image-slice-doc";

  private static final String KB_ID = "5";

  private static final String DESCRIPTION = "服务台架构图：接入层 -> 调度层 -> 数据层，SLA 台账按分层登记工单、响应时长与解决时长。";

  private static MySQLContainer<?> mysql;

  private static SqlSession sqlSession;

  private static DocumentVectorChunkMapper mapper;

  /** 确定性假嵌入：无 Provider、无密钥、无外呼。 */
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
        DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用：跳过图片切片检索 IT（必须在真库上跑才有意义）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_image_slice")
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
    // 与生产 MP 配置一致：列名 snake_case → 实体 camelCase（清理侧注解语句依赖）
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(
        new Environment("image-slice-it", new JdbcTransactionFactory(), dataSource));
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

  @Test
  @DisplayName("图片切片入库 → 检索命中 → SourceReference=IMAGE（资产 id/描述摘要/IMAGE:前缀）")
  void imageSlicesIngestThenRetrievalHit_asImageSourceReference() throws Exception {
    Harness harness = new Harness(pdfWithImages(2), 5);
    InMemoryVectorStore vectorStore = new InMemoryVectorStore();
    List<DocumentChunk> slices =
        harness.sliceIngest.buildSlices(harness.pdf, "架构图.pdf", "crm", KB_ID);
    assertThat(slices).hasSize(2);

    List<DocumentVectorChunkEntity> children =
        DocumentIngestionSupport.chunkEmbedAndWrite(
            file(), renumbered(slices), EMBEDDING, vectorStore, mapper, new DocumentService());

    assertThat(children).hasSize(2);
    List<Row> rows = rowsOf(DOCUMENT_ID);
    assertThat(rows).hasSize(2);
    for (Row row : rows) {
      assertThat(row.extraMetadataJson()).contains("\"imageSlice\":true");
      assertThat(row.extraMetadataJson()).contains("imageStorageKey");
    }

    List<VectorSearchHit> hits =
        vectorStore.search(
            new VectorSearchRequest(
                EMBEDDING.embed(DESCRIPTION), 5, 0.0, Map.of("knowledgeBaseId", KB_ID)));
    assertThat(hits).hasSize(2);
    VectorSearchHit hit = hits.get(0);
    assertThat(hit.metadata()).containsEntry("sourceType", "IMAGE");

    SourceReference reference = SourceReferenceFactory.from(hit, hit.score());

    assertThat(reference.sourceType()).isEqualTo("IMAGE");
    assertThat(reference.documentId()).isEqualTo(hit.chunkId());
    assertThat(reference.chunkId()).isEqualTo("IMAGE:" + hit.chunkId());
    assertThat(reference.filename()).isEqualTo(DESCRIPTION);
    assertThat(reference.pageNo()).isEqualTo(1);
    assertThat(reference.rowIndex()).isNull();
    assertThat(reference.excerpt()).isEqualTo(DESCRIPTION);
    // KB 隔离：其他知识库过滤不到该库图片切片
    assertThat(
            vectorStore.search(
                new VectorSearchRequest(
                    EMBEDDING.embed(DESCRIPTION), 5, 0.0, Map.of("knowledgeBaseId", "6"))))
        .isEmpty();
    assertThat(harness.recorded).hasSize(2);
  }

  @Test
  @DisplayName("删除联动清理：图片对象与切片行同时消失，资产 id 不再可读")
  void deleteAlsoClearsImageAssets() {
    Harness harness = new Harness(pdfWithImages(2), 9);
    List<DocumentChunk> slices =
        harness.sliceIngest.buildSlices(harness.pdf, "删除用例.pdf", "crm", KB_ID);
    DocumentIngestionSupport.chunkEmbedAndWrite(
        file("it-image-slice-delete"),
        renumbered(slices),
        EMBEDDING,
        new InMemoryVectorStore(),
        mapper,
        new DocumentService());
    List<Row> rows = rowsOf("it-image-slice-delete");
    assertThat(rows).hasSize(2);
    List<String> storageKeys = new ArrayList<>();
    for (Row row : rows) {
      storageKeys.add(
          ImageSliceMetadata.storageKeyOf(ImageSliceMetadata.fromJson(row.extraMetadataJson())));
    }
    assertThat(storageKeys)
        .hasSize(2)
        .allSatisfy(key -> assertThat(harness.storage.exists(key)).isTrue());

    int deleted = ImageAssetCleanup.deleteAssets("it-image-slice-delete", mapper, harness.storage);

    assertThat(deleted).isEqualTo(2);
    for (String key : storageKeys) {
      assertThat(harness.storage.exists(key)).isFalse();
    }
    // 行清理仍由既有 C-11 双清负责：清理后行消失，资产 id 不再可查
    mapper.deletePhysicallyByDocumentId("it-image-slice-delete");
    assertThat(rowsOf("it-image-slice-delete")).isEmpty();
    assertThat(rows).isNotEmpty();
  }

  // ---------- 夹具与装配 ----------

  /** 一次用例的装配：mock VLM + 固定开配置 + 内存存储 + 计量收集。 */
  private static final class Harness {
    private final byte[] pdf;
    private final InMemoryFileStorageService storage = new InMemoryFileStorageService();
    private final List<TokenUsageRecord> recorded = new ArrayList<>();
    private final PdfImageSliceIngestService sliceIngest;

    private Harness(byte[] pdf, int maxImages) {
      this.pdf = pdf;
      ModelProvider modelProvider = mock(ModelProvider.class);
      when(modelProvider.vision(any(Prompt.class), any(ModelCallOptions.class)))
          .thenReturn(ModelCallResult.ofText(DESCRIPTION, "qwen-vl-plus", 12L, 34L, 46L));
      DynamicConfigService config = config(maxImages);
      TokenUsageRecorder recorder = recorded::add;
      this.sliceIngest =
          new PdfImageSliceIngestService(
              modelProvider, provider(config), provider(recorder), provider(storage));
    }
  }

  private static DynamicConfigService config(int maxImages) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    values.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, maxImages);
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

  private static UploadedFileEntity file() {
    return file(DOCUMENT_ID);
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

  private static List<Row> rowsOf(String documentId) {
    List<Row> rows = new ArrayList<>();
    try (Connection connection =
            java.sql.DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id, chunk_index, extra_metadata_json FROM document_vector_chunk"
                    + " WHERE document_id = ? ORDER BY chunk_index")) {
      statement.setString(1, documentId);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          rows.add(
              new Row(
                  resultSet.getLong("id"),
                  resultSet.getInt("chunk_index"),
                  resultSet.getString("extra_metadata_json")));
        }
      }
    } catch (Exception exception) {
      throw new IllegalStateException("查询切片行失败", exception);
    }
    return rows;
  }

  /** 库内一行切片快照（只取本 IT 断言需要的列）。 */
  private record Row(long id, int chunkIndex, String extraMetadataJson) {}
}
