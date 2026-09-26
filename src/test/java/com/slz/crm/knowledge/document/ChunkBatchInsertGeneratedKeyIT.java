package com.slz.crm.knowledge.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * 批次写库生成键可行性闸（update-document-chunk-write-batching 前置闸）：在一次性真 MySQL（与生产同版本 8.0.36）上独立验证 {@code
 * DocumentVectorChunkMapper#insertBatch} 能否按行序把自增主键逐行回填，以及 「父块先取得 ID → 子块挂 parent_chunk_id → 子块取得
 * ID → 向量 chunkId 引用子块 DB 主键」的顺序， 和批次中途撞唯一键时的部分失败返回（整批原子失败、不落半量、物理清理后可重试）。
 *
 * <p>这是生产批写实现的<b>前置闸</b>：生成键若不能可靠逐行回填，立刻停止生产实现——因此本闸不改主键策略、冻结契约或库结构， 只实测注解式
 * {@code @Options(useGeneratedKeys=true, keyProperty="id")} 在真实驱动下的行为。该机制与仓库既有的 {@code
 * ContractOrderItemMapper#insertBatch}（XML）同源，本闸验证的是注解版这条新写法。
 *
 * <p>隔离：只连 Testcontainers 一次性 MySQL 与一次性库（Flyway 全量迁移建表）；嵌入走确定性假模型（匿名子类， 无
 * Provider、无密钥、无外呼）；向量库为内存捕获桩（不连 Qdrant）。无 Docker 时 assumeTrue 跳过。
 */
class ChunkBatchInsertGeneratedKeyIT {

  /** 闸门文档业务键：固定策略 24 子块。 */
  private static final String DOC_FIXED = "gate-batch-fixed";

  /** 闸门文档业务键：150 子块，跨单批上限 64 的批次边界。 */
  private static final String DOC_MULTI_BATCH = "gate-batch-multibatch";

  /** 闸门文档业务键：语义切分带父块。 */
  private static final String DOC_SEMANTIC = "gate-batch-semantic";

  /** 闸门文档业务键：撞唯一键的批次原子失败与清理后重试。 */
  private static final String DOC_CONFLICT = "gate-batch-conflict";

  /** 生产同版本真库；一次性容器。 */
  private static MySQLContainer<?> mysql;

  private static SqlSession sqlSession;

  private static DocumentVectorChunkMapper mapper;

  /** 确定性假嵌入：无 Provider、无密钥、无外呼，仅按文本长度生成定长向量（隔离闸）。 */
  private static final EmbeddingService EMBEDDING =
      new EmbeddingService(null, null) {
        @Override
        public float[] embed(String text) {
          float[] vector = new float[8];
          for (int i = 0; i < vector.length; i++) {
            vector[i] = ((text.length() + i) % 7) / 7.0f;
          }
          return vector;
        }
      };

  @BeforeAll
  static void startContainerAndBuildMapper() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过批次写库生成键可行性闸 IT（本闸必须在真库上跑才有意义）");
    mysql =
        new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("crm_batch_gate")
            .withUsername("crm")
            .withPassword("crm_it_pwd");
    mysql.start();
    Flyway.configure()
        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
    // 只装配待验 Mapper（不拉起 Spring 上下文）：跑的是生产完全相同的 insertBatch 注解语句。
    PooledDataSource dataSource =
        new PooledDataSource(
            mysql.getDriverClassName(),
            mysql.getJdbcUrl(),
            mysql.getUsername(),
            mysql.getPassword());
    Configuration configuration = new Configuration();
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.setEnvironment(
        new Environment("batch-gate", new JdbcTransactionFactory(), dataSource));
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
  @DisplayName("闸1：固定策略 24 子块逐行回填自增主键，向量 chunkId 引用子块 DB 主键")
  void fixedChunksBackfillGeneratedKeysRowByRow() throws Exception {
    List<DocumentChunk> chunks = fixedChunks(24);
    CapturingVectorStore store = new CapturingVectorStore();
    List<DocumentVectorChunkEntity> children =
        DocumentIngestionSupport.chunkEmbedAndWrite(
            file(DOC_FIXED, "gate-fixed.md", "md"),
            chunks,
            EMBEDDING,
            store,
            mapper,
            new DocumentService());

    assertEquals(24, children.size(), "子块行数口径必须与逐条写库一致（24）");
    Set<Long> ids = new LinkedHashSet<>();
    for (int i = 0; i < children.size(); i++) {
      DocumentVectorChunkEntity child = children.get(i);
      assertNotNull(child.getId(), "第 " + i + " 行生成键未回填（生成键闸不成立）");
      assertTrue(ids.add(child.getId()), "生成键重复，非逐行回填：" + child.getId());
      assertEquals(i, child.getChunkIndex(), "返回顺序必须仍按 chunkIndex 升序");
      assertEquals("CHILD", child.getChunkRole(), "子块角色不变");
      assertNull(child.getParentChunkId(), "固定策略无父块，parent_chunk_id 必须为空");
    }

    List<Row> rows = rowsOf(DOC_FIXED);
    assertEquals(24, rows.size(), "库内逻辑行数必须 24");
    for (Row row : rows) {
      assertEquals("CHILD", row.role());
      assertEquals(
          children.get(row.chunkIndex()).getId(),
          row.id(),
          "库内主键（chunk_index=" + row.chunkIndex() + "）必须等于回填主键");
    }

    assertEquals(24, store.upserted.size(), "向量记录数必须等于子块数");
    for (VectorRecord record : store.upserted) {
      assertEquals(
          String.valueOf(children.get(record.chunkIndex()).getId()),
          record.chunkId(),
          "向量 chunkId 必须引用子块 DB 主键（chunk_index=" + record.chunkIndex() + "）");
    }
  }

  @Test
  @DisplayName("闸2：150 子块跨批次边界逐行回填，全行非空且与 chunk_index 逐一对齐")
  void multiBatchBoundaryBackfillsEveryRow() throws Exception {
    int total = 150;
    List<DocumentVectorChunkEntity> children =
        DocumentIngestionSupport.chunkEmbedAndWrite(
            file(DOC_MULTI_BATCH, "gate-multibatch.md", "md"),
            fixedChunks(total),
            EMBEDDING,
            new CapturingVectorStore(),
            mapper,
            new DocumentService());

    assertEquals(total, children.size(), "跨批次不得丢行");
    for (int i = 0; i < total; i++) {
      assertNotNull(children.get(i).getId(), "第 " + i + " 行（跨批次）生成键未回填");
      assertEquals(i, children.get(i).getChunkIndex());
    }
    List<Row> rows = rowsOf(DOC_MULTI_BATCH);
    assertEquals(total, rows.size(), "库内行数必须等于逻辑行数");
    for (int i = 0; i < total; i++) {
      assertEquals(i, rows.get(i).chunkIndex(), "库内 chunk_index 必须连续无缺");
      assertEquals(children.get(i).getId(), rows.get(i).id(), "跨批主键与 chunk_index 必须逐一对齐");
    }
  }

  @Test
  @DisplayName("闸3：父块先取得 ID，3 个子块挂它的 parent_chunk_id，父块不产向量")
  void parentRowGetsIdBeforeChildrenLinkToIt() throws Exception {
    CapturingVectorStore store = new CapturingVectorStore();
    List<DocumentVectorChunkEntity> children =
        DocumentIngestionSupport.chunkEmbedAndWrite(
            file(DOC_SEMANTIC, "gate-semantic.md", "md"),
            semanticChunksWithParent(),
            EMBEDDING,
            store,
            mapper,
            new DocumentService());

    assertEquals(24, children.size(), "父块行不进子块返回口径");
    List<Row> rows = rowsOf(DOC_SEMANTIC);
    assertEquals(25, rows.size(), "24 子块 + 1 语义父块（index 3 单块段不落父块）");

    Row parent = null;
    for (Row row : rows) {
      if ("PARENT".equals(row.role())) {
        parent = row;
      }
    }
    assertNotNull(parent, "index 0..2 同段必须落一行父块");
    assertEquals(25, parent.chunkIndex(), "父块 chunkIndex 必须从子块总数+1 起编号");
    assertEquals("父段全文A", parent.text(), "父块文本必须是逻辑段全文");

    long minChildId = Long.MAX_VALUE;
    for (Row row : rows) {
      if ("CHILD".equals(row.role())) {
        minChildId = Math.min(minChildId, row.id());
      }
    }
    assertTrue(parent.id() < minChildId, "父块必须先于子块取得 ID（本闸核心顺序断言）");

    for (Row row : rows) {
      if (row.chunkIndex() <= 2) {
        assertEquals("CHILD", row.role());
        assertEquals(parent.id(), row.parentChunkId(), "同段子块必须挂父块主键");
      } else if (row.chunkIndex() == 3) {
        assertNull(row.parentChunkId(), "单块逻辑段自身即父块，不得落父块行也不挂父块");
      } else if (row.chunkIndex() >= 4) {
        assertNull(row.parentChunkId(), "无 parentText 的切片 parent_chunk_id 必须为空");
      }
    }

    assertEquals(24, store.upserted.size(), "父块不嵌入：向量只覆盖 24 个子块");
    for (VectorRecord record : store.upserted) {
      assertEquals(String.valueOf(children.get(record.chunkIndex()).getId()), record.chunkId());
    }
  }

  @Test
  @DisplayName("闸4：批次中途撞唯一键整批原子失败不落半量，物理清理后重试成功")
  void conflictingRowFailsWholeBatchAtomicallyThenRetrySucceeds() throws Exception {
    seedConflictingRow(DOC_CONFLICT, 0);
    assertEquals(1, rowsOf(DOC_CONFLICT).size(), "前置冲突行必须只有 1 行");

    CapturingVectorStore store = new CapturingVectorStore();
    UploadedFileEntity file = file(DOC_CONFLICT, "gate-conflict.md", "md");
    List<DocumentChunk> chunks = fixedChunks(24);

    assertThrows(
        RuntimeException.class,
        () ->
            DocumentIngestionSupport.chunkEmbedAndWrite(
                file, chunks, EMBEDDING, store, mapper, new DocumentService()),
        "批内撞唯一键必须把失败返回给调用方，不得静默");

    assertEquals(1, rowsOf(DOC_CONFLICT).size(), "整批原子失败：不得留下半量行（仍只有前置 1 行）");
    assertEquals(0, store.upserted.size(), "落库失败不得进入向量写库");

    // 既有失败清理语义：物理删切片后可重试（软删会占唯一键，故必须物理删）
    assertEquals(1, mapper.deletePhysicallyByDocumentId(DOC_CONFLICT));
    assertEquals(0, rowsOf(DOC_CONFLICT).size(), "物理清理后不得有残留");

    List<DocumentVectorChunkEntity> children =
        DocumentIngestionSupport.chunkEmbedAndWrite(
            file, chunks, EMBEDDING, store, mapper, new DocumentService());
    assertEquals(24, children.size(), "清理后重试必须写全 24 子块");
    assertEquals(24, rowsOf(DOC_CONFLICT).size());
    for (int i = 0; i < children.size(); i++) {
      assertNotNull(children.get(i).getId(), "重试后第 " + i + " 行生成键未回填");
    }
    assertEquals(24, store.upserted.size(), "重试成功后才可进入向量写库");
  }

  /** 固定策略切片：无 parentText，全部为检索单元子块。 */
  private static List<DocumentChunk> fixedChunks(int count) {
    List<DocumentChunk> chunks = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      chunks.add(
          new DocumentChunk("固定策略切片正文-" + i, i, 1, null, "benchmark", List.of("kw" + i), null));
    }
    return chunks;
  }

  /**
   * 语义切分切片：index 0..2 同属逻辑段 A（3 子块 → 落 1 行父块）；index 3 单块逻辑段 B（自身即父块，不落父块行）； index 4..23 无
   * parentText。
   */
  private static List<DocumentChunk> semanticChunksWithParent() {
    List<DocumentChunk> chunks = new ArrayList<>(24);
    for (int i = 0; i < 24; i++) {
      String parentText = null;
      if (i <= 2) {
        parentText = "父段全文A";
      } else if (i == 3) {
        parentText = "父段全文B";
      }
      chunks.add(
          new DocumentChunk("语义切片正文-" + i, i, 1, null, "benchmark", List.of("kw" + i), parentText));
    }
    return chunks;
  }

  private static UploadedFileEntity file(String documentId, String filename, String fileType) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId(documentId);
    file.setFilename(filename);
    file.setOriginalFilename(filename);
    file.setFileType(fileType);
    file.setKnowledgeBase("1");
    return file;
  }

  /** 预置一行占用 (document_id, chunk_index)=(1) 唯一键的切片，用于触发批次部分失败。 */
  private static void seedConflictingRow(String documentId, int chunkIndex) throws Exception {
    try (Connection connection = openConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO document_vector_chunk "
                    + "(document_id, chunk_index, chunk_text, chunk_hash, filename, category, chunk_role) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      insert.setString(1, documentId);
      insert.setInt(2, chunkIndex);
      insert.setString(3, "前置冲突行");
      insert.setString(4, "conflict-hash");
      insert.setString(5, "gate-conflict.md");
      insert.setString(6, "benchmark");
      insert.setString(7, "CHILD");
      insert.executeUpdate();
    }
  }

  /** 库内实际行（按 chunk_index 升序），用于把「回填主键」与「真实落库形状」对齐。 */
  private static List<Row> rowsOf(String documentId) throws Exception {
    List<Row> rows = new ArrayList<>();
    try (Connection connection = openConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT id, chunk_index, chunk_role, parent_chunk_id, chunk_text "
                    + "FROM document_vector_chunk WHERE document_id = ? ORDER BY chunk_index")) {
      select.setString(1, documentId);
      try (ResultSet rs = select.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new Row(
                  rs.getLong(1),
                  rs.getInt(2),
                  rs.getString(3),
                  (Long) rs.getObject(4),
                  rs.getString(5)));
        }
      }
    }
    return rows;
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
  }

  /** 库内行快照。 */
  private record Row(long id, int chunkIndex, String role, Long parentChunkId, String text) {}

  /** 内存捕获桩：只记 upsertAll 入参，绝不外呼向量库（不连 Qdrant）。 */
  private static final class CapturingVectorStore implements CrmVectorStore {
    private final List<VectorRecord> upserted = new ArrayList<>();

    @Override
    public void upsert(VectorRecord record) {
      upserted.add(record);
    }

    @Override
    public void upsertAll(List<VectorRecord> records) {
      upserted.addAll(records);
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
      return List.of();
    }

    @Override
    public void deleteByDocumentId(String documentId) {
      // 一次性库内自清，无需真删
    }
  }
}
