package com.slz.crm.integration.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.dockerjava.api.exception.NotFoundException;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseMemberEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseVisibility;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.pojo.vo.KnowledgeFileVO;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMemberMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import com.slz.crm.server.service.KnowledgeAdminService;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 知识库成员可见集「有效库边界」真库 IT（fix-knowledge-base-member-visibility-boundary 阶段 2/3）。
 *
 * <p><b>证据级别</b>：本机一次性真 MySQL（Testcontainers，本机已存在钉扎镜像，缺失即 assumeTrue 跳过、不拉取） + 生产 Flyway 迁移链 + 生产
 * mapper（MyBatis-Plus 接线）+ 生产服务 （{@link KnowledgeBaseAuthorizationService}、{@link
 * KnowledgeAdminService}、{@link SparseRecallService}、 {@link
 * KnowledgeRetrievalServiceImpl}）。知识库/成员/文件/切片全部为确定性假数据； <b>不读真实凭据、不连业务库、不调用真实模型</b>。
 *
 * <p><b>向量路</b>：用记录型假 {@link CrmVectorStore}（确定性假 embedding）只取证「是否向失效 KB 发起 search」， 固定返回空命中——因此本
 * IT <b>不冒充</b>「向量路返回了残留内容」，只把「发起了失效库 search」作为独立证据级别。
 *
 * <p><b>三档证据分级（不可混称泄露）</b>：
 *
 * <ol>
 *   <li>集合多出 ID：{@code visibleKnowledgeBaseIds} / {@code authorizedKnowledgeBaseIds}；
 *   <li>实际返回文件：{@link KnowledgeAdminService#listFiles(Long)}；
 *   <li>实际返回内容：{@link SparseRecallService#recall}；只搜索了失效 KB：向量路 filter。
 * </ol>
 *
 * <p>种子（user:50 / role 7 / dept 1）：KB1 活库自有；KB2 活库成员；KB3 <b>已软删且成员行存活</b>； KB4 公共活库；KB5 无关私有；孤儿成员引用
 * 900000000（库不存在）。上述每个库都留有一条未删 uploaded_file 与一条未删 CHILD 切片，供下游差异取证。
 */
class KnowledgeBaseMemberVisibilityBoundaryIT {

  private static final String MYSQL_IMAGE = "mysql:8.0.36";

  private static MySQLContainer<?> mysql;
  private static KnowledgeBaseAuthorizationService authorization;
  private static KnowledgeAdminService adminService;
  private static SparseRecallService sparseRecall;
  private static SqlSessionFactory factory;

  @BeforeAll
  static void startContainer() throws Exception {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker 不可用：跳过真库成员可见集边界 IT（不拉镜像、不假装成功）");
    assumeTrue(
        missingLocalImages(List.of(MYSQL_IMAGE)).isEmpty(),
        "本地缺失钉扎镜像 " + MYSQL_IMAGE + "：按规格不自动拉取，跳过并记未测");
    mysql =
        new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
            .withDatabaseName("crm_kb_member_boundary")
            .withUsername("crm")
            .withPassword("crm_boundary_pwd");
    mysql.start();
    try (Connection connection =
        DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
      Flyway.configure()
          .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
          .locations("classpath:db/migration")
          .baselineVersion("0")
          .load()
          .migrate();
      seed(connection);
    }
    factory = assemble(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    KnowledgeBaseMapper kbMapper = dispatch(factory, KnowledgeBaseMapper.class);
    KnowledgeBaseMemberMapper memberMapper = dispatch(factory, KnowledgeBaseMemberMapper.class);
    UploadedFileMapper fileMapper = dispatch(factory, UploadedFileMapper.class);
    authorization = new KnowledgeBaseAuthorizationService(kbMapper, memberMapper);
    adminService = new KnowledgeAdminService(kbMapper, fileMapper, authorization, null, null);
    sparseRecall = new SparseRecallService(dispatch(factory, DocumentVectorChunkMapper.class));
  }

  @AfterAll
  static void stopContainer() {
    if (mysql != null) {
      mysql.stop();
    }
  }

  /** 逐镜像只读 inspect，返回缺失清单（纯查询、无拉取副作用）。 */
  private static List<String> missingLocalImages(List<String> images) {
    List<String> missing = new ArrayList<>();
    for (String image : images) {
      try {
        DockerClientFactory.instance().client().inspectImageCmd(image).exec();
      } catch (NotFoundException exception) {
        missing.add(image);
      }
    }
    return missing;
  }

  // ---------------------------------------------------------------- 断言：集合多出 ID

  /** 失效成员来源（软删库、孤儿引用）不得进入可见/授权集合；活库相对顺序保持。 */
  @Test
  void memberSourcesMustExcludeSoftDeletedAndOrphanKnowledgeBases() {
    assertEquals(
        List.of(1L, 4L, 2L),
        authorization.visibleKnowledgeBaseIds(subject()),
        "非超管可见集：owner(KB1) → PUBLIC(KB4) → member(KB2)，且不含软删库 KB3 与孤儿 900000000");
    assertEquals(
        List.of(1L, 4L, 2L),
        authorization.authorizedKnowledgeBaseIds(subject(), null),
        "空 scope 返回全部可见集且保序");
    assertEquals(
        List.of(),
        authorization.authorizedKnowledgeBaseIds(subject(), List.of("3")),
        "软删库单库 scope 不得授权");
    assertEquals(
        List.of(),
        authorization.authorizedKnowledgeBaseIds(subject(), List.of("900000000")),
        "孤儿库单库 scope 不得授权");
    assertEquals(
        List.of(), authorization.authorizedKnowledgeBaseIds(subject(), List.of("5")), "无关私有库不得授权");
    List<Long> adminVisible = new ArrayList<>(authorization.visibleKnowledgeBaseIds(admin()));
    adminVisible.sort(Long::compareTo);
    assertEquals(List.of(1L, 2L, 4L, 5L), adminVisible, "超管只见未软删库（KB3 已软删被 @TableLogic 过滤）");
  }

  // ---------------------------------------------------------------- 断言：实际返回文件

  /** 失效库单库请求不得拿到残留 uploaded_file（修前 KB3 会返回 doc-c）。 */
  @Test
  void listFilesOfInvalidKbMustNotReturnResidualFile() {
    assertEquals(List.of(), documentIds(listFiles(3L)), "软删库 KB3 的残留文件不得被列出");
    assertEquals(List.of(), documentIds(listFiles(900000000L)), "孤儿库的残留文件不得被列出");
  }

  /** 全量列表不得包含失效库残留文件，且活库文件保持。 */
  @Test
  void listFilesAllMustExcludeResidualFilesOfInvalidKbs() {
    List<String> docs = documentIds(listFiles(null));
    docs.sort(String::compareTo);
    assertEquals(
        List.of("doc-a", "doc-b", "doc-d"),
        docs,
        "仅活库 doc-a(KB1)/doc-b(KB2)/doc-d(KB4)；doc-c(KB3)/doc-o(孤儿) 不得出现");
  }

  // ---------------------------------------------------------------- 断言：实际返回内容

  /** 稀疏召回不得返回失效库残留切片文本（修前会返回 doc-c/doc-o 的切片）。 */
  @Test
  void sparseRecallMustNotReturnChunksOfInvalidKbs() {
    List<String> docs =
        sparseRecall
            .recall("XR-900", authorization.authorizedKnowledgeBaseIds(subject(), null), null, 10)
            .stream()
            .map(candidate -> candidate.hit().documentId())
            .sorted()
            .toList();
    assertEquals(List.of("doc-a", "doc-b", "doc-d"), docs, "稀疏路只允许返回活库切片");
    assertTrue(
        sparseRecall
            .recall(
                "XR-900",
                authorization.authorizedKnowledgeBaseIds(subject(), List.of("3")),
                null,
                10)
            .isEmpty(),
        "软删库单库 scope 不授权 → 稀疏路返回空内容");
  }

  // ---------------------------------------------------------------- 断言：只搜索了失效 KB

  /** 向量路不得向失效/孤儿 KB 发起 search；失效单库 scope 连 search 都不发。 */
  @Test
  void vectorRouteMustNotSearchInvalidKnowledgeBases() {
    RecordingVectorStore store = new RecordingVectorStore();
    KnowledgeRetrievalServiceImpl retrieval = retrievalService(store);

    store.reset();
    UserContextHolder.callWith(subject(), () -> retrieval.retrieve(query("XR-900", List.of())));
    List<String> allScopeFilters = store.knowledgeBaseFilters();
    assertTrue(
        allScopeFilters.containsAll(List.of("1", "4", "2")),
        "活库仍必须进入向量路 search，实测 filters=" + allScopeFilters);
    assertFalse(
        allScopeFilters.contains("3"), "软删库 KB3 不得进入向量路 search filter，实测=" + allScopeFilters);
    assertFalse(
        allScopeFilters.contains("900000000"), "孤儿库不得进入向量路 search filter，实测=" + allScopeFilters);

    store.reset();
    KnowledgeRetrievalPort.RetrievalResult singleScope =
        UserContextHolder.callWith(
            subject(), () -> retrieval.retrieve(query("XR-900", List.of("3"))));
    assertTrue(
        store.knowledgeBaseFilters().isEmpty(),
        "软删库单库 scope 不授权 → 连一次 search 都不发，实测=" + store.knowledgeBaseFilters());
    assertEquals(0, singleScope.hitCount(), "失效库单库 scope 检索结果为空");
  }

  // ---------------------------------------------------------------- 回归：合法路径与显式软删实体

  /** 活库文件/检索与 scope 语义保持；无关库仍拒绝。 */
  @Test
  void activePathsAndScopeSemanticsMustBePreserved() {
    assertEquals(List.of("doc-a"), documentIds(listFiles(1L)), "自有活库文件保持");
    assertEquals(List.of("doc-b"), documentIds(listFiles(2L)), "成员活库文件保持");
    assertEquals(List.of("doc-d"), documentIds(listFiles(4L)), "公共活库文件保持");
    assertEquals(List.of(), documentIds(listFiles(5L)), "无关私有库仍拒绝");
    assertEquals(
        authorization.visibleKnowledgeBaseIds(subject()),
        authorization.authorizedKnowledgeBaseIds(subject(), List.of()),
        "空 scope = 全部可见集");
    assertEquals(
        List.of(1L, 4L, 2L),
        authorization.authorizedKnowledgeBaseIds(subject(), List.of("1", "1", "4", "2")),
        "重复 scope 去重、无效 scope 忽略、保持可见集顺序");
    assertEquals(
        List.of(),
        authorization.authorizedKnowledgeBaseIds(subject(), List.of("abc")),
        "非数字 scope 不放权");
    assertEquals(
        List.of(),
        authorization.authorizedKnowledgeBaseIds(subject(), List.of("99999999999999999999999999")),
        "溢出 scope 不放权");
  }

  /** 直接传入显式 isDeleted=true 的实体：owner 与超管都被拒绝；正常实体矩阵不变。 */
  @Test
  void explicitlyDeletedEntityMustBeDeniedForReadAndWrite() {
    KnowledgeBaseEntity deleted = entity(3L, "user:50", KnowledgeBaseVisibility.PRIVATE, true);
    assertFalse(authorization.canRead(deleted, subject()), "显式软删实体的 owner 读判定必须拒绝");
    assertFalse(authorization.canWrite(deleted, subject()), "显式软删实体的 owner 写判定必须拒绝");
    assertFalse(authorization.canRead(deleted, admin()), "显式软删实体对超管的读判定必须拒绝");
    assertFalse(authorization.canWrite(deleted, admin()), "显式软删实体对超管的写判定必须拒绝");
    KnowledgeBaseEntity deletedPublic = entity(4L, "user:99", KnowledgeBaseVisibility.PUBLIC, true);
    assertFalse(authorization.canRead(deletedPublic, subject()), "显式软删的 PUBLIC 实体必须拒绝读");

    KnowledgeBaseEntity activePublic = entity(4L, "user:99", KnowledgeBaseVisibility.PUBLIC, false);
    assertTrue(authorization.canRead(activePublic, subject()), "活动 PUBLIC 实体仍可读");
    assertFalse(authorization.canWrite(activePublic, subject()), "PUBLIC 不等于可写");
    KnowledgeBaseEntity activeOwned = entity(1L, "user:50", KnowledgeBaseVisibility.PRIVATE, false);
    assertTrue(authorization.canRead(activeOwned, subject()), "活动自有库仍可读");
    assertTrue(authorization.canWrite(activeOwned, subject()), "活动自有库仍可写");
  }

  // ---------------------------------------------------------------- 装配

  private static SqlSessionFactory assemble(String jdbcUrl, String user, String password) {
    PooledDataSource dataSource =
        new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl, user, password);
    MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    SqlSessionFactory sqlSessionFactory;
    try {
      sqlSessionFactory = factoryBean.getObject();
    } catch (Exception exception) {
      throw new IllegalStateException("MyBatis-Plus 工厂装配失败", exception);
    }
    if (sqlSessionFactory == null) {
      throw new IllegalStateException("MyBatis-Plus 工厂装配返回 null");
    }
    org.apache.ibatis.session.Configuration configuration = sqlSessionFactory.getConfiguration();
    // 生产接线复刻：Spring 上下文的 SqlSessionFactory 用 tangzc MyAnnotationHandler 解析 @TableName。
    GlobalConfigUtils.getGlobalConfig(configuration)
        .setAnnotationHandler(new com.tangzc.mpe.magic.MyAnnotationHandler());
    configuration.addMapper(KnowledgeBaseMapper.class);
    configuration.addMapper(KnowledgeBaseMemberMapper.class);
    configuration.addMapper(UploadedFileMapper.class);
    configuration.addMapper(DocumentVectorChunkMapper.class);
    TableInfo kbTable = TableInfoHelper.getTableInfo(KnowledgeBaseEntity.class);
    TableInfo memberTable = TableInfoHelper.getTableInfo(KnowledgeBaseMemberEntity.class);
    TableInfo fileTable = TableInfoHelper.getTableInfo(UploadedFileEntity.class);
    if (kbTable == null || !"knowledge_base".equals(kbTable.getTableName())) {
      throw new IllegalStateException("接线与生产不一致：knowledge_base 表名解析失败");
    }
    if (memberTable == null || !"knowledge_base_member".equals(memberTable.getTableName())) {
      throw new IllegalStateException("接线与生产不一致：knowledge_base_member 表名解析失败");
    }
    if (fileTable == null || !"uploaded_file".equals(fileTable.getTableName())) {
      throw new IllegalStateException("接线与生产不一致：uploaded_file 表名解析失败");
    }
    return sqlSessionFactory;
  }

  private static <T> T dispatch(SqlSessionFactory sqlSessionFactory, Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (Object proxy, Method method, Object[] args) -> {
              if (method.getName().equals("toString")) {
                return type.getSimpleName() + "@kbmb-boundary-dispatch";
              }
              try (SqlSession session = sqlSessionFactory.openSession(true)) {
                return method.invoke(session.getMapper(type), args);
              }
            }));
  }

  @SuppressWarnings("unchecked")
  private static KnowledgeRetrievalServiceImpl retrievalService(CrmVectorStore store) {
    EmbeddingService embedding = Mockito.mock(EmbeddingService.class);
    Mockito.when(embedding.embed(Mockito.anyString())).thenReturn(new float[] {1.0f, 0.0f});
    RetrievalQueryRewriteService rewrite = Mockito.mock(RetrievalQueryRewriteService.class);
    Mockito.when(rewrite.rewrite(Mockito.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ObjectProvider<DynamicConfigService> configs =
        (ObjectProvider<DynamicConfigService>) Mockito.mock(ObjectProvider.class);
    return new KnowledgeRetrievalServiceImpl(
        authorization, embedding, store, rewrite, Mockito.mock(Bm25Scorer.class), configs);
  }

  private static KnowledgeRetrievalPort.RetrievalQuery query(String text, List<String> scopes) {
    return new KnowledgeRetrievalPort.RetrievalQuery(text, 50L, scopes, 5, null, null);
  }

  // ---------------------------------------------------------------- 种子

  private static void seed(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_base (id, name, display_name, owner_user_id, visibility, is_deleted) "
              + "VALUES "
              + "(1,'kb-1','KB1 活库自有','user:50','PRIVATE',0),"
              + "(2,'kb-2','KB2 活库成员','user:99','PRIVATE',0),"
              + "(3,'kb-3','KB3 已软删但成员行存活','user:99','PRIVATE',1),"
              + "(4,'kb-4','KB4 公共活库','user:99','PUBLIC',0),"
              + "(5,'kb-5','KB5 无关私有','user:99','PRIVATE',0)");
      statement.execute(
          "INSERT INTO knowledge_base_member (knowledge_base_id, user_id, member_role, is_deleted) "
              + "VALUES (2,'user:50','READER',0),(3,'user:50','READER',0),(900000000,'user:50','READER',0)");
    }
    try (PreparedStatement file =
            connection.prepareStatement(
                "INSERT INTO uploaded_file (user_id, filename, original_filename, file_type, document_id, "
                    + "storage_key, status, knowledge_base) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        PreparedStatement chunk =
            connection.prepareStatement(
                "INSERT INTO document_vector_chunk (document_id, chunk_index, chunk_text, chunk_hash, "
                    + "filename, category) VALUES (?, ?, ?, ?, ?, ?)")) {
      seedFileAndChunk(file, chunk, "doc-a", "1", "aftermarket", "XR-900 活库自有一号切片");
      seedFileAndChunk(file, chunk, "doc-b", "2", "aftermarket", "XR-900 活库成员二号切片");
      seedFileAndChunk(file, chunk, "doc-c", "3", "aftermarket", "XR-900 失效库残留三号切片");
      seedFileAndChunk(file, chunk, "doc-d", "4", "aftermarket", "XR-900 公共库四号切片");
      seedFileAndChunk(file, chunk, "doc-e", "5", "aftermarket", "XR-900 无关私有五号切片");
      seedFileAndChunk(file, chunk, "doc-o", "900000000", "aftermarket", "XR-900 孤儿库残留六号切片");
    }
  }

  private static void seedFileAndChunk(
      PreparedStatement file,
      PreparedStatement chunk,
      String documentId,
      String kbId,
      String category,
      String text)
      throws Exception {
    file.setString(1, "user:99");
    file.setString(2, documentId + ".md");
    file.setString(3, documentId + ".md");
    file.setString(4, "md");
    file.setString(5, documentId);
    file.setString(6, "storage/" + documentId);
    file.setString(7, "COMPLETED");
    file.setString(8, kbId);
    file.executeUpdate();

    chunk.setString(1, documentId);
    chunk.setInt(2, 0);
    chunk.setString(3, text);
    chunk.setString(4, "hash-" + documentId);
    chunk.setString(5, documentId + ".md");
    chunk.setString(6, category);
    chunk.executeUpdate();
  }

  // ---------------------------------------------------------------- 工具

  private static List<KnowledgeFileVO> listFiles(Long kbId) {
    return UserContextHolder.callWith(subject(), () -> adminService.listFiles(kbId));
  }

  private static List<String> documentIds(List<KnowledgeFileVO> files) {
    List<String> ids = new ArrayList<>(files.stream().map(KnowledgeFileVO::getDocumentId).toList());
    ids.sort(String::compareTo);
    return ids;
  }

  private static KnowledgeBaseEntity entity(
      Long id, String owner, KnowledgeBaseVisibility visibility, boolean deleted) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(id);
    entity.setOwnerUserId(owner);
    entity.setVisibility(visibility);
    entity.setIsDeleted(deleted);
    return entity;
  }

  private static UserContext subject() {
    return new UserContext(50L, 7L, 1L, DataScopeLevel.NONE, "kbmb-subject");
  }

  private static UserContext admin() {
    return new UserContext(1L, 1L, 1L, DataScopeLevel.NONE, "kbmb-admin");
  }

  /** 记录型假向量库：只记录每次 search 的 knowledgeBaseId filter，固定返回空命中。 */
  static final class RecordingVectorStore implements CrmVectorStore {

    private final List<Map<String, Object>> filters = new ArrayList<>();

    @Override
    public void upsert(VectorRecord record) {
      // 本 IT 不需写入向量
    }

    @Override
    public void upsertAll(List<VectorRecord> records) {
      // 本 IT 不需写入向量
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
      filters.add(request.filter());
      return List.of();
    }

    @Override
    public void deleteByDocumentId(String documentId) {
      // 本 IT 不需删除向量
    }

    List<String> knowledgeBaseFilters() {
      return filters.stream().map(filter -> String.valueOf(filter.get("knowledgeBaseId"))).toList();
    }

    void reset() {
      filters.clear();
    }
  }
}
