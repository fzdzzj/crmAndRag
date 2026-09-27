package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.dockerjava.api.exception.NotFoundException;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseMemberEntity;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMemberMapper;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 单库 scope 知识库授权路径基线度量（add-knowledge-base-scope-auth-baseline 阶段 2/3）。
 *
 * <p><b>证据级别</b>：「本机一次性真 MySQL + 生产 Flyway 迁移 + 生产 mapper + 生产授权服务」。库结构由 <b>生产 Flyway
 * 迁移链</b>建出；授权判定完全走 <b>生产 {@link KnowledgeBaseAuthorizationService}</b> 与生产 MyBatis-Plus {@link
 * KnowledgeBaseMapper}/{@link KnowledgeBaseMemberMapper}。知识库与成员全部是本机一次性库里的
 * <b>确定性假数据</b>；<b>不读任何真实凭据、不连业务库、不下载镜像、不调用真实模型、不外发</b>。
 *
 * <p><b>非默认执行（fail closed）</b>：类名不以 {@code Test/Tests/IT/IntegrationTest} 结尾，surefire/failsafe
 * 默认发现不到，仅显式 {@code -Dtest=KnowledgeBaseScopeAuthBaselineBenchmark} 才运行。运行时先校验独立 opt-in 开关 {@link
 * #OPT_IN_ENV}（先于一切容器/装配动作），再做 Docker/镜像<b>只读</b>预检（不 pull）。缺开关、Docker 不可用或本地镜像缺失
 * 一律显式失败并报告「未测」，绝不自动拉镜像、不连业务库、不假装成功。
 *
 * <p><b>冻结口径</b>：{@code visibleKnowledgeBaseIds} 非超管按 owner → PUBLIC → member 三段查询并以 {@link
 * LinkedHashSet} 顺序去重；{@code authorizedKnowledgeBaseIds} 空 scope 表示可见全部，否则把请求 scope 用 {@code
 * Long.valueOf} 解析（非数字/溢出直接忽略）后与可见集求交。本案只把这些<b>现行语义</b>记录成等价快照， 不修语义、不加索引、不声明生产或请求端到端收益。
 *
 * <p><b>复现</b>：{@code KB_SCOPE_AUTH_MEASURE=1 mvn -o -B -ntp
 * -Dtest=KnowledgeBaseScopeAuthBaselineBenchmark test}（stdout 搜 {@code KBSCOPE} 行）。至少两条独立命令各跑一遍，
 * 保留完整原始输出。
 */
class KnowledgeBaseScopeAuthBaselineBenchmark {

  // ---------------------------------------------------------------- 开关与镜像钉扎

  /** 本案独立 opt-in 开关；与其它度量开关互不读取。 */
  static final String OPT_IN_ENV = "KB_SCOPE_AUTH_MEASURE";

  /** 钉扎镜像（本机已有，不拉取）。 */
  static final String DEFAULT_MYSQL_IMAGE = "mysql:8.0";

  /** 镜像覆盖属性。 */
  static final String MYSQL_IMAGE_PROP = "kbscope.mysql.image";

  // ---------------------------------------------------------------- 预登记矩阵（实施前固定）

  /** 少/中/多知识库规模。 */
  static final int[] SCALES = {4, 128, 512};

  /** 每规模的 ID 基址，避免跨规模撞号。 */
  static final long[] SCALE_BASE = {100_000L, 2_000_000L, 30_000_000L};

  /** 非超管主体与超管身份。 */
  static final long SUBJECT_USER_ID = 50L;

  static final long SUBJECT_ROLE_ID = 7L;
  static final long ADMIN_USER_ID = 1L;
  static final long ADMIN_ROLE_ID = 1L;
  static final long DEPT_ID = 1L;

  /** 可见来源分布：每个规模的库按 owner / PUBLIC / member / other 各占 1/4。 */
  static final int DISTRIBUTION_DIVISOR = 4;

  /** 每个规模的样本：热身后 20 次，两轮。 */
  static final int WARMUP = 3;

  static final int SAMPLES = 20;
  static final int ROUNDS = 2;

  /** 并发相：多规模最后一档用 8 线程 × 20 次测吞吐。 */
  static final int C8_THREADS = 8;

  static final int C8_PER_THREAD = 20;

  /** 孤儿成员引用指向一个永远不存在的知识库 ID。 */
  static final long ORPHAN_KB_ID = 900_000_000L;

  /** 硬编码 out-of-range scope（溢出 long）。 */
  static final String OVERFLOW_SCOPE = "99999999999999999999999999";

  /** 四类授权 SQL 归因键。 */
  static final List<String> CATEGORY_KEYS = List.of("owner", "public", "member", "adminAll");

  /** scope 矩阵（逐格固定，实施前登记）。 */
  static final List<String> SCOPE_KINDS =
      List.of("single", "multi", "empty", "duplicate", "nonnumeric", "overflow", "mixed");

  // ---------------------------------------------------------------- 入口

  @Test
  void runKnowledgeBaseScopeAuthBaseline() throws Exception {
    requireOptIn();
    String image = System.getProperty(MYSQL_IMAGE_PROP, DEFAULT_MYSQL_IMAGE);
    requireDockerAndLocalImages(List.of(image));
    MySQLContainer<?> mysql =
        new MySQLContainer<>(DockerImageName.parse(image))
            .withDatabaseName("crm_kb_scope")
            .withUsername("crm")
            .withPassword("crm_kb_pwd");
    mysql.start();
    try {
      String jdbcUrl = mysql.getJdbcUrl();
      String dbUser = mysql.getUsername();
      String dbPassword = mysql.getPassword();
      migrate(jdbcUrl, dbUser, dbPassword);
      Environment env = assemble(jdbcUrl, dbUser, dbPassword);
      printEnvironment(image);
      for (int scaleIndex = 0; scaleIndex < SCALES.length; scaleIndex++) {
        SeedShape shape = new SeedShape(SCALES[scaleIndex], SCALE_BASE[scaleIndex]);
        resetAndSeed(jdbcUrl, dbUser, dbPassword, shape);
        verifySnapshot(env, shape);
        for (int round = 1; round <= ROUNDS; round++) {
          runMatrixRound(env, shape, round);
        }
        runConcurrencyPhase(env, shape);
        explainCategories(env, jdbcUrl, dbUser, dbPassword, shape);
      }
      assertThreadLocalClean();
      printf("KBSCOPE done: threadlocal_clean=true failures=%d", FailureCounter.total());
    } finally {
      mysql.stop();
    }
  }

  // ---------------------------------------------------------------- 门禁（静态、纯 JVM）

  /** 独立 opt-in 校验：缺失即抛错（fail closed），绝不进入容器/装配阶段。 */
  static void requireOptIn() {
    if (!"1".equals(System.getenv(OPT_IN_ENV))) {
      throw new IllegalStateException(
          "未测：缺少独立 opt-in 开关 "
              + OPT_IN_ENV
              + "=1。本度量入口不允许默认运行；开关缺失或镜像缺失一律 fail closed，"
              + "不自动拉取镜像、不连业务库、不假装成功。");
    }
  }

  /** Docker/镜像只读预检：不 pull；镜像缺失即抛错并报告「未测」。 */
  static void requireDockerAndLocalImages(List<String> images) {
    if (!DockerClientFactory.instance().isDockerAvailable()) {
      throw new IllegalStateException("未测：Docker 不可用。本度量需要本机一次性 MySQL，按规格不自动拉取镜像、不降级假装成功。");
    }
    List<String> missing = missingLocalImages(images);
    if (!missing.isEmpty()) {
      throw new IllegalStateException("未测：本地镜像缺失 " + missing + "。按规格不自动拉取镜像；请先手工准备与钉扎一致的镜像后重试。");
    }
  }

  /** 逐镜像 inspectImage 只读探测，返回缺失清单（纯查询，无拉取副作用）。 */
  static List<String> missingLocalImages(List<String> images) {
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

  /** 度量结束后当前线程不得残留账本 ThreadLocal。 */
  static void assertThreadLocalClean() {
    if (SqlLedger.current() != null) {
      throw new IllegalStateException("度量结束仍残留 SqlLedger ThreadLocal");
    }
  }

  // ---------------------------------------------------------------- scope 语义（供守卫复用的纯函数）

  /** 复刻生产 scope 解析：{@code Long.valueOf}，非数字/溢出直接忽略，{@link LinkedHashSet} 去重。 */
  static Set<Long> parseScopes(List<String> requestedScopes) {
    Set<Long> requested = new LinkedHashSet<>();
    if (requestedScopes == null) {
      return requested;
    }
    for (String scope : requestedScopes) {
      try {
        requested.add(Long.valueOf(scope));
      } catch (NumberFormatException ignored) {
        // 与生产实现一致：非数字/溢出 scope 不放大授权，直接忽略。
      }
    }
    return requested;
  }

  /** 复刻生产求交：空 scope 返回全部可见集，否则保持可见集顺序过滤。 */
  static List<Long> expectedAuthorized(List<Long> visible, List<String> requestedScopes) {
    if (requestedScopes == null || requestedScopes.isEmpty()) {
      return new ArrayList<>(visible);
    }
    Set<Long> requested = parseScopes(requestedScopes);
    List<Long> result = new ArrayList<>();
    for (Long id : visible) {
      if (requested.contains(id)) {
        result.add(id);
      }
    }
    return result;
  }

  // ---------------------------------------------------------------- 装配

  private Environment assemble(String jdbcUrl, String user, String password) {
    TimingDataSource dataSource =
        new TimingDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl, user, password);
    MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    factoryBean.setPlugins(new SqlAttributionInterceptor());
    SqlSessionFactory factory;
    try {
      factory = factoryBean.getObject();
    } catch (Exception exception) {
      throw new IllegalStateException("MyBatis-Plus 工厂装配失败", exception);
    }
    if (factory == null) {
      throw new IllegalStateException("MyBatis-Plus 工厂装配返回 null");
    }
    Configuration configuration = factory.getConfiguration();
    configuration.setLogImpl(NoLoggingImpl.class);
    // 生产接线复刻：Spring 上下文的 SqlSessionFactory 用 tangzc MyAnnotationHandler 解析 @TableName。
    GlobalConfigUtils.getGlobalConfig(configuration)
        .setAnnotationHandler(new com.tangzc.mpe.magic.MyAnnotationHandler());
    configuration.addMapper(KnowledgeBaseMapper.class);
    configuration.addMapper(KnowledgeBaseMemberMapper.class);

    TableInfo kbTable = TableInfoHelper.getTableInfo(KnowledgeBaseEntity.class);
    TableInfo memTable = TableInfoHelper.getTableInfo(KnowledgeBaseMemberEntity.class);
    if (kbTable == null || !"knowledge_base".equals(kbTable.getTableName())) {
      throw new IllegalStateException(
          "接线与生产不一致：KnowledgeBaseEntity 解析表名="
              + (kbTable == null ? "null" : kbTable.getTableName())
              + "，期望 knowledge_base");
    }
    if (memTable == null || !"knowledge_base_member".equals(memTable.getTableName())) {
      throw new IllegalStateException(
          "接线与生产不一致：KnowledgeBaseMemberEntity 解析表名="
              + (memTable == null ? "null" : memTable.getTableName())
              + "，期望 knowledge_base_member");
    }
    printf(
        "KBSCOPE wiring: annotation_handler=%s knowledge_base_table=%s member_table=%s",
        GlobalConfigUtils.getGlobalConfig(configuration)
            .getAnnotationHandler()
            .getClass()
            .getName(),
        kbTable.getTableName(),
        memTable.getTableName());

    KnowledgeBaseMapper kbMapper = dispatch(factory, KnowledgeBaseMapper.class);
    KnowledgeBaseMemberMapper memberMapper = dispatch(factory, KnowledgeBaseMemberMapper.class);
    KnowledgeBaseAuthorizationService service =
        new KnowledgeBaseAuthorizationService(kbMapper, memberMapper);
    return new Environment(service, dataSource, jdbcUrl, user, password);
  }

  private static <T> T dispatch(SqlSessionFactory factory, Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (Object proxy, Method method, Object[] args) -> {
              if (method.getName().equals("toString")) {
                return type.getSimpleName() + "@kbscope-dispatch";
              }
              try (SqlSession session = factory.openSession(true)) {
                return method.invoke(session.getMapper(type), args);
              }
            }));
  }

  // ---------------------------------------------------------------- 建库与种子

  private static void migrate(String jdbcUrl, String user, String password) {
    Flyway.configure()
        .dataSource(jdbcUrl, user, password)
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
  }

  /** 每个规模独占一份数据：先清空知识库两表，再只种当前规模，避免跨规模污染可见集。 */
  private static void resetAndSeed(String jdbcUrl, String user, String password, SeedShape shape) {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE TABLE knowledge_base_member");
      statement.execute("TRUNCATE TABLE knowledge_base");
      seedScale(connection, shape);
      seedOrphanMember(connection);
    } catch (SQLException exception) {
      throw new IllegalStateException("种子数据写入失败", exception);
    }
  }

  /** 逐规模写入知识库与成员：owner / PUBLIC / member / other 各 1/4，外加固定反例行。 */
  private static void seedScale(Connection connection, SeedShape shape) throws SQLException {
    int quarter = shape.scale / DISTRIBUTION_DIVISOR;
    String kbSql =
        "INSERT INTO knowledge_base (id, name, display_name, owner_user_id, visibility, is_deleted) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
    String memSql =
        "INSERT INTO knowledge_base_member (knowledge_base_id, user_id, member_role, is_deleted) "
            + "VALUES (?, ?, ?, ?)";
    try (PreparedStatement kb = connection.prepareStatement(kbSql);
        PreparedStatement mem = connection.prepareStatement(memSql)) {
      for (int i = 0; i < shape.scale; i++) {
        long id = shape.base + i;
        String owner;
        String visibility;
        boolean member = false;
        if (i < quarter) {
          owner = ref(SUBJECT_USER_ID);
          visibility = "PRIVATE";
        } else if (i < 2 * quarter) {
          owner = ref(99L);
          visibility = "PUBLIC";
        } else if (i < 3 * quarter) {
          owner = ref(99L);
          visibility = "PRIVATE";
          member = true;
        } else {
          owner = ref(99L);
          visibility = "PRIVATE";
        }
        kb.setLong(1, id);
        kb.setString(2, "kb-s" + shape.scale + "-" + i);
        kb.setString(3, "KB s" + shape.scale + " #" + i);
        kb.setString(4, owner);
        kb.setString(5, visibility);
        kb.setBoolean(6, false);
        kb.addBatch();
        if (member) {
          mem.setLong(1, id);
          mem.setString(2, ref(SUBJECT_USER_ID));
          mem.setString(3, "READER");
          mem.setBoolean(4, false);
          mem.addBatch();
        }
      }
      kb.executeBatch();
      mem.executeBatch();
    }
    try (PreparedStatement kb = connection.prepareStatement(kbSql);
        PreparedStatement mem = connection.prepareStatement(memSql)) {
      insertKb(
          kb,
          shape.overlapId(),
          "kb-s" + shape.scale + "-overlap",
          ref(SUBJECT_USER_ID),
          "PUBLIC",
          false);
      insertKb(
          kb,
          shape.softDeletedOwnedId(),
          "kb-s" + shape.scale + "-softdel-owned",
          ref(SUBJECT_USER_ID),
          "PRIVATE",
          true);
      insertKb(
          kb,
          shape.softDeletedPublicId(),
          "kb-s" + shape.scale + "-softdel-public",
          ref(99L),
          "PUBLIC",
          true);
      insertKb(
          kb,
          shape.memberSoftDeletedKbId(),
          "kb-s" + shape.scale + "-member-softdel",
          ref(99L),
          "PRIVATE",
          false);
      insertKb(
          kb,
          shape.memberOfSoftDeletedId(),
          "kb-s" + shape.scale + "-member-of-softdel",
          ref(99L),
          "PRIVATE",
          true);
      kb.executeBatch();
      // OVERLAP：owner+PUBLIC+member 三重来源重叠
      mem.setLong(1, shape.overlapId());
      mem.setString(2, ref(SUBJECT_USER_ID));
      mem.setString(3, "OWNER");
      mem.setBoolean(4, false);
      mem.addBatch();
      // MEMBER_SOFTDEL_KB：成员行被软删，库仍存活 → 不应可见
      mem.setLong(1, shape.memberSoftDeletedKbId());
      mem.setString(2, ref(SUBJECT_USER_ID));
      mem.setString(3, "READER");
      mem.setBoolean(4, true);
      mem.addBatch();
      // MEMBER_OF_SOFTDELETED：成员行存活但库被软删 → 现行实现仍返回该库引用（孤儿）
      mem.setLong(1, shape.memberOfSoftDeletedId());
      mem.setString(2, ref(SUBJECT_USER_ID));
      mem.setString(3, "READER");
      mem.setBoolean(4, false);
      mem.addBatch();
      mem.executeBatch();
    }
  }

  /** 孤儿成员：成员行引用一个永不存在的知识库 ID，供非超管 member 查询按现行实现如实返回。 */
  private static void seedOrphanMember(Connection connection) throws SQLException {
    try (PreparedStatement mem =
        connection.prepareStatement(
            "INSERT INTO knowledge_base_member (knowledge_base_id, user_id, member_role, is_deleted) "
                + "VALUES (?, ?, ?, ?)")) {
      mem.setLong(1, ORPHAN_KB_ID);
      mem.setString(2, ref(SUBJECT_USER_ID));
      mem.setString(3, "READER");
      mem.setBoolean(4, false);
      mem.executeUpdate();
    }
  }

  private static void insertKb(
      PreparedStatement kb, long id, String name, String owner, String visibility, boolean deleted)
      throws SQLException {
    kb.setLong(1, id);
    kb.setString(2, name);
    kb.setString(3, name);
    kb.setString(4, owner);
    kb.setString(5, visibility);
    kb.setBoolean(6, deleted);
    kb.addBatch();
  }

  private static String ref(long userId) {
    return "user:" + userId;
  }

  // ---------------------------------------------------------------- 快照与反例

  /** 打印规模形状并断言非超管/超管可见集与预登记分布、反例完全一致（现行实现作结果快照）。 */
  private void verifySnapshot(Environment env, SeedShape shape) {
    UserContext subject = subject();
    UserContext admin = admin();
    List<Long> subjectVisible = env.service().visibleKnowledgeBaseIds(subject);
    List<Long> adminVisible = env.service().visibleKnowledgeBaseIds(admin);
    int subjectExpected = 3 * (shape.scale / DISTRIBUTION_DIVISOR) + 3;
    int adminExpected = shape.scale + 2;
    assertEquals(
        subjectExpected,
        subjectVisible.size(),
        "非超管可见集大小应为 3/4*N+3（owner+PUBLIC+member+重叠/孤儿），scale=" + shape.scale);
    assertEquals(
        adminExpected,
        adminVisible.size(),
        "超管可见集应为全部未软删库（N + OVERLAP + MEMBER_SOFTDEL_KB），scale=" + shape.scale);
    assertEquals(
        shape.overlapId(),
        subjectVisible.get(shape.quarter()),
        "owner 段末位应为 OVERLAP（三重来源去重后只出现一次），scale=" + shape.scale);
    assertTrue(
        subjectVisible.contains(shape.memberOfSoftDeletedId()),
        "MEMBER_OF_SOFTDELETED 按现行实现仍出现在非超管可见集（孤儿引用，如实记录不修正），scale=" + shape.scale);
    assertTrue(
        subjectVisible.contains(ORPHAN_KB_ID),
        "ORPHAN 成员引用按现行实现仍出现在非超管可见集（如实记录不修正），scale=" + shape.scale);
    assertTrue(
        !subjectVisible.contains(shape.softDeletedOwnedId()),
        "SOFTDEL_OWNED 不得出现在可见集（@TableLogic 过滤），scale=" + shape.scale);
    assertTrue(
        !subjectVisible.contains(shape.softDeletedPublicId()),
        "SOFTDEL_PUBLIC 不得出现在可见集，scale=" + shape.scale);
    assertTrue(
        !subjectVisible.contains(shape.memberSoftDeletedKbId()),
        "MEMBER_SOFTDEL_KB 不得出现在可见集（成员行被软删），scale=" + shape.scale);
    assertTrue(
        !adminVisible.contains(shape.softDeletedOwnedId())
            && !adminVisible.contains(shape.softDeletedPublicId())
            && !adminVisible.contains(shape.memberOfSoftDeletedId()),
        "超管不得看到任何软删库，scale=" + shape.scale);
    assertEquals(
        subjectVisible,
        env.service().authorizedKnowledgeBaseIds(subject, null),
        "scope=null 应返回全部可见集且保序，scale=" + shape.scale);
    assertEquals(
        subjectVisible,
        env.service().authorizedKnowledgeBaseIds(subject, List.of()),
        "scope=空应返回全部可见集且保序，scale=" + shape.scale);
    printf(
        "KBSCOPE scale: scale=%d base=%d kb_rows=%d owned=%d public=%d member=%d other=%d "
            + "subject_visible=%d admin_visible=%d",
        shape.scale,
        shape.base,
        shape.scale,
        shape.quarter(),
        shape.quarter(),
        shape.quarter(),
        shape.scale - 3 * shape.quarter(),
        subjectVisible.size(),
        adminVisible.size());
    printf(
        "KBSCOPE snapshot: scale=%d subject_visible_ids=%s subject_first_owned=%d overlap_id=%d "
            + "orphan_included=true member_of_softdeleted_included=true softdel_excluded=true",
        shape.scale, subjectVisible, shape.base, shape.overlapId());
  }

  private static UserContext subject() {
    return new UserContext(
        SUBJECT_USER_ID, SUBJECT_ROLE_ID, DEPT_ID, DataScopeLevel.NONE, "kbscope-subject");
  }

  private static UserContext admin() {
    return new UserContext(
        ADMIN_USER_ID, ADMIN_ROLE_ID, DEPT_ID, DataScopeLevel.NONE, "kbscope-admin");
  }

  // ---------------------------------------------------------------- 度量矩阵

  private void runMatrixRound(Environment env, SeedShape shape, int round) {
    UserContext subject = subject();
    UserContext admin = admin();
    List<Long> subjectVisible = env.service().visibleKnowledgeBaseIds(subject);
    List<Long> adminVisible = env.service().visibleKnowledgeBaseIds(admin);
    for (CaseSpec spec : cases(shape, subjectVisible, adminVisible)) {
      runCell(env, shape, spec, round);
    }
  }

  private static List<CaseSpec> cases(
      SeedShape shape, List<Long> subjectVisible, List<Long> adminVisible) {
    long singleSubject = subjectVisible.get(0);
    long singleAdmin = adminVisible.get(0);
    List<String> multiSubject =
        List.of(
            String.valueOf(subjectVisible.get(0)),
            String.valueOf(shape.overlapId()),
            String.valueOf(shape.memberFirstId()));
    List<String> multiAdmin =
        List.of(
            String.valueOf(adminVisible.get(0)),
            String.valueOf(adminVisible.get(adminVisible.size() - 1)),
            String.valueOf(shape.overlapId()));
    List<CaseSpec> specs = new ArrayList<>();
    for (boolean superAdmin : new boolean[] {false, true}) {
      long single = superAdmin ? singleAdmin : singleSubject;
      List<String> multi = superAdmin ? multiAdmin : multiSubject;
      specs.add(new CaseSpec(superAdmin, "single", List.of(String.valueOf(single))));
      specs.add(new CaseSpec(superAdmin, "multi", multi));
      specs.add(new CaseSpec(superAdmin, "empty", List.of()));
      specs.add(
          new CaseSpec(
              superAdmin, "duplicate", List.of(String.valueOf(single), String.valueOf(single))));
      specs.add(new CaseSpec(superAdmin, "nonnumeric", List.of("abc")));
      specs.add(new CaseSpec(superAdmin, "overflow", List.of(OVERFLOW_SCOPE)));
      specs.add(new CaseSpec(superAdmin, "mixed", List.of(String.valueOf(single), "abc", "-1")));
    }
    return specs;
  }

  private void runCell(Environment env, SeedShape shape, CaseSpec spec, int round) {
    UserContext user = spec.superAdmin() ? admin() : subject();
    List<Long> visible = env.service().visibleKnowledgeBaseIds(user);
    List<Long> expectedResult = expectedAuthorized(visible, spec.scopes());
    for (int i = 0; i < WARMUP; i++) {
      env.service().authorizedKnowledgeBaseIds(user, spec.scopes());
    }
    List<Long> authNanos = new ArrayList<>();
    int failures = 0;
    long connCallsBefore = env.dataSource().calls();
    long connNanosBefore = env.dataSource().nanos();
    List<Long> lastResult = List.of();
    SqlStats stats = new SqlStats();
    for (int i = 0; i < SAMPLES; i++) {
      SqlLedger.begin();
      long start = System.nanoTime();
      try {
        lastResult = env.service().authorizedKnowledgeBaseIds(user, spec.scopes());
        authNanos.add(System.nanoTime() - start);
      } catch (RuntimeException exception) {
        failures++;
        FailureCounter.increment();
        printf(
            "KBSCOPE failure: scale=%d user=%s scope=%s error=%s",
            shape.scale, spec.superAdmin() ? "admin" : "subject", spec.scopeKind(), exception);
      } finally {
        stats.add(SqlLedger.snapshot());
        SqlLedger.end();
      }
    }
    long connCalls = env.dataSource().calls() - connCallsBefore;
    long connNanos = env.dataSource().nanos() - connNanosBefore;
    assertEquals(
        expectedResult,
        lastResult,
        "scope="
            + spec.scopeKind()
            + " 结果必须与现行求交语义一致，scale="
            + shape.scale
            + " user="
            + (spec.superAdmin() ? "admin" : "subject"));
    printf(
        "KBSCOPE cell: scale=%d round=%d user=%s scope=%s parsed=%s result_ids=%s result_count=%d "
            + "calls_owner=%d calls_public=%d calls_member=%d calls_admin=%d "
            + "rows_owner=%d rows_public=%d rows_member=%d rows_admin=%d "
            + "auth_p50_ms=%.3f auth_p95_ms=%.3f auth_p99_ms=%.3f auth_avg_ms=%.3f "
            + "throughput_rps=%.1f failures=%d conn_calls=%d conn_avg_ms=%.4f",
        shape.scale,
        round,
        spec.superAdmin() ? "admin" : "subject",
        spec.scopeKind(),
        parseScopes(spec.scopes()),
        lastResult,
        lastResult.size(),
        stats.calls("owner"),
        stats.calls("public"),
        stats.calls("member"),
        stats.calls("adminAll"),
        stats.rows("owner"),
        stats.rows("public"),
        stats.rows("member"),
        stats.rows("adminAll"),
        percentileMillis(authNanos, 50.0),
        percentileMillis(authNanos, 95.0),
        percentileMillis(authNanos, 99.0),
        avgMillis(authNanos),
        1000.0 / Math.max(0.0001, percentileMillis(authNanos, 50.0)),
        failures,
        Math.max(0, connCalls),
        ms(Math.max(0, connNanos), Math.max(1, connCalls)));
  }

  // ---------------------------------------------------------------- 并发相与资源

  private void runConcurrencyPhase(Environment env, SeedShape shape) {
    UserContext user = subject();
    List<Long> visible = env.service().visibleKnowledgeBaseIds(user);
    List<String> scopes = List.of(String.valueOf(visible.get(0)));
    long connCallsBefore = env.dataSource().calls();
    long connNanosBefore = env.dataSource().nanos();
    try (ResourceWindow window = ResourceWindow.start()) {
      ExecutorService pool = Executors.newFixedThreadPool(C8_THREADS);
      List<Long> e2e = Collections.synchronizedList(new ArrayList<>());
      AtomicLong localFailures = new AtomicLong();
      List<Future<?>> futures = new ArrayList<>();
      long wallStart = System.nanoTime();
      for (int t = 0; t < C8_THREADS; t++) {
        futures.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < C8_PER_THREAD; i++) {
                    long start = System.nanoTime();
                    try {
                      env.service().authorizedKnowledgeBaseIds(user, scopes);
                      e2e.add(System.nanoTime() - start);
                    } catch (RuntimeException exception) {
                      localFailures.incrementAndGet();
                      FailureCounter.increment();
                    }
                  }
                }));
      }
      for (Future<?> future : futures) {
        try {
          future.get(5, TimeUnit.MINUTES);
        } catch (Exception exception) {
          throw new IllegalStateException("并发相执行失败", exception);
        }
      }
      pool.shutdown();
      long wallNanos = Math.max(1L, System.nanoTime() - wallStart);
      long requests = (long) C8_THREADS * C8_PER_THREAD;
      long connCallsDelta = env.dataSource().calls() - connCallsBefore;
      long connNanosDelta = env.dataSource().nanos() - connNanosBefore;
      printf(
          "KBSCOPE concurrency: scale=%d threads=%d per_thread=%d requests=%d ok=%d failures=%d "
              + "p50_ms=%.3f p95_ms=%.3f throughput_rps=%.1f conn_wait_avg_ms=%.4f",
          shape.scale,
          C8_THREADS,
          C8_PER_THREAD,
          requests,
          e2e.size(),
          localFailures.get(),
          percentileMillis(e2e, 50.0),
          percentileMillis(e2e, 95.0),
          requests / (wallNanos / 1_000_000_000.0),
          ms(connNanosDelta, Math.max(1, connCallsDelta)));
      printf("KBSCOPE resource: phase=concurrency scale=%d %s", shape.scale, window.summary());
    }
  }

  /** 刷新表统计，避免 EXPLAIN 的 rows 估计被 information_schema 统计缓存污染成 1。 */
  private static void analyzeTables(String jdbcUrl, String user, String password) {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        Statement statement = connection.createStatement()) {
      statement.execute("ANALYZE TABLE knowledge_base, knowledge_base_member");
    } catch (SQLException exception) {
      throw new IllegalStateException("ANALYZE TABLE 失败", exception);
    }
  }

  // ---------------------------------------------------------------- EXPLAIN

  /** 对四类授权 SQL 的真实形状跑 EXPLAIN（参数以字面量代入），用于报告查询计划。 */
  private void explainCategories(
      Environment env, String jdbcUrl, String user, String password, SeedShape shape) {
    analyzeTables(jdbcUrl, user, password);
    for (String category : CATEGORY_KEYS) {
      CapturedSql captured = SqlLedger.capturedSql().get(category);
      if (captured == null) {
        printf(
            "KBSCOPE explain: scale=%d category=%s plan=unknown(no_capture)",
            shape.scale, category);
        continue;
      }
      String plan;
      try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
          Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("EXPLAIN " + captured.substitutedSql())) {
        StringBuilder builder = new StringBuilder();
        while (rs.next()) {
          if (builder.length() > 0) {
            builder.append(" | ");
          }
          builder
              .append("type=")
              .append(rs.getString("type"))
              .append(",key=")
              .append(rs.getString("key"))
              .append(",rows=")
              .append(rs.getString("rows"))
              .append(",filtered=")
              .append(rs.getString("filtered"));
        }
        plan = builder.toString();
      } catch (SQLException exception) {
        plan = "unknown(" + exception.getMessage() + ")";
      }
      printf(
          "KBSCOPE explain: scale=%d category=%s sql=%s plan=%s",
          shape.scale, category, captured.substitutedSql(), plan);
    }
  }

  // ---------------------------------------------------------------- 报告环境

  private void printEnvironment(String image) {
    printf(
        "KBSCOPE env: evidence_class=%s docker=available mysql_image=%s java=%s os=%s arch=%s",
        "local-real-mysql+production-flyway+production-auth-service",
        image,
        System.getProperty("java.version"),
        System.getProperty("os.name"),
        System.getProperty("os.arch"));
    printf(
        "KBSCOPE load: scales=%s warmup=%d samples=%d rounds=%d scope_kinds=%s "
            + "c8_threads=%d c8_per_thread=%d distribution=owner/public/member/other=1/4 each",
        Arrays.toString(SCALES), WARMUP, SAMPLES, ROUNDS, SCOPE_KINDS, C8_THREADS, C8_PER_THREAD);
    printf(
        "KBSCOPE identities: subject=%d role=%d admin=%d role=%d dept=%d "
            + "credentials=none (local-only, no real key read)",
        SUBJECT_USER_ID, SUBJECT_ROLE_ID, ADMIN_USER_ID, ADMIN_ROLE_ID, DEPT_ID);
    printf("KBSCOPE resource: container_resources=unknown (not collected)");
  }

  // ---------------------------------------------------------------- 统计工具

  private static double avgMillis(List<Long> values) {
    if (values == null || values.isEmpty()) {
      return Double.NaN;
    }
    long sum = 0;
    synchronized (values) {
      for (Long value : values) {
        sum += value;
      }
    }
    return sum / 1_000_000.0 / values.size();
  }

  private static double percentileMillis(List<Long> values, double percentile) {
    if (values == null || values.isEmpty()) {
      return Double.NaN;
    }
    List<Long> sorted;
    synchronized (values) {
      sorted = new ArrayList<>(values);
    }
    Collections.sort(sorted);
    int index = (int) Math.round((percentile / 100.0) * (sorted.size() - 1));
    index = Math.max(0, Math.min(sorted.size() - 1, index));
    return sorted.get(index) / 1_000_000.0;
  }

  private static double ms(long nanos, long divisor) {
    return nanos / 1_000_000.0 / Math.max(1, divisor);
  }

  // ---------------------------------------------------------------- SQL 归因

  /** SQL 归因拦截器：按 mapper id + SQL 形状分类 owner/public/member/adminAll，记录调用数、取回行数与耗时。 */
  @Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(
        type = Executor.class,
        method = "query",
        args = {
          MappedStatement.class,
          Object.class,
          RowBounds.class,
          ResultHandler.class,
          org.apache.ibatis.cache.CacheKey.class,
          BoundSql.class
        })
  })
  static class SqlAttributionInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
      Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
      long start = System.nanoTime();
      Object result = null;
      try {
        result = invocation.proceed();
      } finally {
        long nanos = System.nanoTime() - start;
        BoundSql boundSql = statement.getBoundSql(parameter);
        String category = categorize(statement.getId(), boundSql.getSql());
        SqlLedger.record(category, 1, rowsOf(result), nanos);
        SqlLedger.captureSql(category, boundSql, parameter);
      }
      return result;
    }

    static String categorize(String statementId, String sql) {
      String lower = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
      if (statementId.contains("KnowledgeBaseMemberMapper.")) {
        return "member";
      }
      if (statementId.contains("KnowledgeBaseMapper.")) {
        if (lower.contains("owner_user_id")) {
          return "owner";
        }
        if (lower.contains("visibility")) {
          return "public";
        }
        return "adminAll";
      }
      return "other";
    }

    static long rowsOf(Object result) {
      if (result instanceof List<?> list) {
        return list.size();
      }
      return 0L;
    }
  }

  /** 单次调用账本：四类调用数 / 取回行数 / 耗时；线程本地。 */
  static final class SqlLedger {

    private static final ThreadLocal<Map<String, long[]>> CURRENT = new ThreadLocal<>();
    private static final Map<String, CapturedSql> CAPTURED = new ConcurrentHashMap<>();

    static void begin() {
      CURRENT.set(new LinkedHashMap<>());
    }

    static void end() {
      CURRENT.remove();
    }

    static Map<String, long[]> current() {
      return CURRENT.get();
    }

    static void record(String key, long calls, long rows, long nanos) {
      Map<String, long[]> ledger = CURRENT.get();
      if (ledger == null) {
        return;
      }
      long[] slot = ledger.computeIfAbsent(key, ignored -> new long[3]);
      slot[0] += calls;
      slot[1] += rows;
      slot[2] += nanos;
    }

    static Map<String, long[]> snapshot() {
      Map<String, long[]> ledger = CURRENT.get();
      Map<String, long[]> copy = new LinkedHashMap<>();
      if (ledger != null) {
        ledger.forEach((key, value) -> copy.put(key, value.clone()));
      }
      return copy;
    }

    /** 首轮捕获每类 SQL 形状（含参数），供 EXPLAIN 使用；不跨调用保留业务数据。 */
    static void captureSql(String category, BoundSql boundSql, Object parameter) {
      CAPTURED.computeIfAbsent(category, ignored -> CapturedSql.of(boundSql, parameter));
    }

    static Map<String, CapturedSql> capturedSql() {
      return CAPTURED;
    }
  }

  /** 捕获的 SQL 形状与其参数代入后的可 EXPLAIN 语句。 */
  static final class CapturedSql {

    private final String substituted;

    private CapturedSql(String substituted) {
      this.substituted = substituted;
    }

    static CapturedSql of(BoundSql boundSql, Object parameter) {
      String sql = boundSql.getSql();
      List<Object> values = new ArrayList<>();
      boolean resolved = true;
      for (org.apache.ibatis.mapping.ParameterMapping mapping : boundSql.getParameterMappings()) {
        Object value = boundSql.getAdditionalParameter(mapping.getProperty());
        if (value == null && parameter != null) {
          try {
            value = SystemMetaObject.forObject(parameter).getValue(mapping.getProperty());
          } catch (RuntimeException ignored) {
            value = null;
          }
        }
        if (value == null) {
          resolved = false;
          break;
        }
        values.add(value);
      }
      if (!resolved) {
        return new CapturedSql(sql);
      }
      StringBuilder builder = new StringBuilder();
      int cursor = 0;
      for (Object value : values) {
        int index = sql.indexOf('?', cursor);
        if (index < 0) {
          break;
        }
        builder.append(sql, cursor, index).append(literal(value));
        cursor = index + 1;
      }
      builder.append(sql, cursor, sql.length());
      return new CapturedSql(builder.toString());
    }

    String substitutedSql() {
      return substituted;
    }

    private static String literal(Object value) {
      if (value instanceof Number || value instanceof Boolean) {
        return String.valueOf(value);
      }
      return "'" + String.valueOf(value).replace("'", "''") + "'";
    }
  }

  /** 从单格聚合出的四类调用数/行数。 */
  static final class SqlStats {

    private final Map<String, Long> calls = new LinkedHashMap<>();
    private final Map<String, Long> rows = new LinkedHashMap<>();

    void add(Map<String, long[]> snapshot) {
      snapshot.forEach(
          (key, value) -> {
            calls.merge(key, value[0], Long::sum);
            rows.merge(key, value[1], Long::sum);
          });
    }

    long calls(String key) {
      return calls.getOrDefault(key, 0L);
    }

    long rows(String key) {
      return rows.getOrDefault(key, 0L);
    }
  }

  /** 失败计数（并发相跨线程）。 */
  static final class FailureCounter {
    private static final AtomicLong TOTAL = new AtomicLong();

    static void increment() {
      TOTAL.incrementAndGet();
    }

    static long total() {
      return TOTAL.get();
    }
  }

  /** 连接获取计时 DataSource：只统计 getConnection 的调用数与纳秒，不改变任何行为。 */
  static final class TimingDataSource extends PooledDataSource {

    private final LongAdder calls = new LongAdder();
    private final LongAdder nanos = new LongAdder();

    TimingDataSource(String driver, String url, String username, String password) {
      super(driver, url, username, password);
    }

    @Override
    public Connection getConnection() throws SQLException {
      long start = System.nanoTime();
      try {
        return super.getConnection();
      } finally {
        calls.increment();
        nanos.add(System.nanoTime() - start);
      }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      long start = System.nanoTime();
      try {
        return super.getConnection(username, password);
      } finally {
        calls.increment();
        nanos.add(System.nanoTime() - start);
      }
    }

    long calls() {
      return calls.sum();
    }

    long nanos() {
      return nanos.sum();
    }
  }

  /** 同窗资源采样：进程 CPU 均值、堆峰值、GC 计数/暂停增量、线程数、采样数。 */
  static final class ResourceWindow implements AutoCloseable {

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> collectors =
        ManagementFactory.getGarbageCollectorMXBeans();
    private final com.sun.management.OperatingSystemMXBean os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong peakHeap = new AtomicLong();
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong cpuSampleSum = new AtomicLong();
    private final long gcCountStart;
    private final long gcMillisStart;
    private final long cpuNanosStart;
    private final long wallStart;
    private Thread sampler;

    private ResourceWindow() {
      this.gcCountStart = gcCount();
      this.gcMillisStart = gcMillis();
      this.cpuNanosStart = os.getProcessCpuTime();
      this.cpuSampleSum.set(cpuNanosStart);
      this.wallStart = System.nanoTime();
    }

    static ResourceWindow start() {
      ResourceWindow window = new ResourceWindow();
      window.sampler =
          new Thread(
              () -> {
                while (window.running.get()) {
                  long heap = window.memory.getHeapMemoryUsage().getUsed();
                  window.peakHeap.accumulateAndGet(heap, Math::max);
                  window.cpuSampleSum.set(window.os.getProcessCpuTime());
                  window.samples.incrementAndGet();
                  try {
                    Thread.sleep(20L);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
              },
              "kbscope-resource-sampler");
      window.sampler.setDaemon(true);
      window.sampler.start();
      return window;
    }

    private long gcCount() {
      return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    private long gcMillis() {
      return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    String summary() {
      long wallNanos = Math.max(1L, System.nanoTime() - wallStart);
      long cpuDelta = Math.max(0L, cpuSampleSum.get() - cpuNanosStart);
      List<String> parts = new ArrayList<>();
      parts.add("samples=" + samples.get());
      parts.add("process_cpu_cores_avg=" + fmt(cpuDelta / (double) wallNanos));
      parts.add("heap_peak_mb=" + fmt(peakHeap.get() / 1024.0 / 1024.0));
      parts.add("gc_count_delta=" + (gcCount() - gcCountStart));
      parts.add("gc_ms_delta=" + (gcMillis() - gcMillisStart));
      parts.add("threads=" + ManagementFactory.getThreadMXBean().getThreadCount());
      return String.join(" ", parts);
    }

    @Override
    public void close() {
      running.set(false);
      if (sampler != null) {
        try {
          sampler.join(1000L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private static String fmt(double value) {
      return String.format(Locale.ROOT, "%.3f", value);
    }
  }

  // ---------------------------------------------------------------- 数据结构

  /** 单规模形状：基址与固定反例 ID。 */
  record SeedShape(int scale, long base) {
    int quarter() {
      return scale / DISTRIBUTION_DIVISOR;
    }

    long overlapId() {
      return base + scale + 1;
    }

    long softDeletedOwnedId() {
      return base + scale + 2;
    }

    long softDeletedPublicId() {
      return base + scale + 3;
    }

    long memberSoftDeletedKbId() {
      return base + scale + 4;
    }

    long memberOfSoftDeletedId() {
      return base + scale + 5;
    }

    long memberFirstId() {
      return base + 2L * quarter();
    }
  }

  /** 单个测量格：超管/非超管 + scope 类型 + scope 列表。 */
  record CaseSpec(boolean superAdmin, String scopeKind, List<String> scopes) {}

  /** 装配后的运行环境。 */
  record Environment(
      KnowledgeBaseAuthorizationService service,
      TimingDataSource dataSource,
      String jdbcUrl,
      String user,
      String password) {}

  private static void printf(String format, Object... args) {
    System.out.println(String.format(Locale.ROOT, format, args));
  }
}
