package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.NotFoundException;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ProjectFileVO;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.PermissionsMapper;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.impl.AttachmentAccessServiceImpl;
import com.slz.crm.server.service.impl.DataConvertServiceImpl;
import com.slz.crm.server.service.impl.PermissionServiceImpl;
import com.slz.crm.server.service.impl.ProjectFileServiceImpl;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 项目文件列表鉴权热路径的请求级前测/后测（update-project-file-list-auth-hotpath）。
 *
 * <p><b>证据级别</b>：「本机真实存储 + 生产记录级鉴权」。一次性 MySQL 由<b>生产 Flyway 迁移链</b>建库；列表、分页、记录级鉴权、名称转换、令牌签发全部走
 * <b>生产实现与真实 Mapper/SQL</b>（{@code ProjectFileServiceImpl#queryPage}、{@code
 * AttachmentAccessServiceImpl#canReadProjectFile}、{@code ProjectFileAttachmentReader}、{@code
 * PermissionServiceImpl#hasPermission}、{@code DataConvertServiceImpl#getUserName}）。用户、角色、权限、
 * 活动/商机/合同/订单项与项目文件全部是本机一次性库里的<b>确定性假身份与假业务记录</b>；令牌密钥是本文件内的 16 字节本地常量，
 * <b>不读任何真实凭据、不连业务库、不下载镜像、不调用外部服务</b>。
 *
 * <p><b>非默认执行（fail closed）</b>：类名不以 {@code Test/Tests/IT/IntegrationTest} 结尾或开头，surefire/failsafe
 * 默认发现不到，仅显式 {@code -Dtest=ProjectFileListAuthHotpathBenchmark} 才运行。运行时先校验独立 opt-in 开关 {@link
 * #OPT_IN_ENV} （先于一切容器/装配动作），再做 Docker/镜像<b>只读</b>预检（不 pull）。缺开关、Docker 不可用或本地镜像缺失一律显式失败并报告「未测」。
 *
 * <p><b>冻结口径</b>：{@code queryPage} 先按数据库条件分页（含 count），再逐行筛可读；{@code total} 是数据库条件总数， {@code
 * records} 只是当前页的可读子集，允许不足额甚至为空。列表签发令牌与下载时按当前权限重新校验是两个独立边界：本度量在令牌反例里走 {@code
 * AttachmentAccessServiceImpl#canReadProjectFile}（与 {@code PublicAttachmentController}
 * 下载分支同一入口）复核撤权后的旧令牌， 不合并掉下载复核。
 *
 * <p><b>已知口径偏离</b>：本装配无 Spring 事务代理，{@code @Transactional} 与 {@code @Cacheable} 注解不生效；前者使每条语句各自
 * 取连接（端到端已包含该开销），后者由 {@link CachedNameConvertService} 以同语义的本地壳复现冷/暖两态。前后测使用同一装配，相对归因不受影响。
 *
 * <p><b>复现</b>：{@code PROJECT_FILE_LIST_HOTPATH_MEASURE=1 mvn -B -ntp
 * -Dtest=ProjectFileListAuthHotpathBenchmark test}（stdout 搜 {@code PFLHOT} 行）。同负载两轮在同一
 * JVM、同一容器、同一种子上完成。
 */
class ProjectFileListAuthHotpathBenchmark {

  // ---------------------------------------------------------------- 开关与镜像钉扎

  /** 本案独立 opt-in 开关；与其它度量开关互不读取。 */
  static final String OPT_IN_ENV = "PROJECT_FILE_LIST_HOTPATH_MEASURE";

  /** 钉扎镜像（本机已有，不拉取）。 */
  static final String DEFAULT_MYSQL_IMAGE = "mysql:8.0";

  /** 镜像覆盖属性。 */
  static final String MYSQL_IMAGE_PROP = "pflhot.mysql.image";

  /** 本地令牌密钥：16 字节，仅用于本机假身份，非任何真实凭据。 */
  static final String LOCAL_TOKEN_SECRET = "PFLHOTLOCALKEY16";

  // ---------------------------------------------------------------- 确定性假身份

  static final long ADMIN_ROLE_ID = 1L;
  static final long SALES_ROLE_ID = 7L;
  static final long ADMIN_USER_ID = 1L;
  static final long SALES_USER_ID = 50L;
  static final long FROZEN_USER_ID = 51L;
  static final long LEAVER_USER_ID = 52L;
  static final long OTHER_USER_ID = 99L;
  static final long[] UPLOADER_IDS = {60L, 61L, 62L, 63L, 64L};

  // 角色改派交错回归：专用用户/记录与「无维度权限」角色（只改角色，不动在职状态与业务参与关系）
  static final long REASSIGN_USER_ID = 70L;
  static final long REASSIGN_ACTIVITY_ID = 611L;
  static final long REASSIGN_OPPORTUNITY_ID = 511L;
  static final long REASSIGN_CONTRACT_ID = 711L;
  static final long REASSIGN_READ_ROLE_ID = SALES_ROLE_ID;
  static final long REASSIGN_DENY_ROLE_ID = 8L;
  static final long REASSIGN_EMPTY_ROLE_ID = 9L;
  static final long REASSIGN_BASE = 5000L;
  static final String REASSIGN_THEME = "REASSIGN#";

  // ---------------------------------------------------------------- 确定性假业务记录

  static final long COMPANY_ID = 1L;
  static final long OPP_OWNER = 500L;
  static final long OPP_CREATOR = 501L;
  static final long OPP_APPROVER = 502L;
  static final long OPP_NONE = 503L;
  static final long ACT_CREATOR = 600L;
  static final long ACT_PARTICIPANT = 601L;
  static final long ACT_NONE = 602L;
  static final long ACT_OTHER_PARTICIPANT = 603L;
  static final long CON_OWNER = 700L;
  static final long CON_CREATOR = 701L;
  static final long CON_NONE = 702L;
  static final long ORDER_OK = 800L;
  static final long ORDER_DENY = 801L;

  // ---------------------------------------------------------------- 项目文件种子

  static final long ALL_BASE = 1000L;
  static final long PART_BASE = 2000L;
  static final long NONE_BASE = 3000L;
  static final long PROBE_BASE = 4000L;
  static final int GROUP_SIZE = 120;
  static final int PROBE_COUNT = 15;
  static final String UPLOAD_TIME_ORIGIN = "2026-02-01 00:00:00";

  /** PROBE 逐行规格：{序号, 活动ID, 商机ID, 合同ID, 订单项ID, 上传人ID}（0 表示 NULL）。 */
  static final long[][] PROBE_SPEC = {
    {0, ACT_CREATOR, 0, 0, 0, 50},
    {1, ACT_PARTICIPANT, 0, 0, 0, 60},
    {2, ACT_NONE, 0, 0, 0, 60},
    {3, 0, OPP_OWNER, 0, 0, 60},
    {4, 0, OPP_CREATOR, 0, 0, 60},
    {5, 0, OPP_APPROVER, 0, 0, 60},
    {6, 0, OPP_NONE, 0, 0, 60},
    {7, 0, 0, CON_OWNER, 0, 60},
    {8, 0, 0, CON_CREATOR, 0, 60},
    {9, 0, 0, CON_NONE, 0, 60},
    {10, 0, 0, 0, ORDER_OK, 60},
    {11, 0, 0, 0, ORDER_DENY, 60},
    {12, ACT_NONE, OPP_OWNER, 0, 0, 60},
    {13, 0, 0, 0, 0, 50},
    {14, 0, 0, 0, 0, 99}
  };

  /** SALES 用户对 PROBE 各行的期望可读性（与 {@link #PROBE_SPEC} 同序）。 */
  static final boolean[] PROBE_READABLE = {
    true, true, false, true, true, true, false, true, true, false, true, false, true, true, false
  };

  // ---------------------------------------------------------------- 负载参数

  static final int[] PAGE_SIZES = {10, 50, 100};
  static final int WARMUP = 3;
  static final int SAMPLES = 15;
  static final int ROUNDS = 2;
  static final int C8_THREADS = 8;
  static final int C8_SAMPLES_PER_THREAD = 20;

  /** 打印顺序（键即账本键）。 */
  static final List<String> REPORT_KEYS =
      List.of(
          "req/pagingCount",
          "req/pagingPage",
          "req/other",
          "auth/calls",
          "auth/user",
          "auth/permission",
          "auth/activity",
          "auth/activityUser",
          "auth/opportunity",
          "auth/contract",
          "auth/orderItem",
          "auth/other",
          "name/calls",
          "name/user",
          "token/calls");

  /** 叶子耗时键：求和得到「SQL + 令牌」可归属份额，其余归为残余（装配/CPU）。 */
  static final List<String> LEAF_NANOS_KEYS =
      List.of(
          "req/pagingCount",
          "req/pagingPage",
          "req/other",
          "auth/user",
          "auth/permission",
          "auth/activity",
          "auth/activityUser",
          "auth/opportunity",
          "auth/contract",
          "auth/orderItem",
          "auth/other",
          "name/user",
          "token/nanos");

  // ---------------------------------------------------------------- 入口

  @Test
  void runProjectFileListAuthHotpathMeasurement() throws Exception {
    requireOptIn();
    String image = System.getProperty(MYSQL_IMAGE_PROP, DEFAULT_MYSQL_IMAGE);
    requireDockerAndLocalImages(List.of(image));
    MySQLContainer<?> mysql =
        new MySQLContainer<>(DockerImageName.parse(image))
            .withDatabaseName("crm_pfl_hotpath")
            .withUsername("crm")
            .withPassword("crm_pfl_pwd");
    mysql.start();
    try {
      String jdbcUrl = mysql.getJdbcUrl();
      migrateAndSeed(jdbcUrl, mysql.getUsername(), mysql.getPassword());
      Environment env = assemble(jdbcUrl, mysql.getUsername(), mysql.getPassword(), image);
      printEnvironment(image);
      runFrozenBehaviorAndProbes(env);
      for (int round = 1; round <= ROUNDS; round++) {
        printf("PFLHOT round %d: begin", round);
        for (Condition condition : conditions()) {
          for (CacheMode cache : CacheMode.values()) {
            runCondition(env, condition, cache, round);
          }
        }
        runConcurrencyPhase(env, round);
        printf("PFLHOT round %d: end", round);
      }
      assertThreadLocalClean();
      printf("PFLHOT done: threadlocal_clean=true failures=%d", FailureCounter.total());
    } finally {
      mysql.stop();
    }
  }

  // ---------------------------------------------------------------- 角色改派交错回归

  /**
   * 确定性交错回归（安全等价复核）：在 {@code canReadProjectFile} 首次读取目标用户之后、维度权限判定之前，
   * 由另一条独立连接把该用户改派为「无维度权限」角色并提交。判定必须使用判定时可取得的当前角色， 而不是首次状态闸读到的旧 roleId。交错点固定，不依赖线程调度。
   */
  @Test
  void runRoleReassignInterleavingRegression() throws Exception {
    requireOptIn();
    String image = System.getProperty(MYSQL_IMAGE_PROP, DEFAULT_MYSQL_IMAGE);
    requireDockerAndLocalImages(List.of(image));
    MySQLContainer<?> mysql =
        new MySQLContainer<>(DockerImageName.parse(image))
            .withDatabaseName("crm_pfl_hotpath")
            .withUsername("crm")
            .withPassword("crm_pfl_pwd");
    mysql.start();
    try {
      String jdbcUrl = mysql.getJdbcUrl();
      String dbUser = mysql.getUsername();
      String dbPassword = mysql.getPassword();
      migrateAndSeed(jdbcUrl, dbUser, dbPassword);
      RoleReassignController controller =
          new RoleReassignController(
              jdbcUrl, dbUser, dbPassword, REASSIGN_USER_ID, REASSIGN_DENY_ROLE_ID);
      Environment env = assemble(jdbcUrl, dbUser, dbPassword, image, controller);
      printf(
          "PFLHOT interleave: begin window=after_first_user_read_before_dimension_permission_check");

      verifyReassignBaseline(env);
      verifyReassignListAndSigning(env, controller);
      verifyReassignDownloadRecheck(env, controller);
      verifyReassignDimensions(env, controller);
      verifyReassignSharedActivityEntry(env, controller);
      verifyReassignIsolation(env);
      verifyReassignPermissionSemantics(env);
      printf("PFLHOT interleave: all role-reassign interleaving assertions passed");
    } finally {
      mysql.stop();
    }
  }

  /** 改派前基线：旧角色（7）对活动/商机/合同三维度与共享活动附件入口必须放行。 */
  private void verifyReassignBaseline(Environment env) {
    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    for (long fileId = REASSIGN_BASE; fileId <= REASSIGN_BASE + 2; fileId++) {
      assertTrue(canRead(env, fileId), "改派前三维度文件必须可读，fileId=" + fileId);
    }
    assertTrue(
        env.access()
            .canReadAttachments(
                ModelName.BUSINESS_ACTIVITY, REASSIGN_ACTIVITY_ID, REASSIGN_USER_ID),
        "改派前共享活动附件入口必须放行");
    printf("PFLHOT interleave: baseline_old_role_readable=true");
  }

  /** 列表筛选 + 下载链接签发的两次独立鉴权：交错后 records 必须为空，total 仍是数据库条件总数。 */
  private void verifyReassignListAndSigning(Environment env, RoleReassignController controller) {
    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    currentUser(REASSIGN_USER_ID, REASSIGN_READ_ROLE_ID);
    try {
      Page<ProjectFileVO> before = queryReassignPage(env);
      assertEquals(3L, before.getTotal(), "改派前 REASSIGN 条件总数应为 3");
      assertEquals(
          List.of(REASSIGN_BASE, REASSIGN_BASE + 1, REASSIGN_BASE + 2),
          ids(before.getRecords()),
          "改派前三行必须全部可读且保持 uploadTime DESC");
      assertNotNull(before.getRecords().get(0).getDownloadUrl(), "改派前必须签发下载链接");
    } finally {
      BaseUnit.removeCurrentId();
    }

    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    controller.arm();
    currentUser(REASSIGN_USER_ID, REASSIGN_READ_ROLE_ID);
    try {
      Page<ProjectFileVO> after = queryReassignPage(env);
      assertTrue(controller.fired(), "交错必须真实发生：首次用户读取返回后已由独立连接改派并提交");
      assertEquals(
          REASSIGN_READ_ROLE_ID, controller.observedRoleId().longValue(), "控制器必须读到改派前的旧角色快照");
      assertEquals(3L, after.getTotal(), "total 仍是数据库条件总数，不因撤权变化");
      assertTrue(
          after.getRecords().isEmpty(), "改派后 records 必须为空（判定用当前角色），实际=" + ids(after.getRecords()));
      printf(
          "PFLHOT interleave: list_after_reassign total=%d records=%s",
          after.getTotal(), ids(after.getRecords()));
    } finally {
      BaseUnit.removeCurrentId();
    }
  }

  private static Page<ProjectFileVO> queryReassignPage(Environment env) {
    ProjectFileQueryDTO query = new ProjectFileQueryDTO();
    query.setTheme(REASSIGN_THEME);
    return env.service().queryPage(1, 10, query);
  }

  /** 已签发令牌的下载复核：令牌绑定不变，但改派后复核必须拒绝（下载端点是独立实时鉴权）。 */
  private void verifyReassignDownloadRecheck(Environment env, RoleReassignController controller) {
    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    currentUser(REASSIGN_USER_ID, REASSIGN_READ_ROLE_ID);
    String token = null;
    try {
      for (ProjectFileVO vo : queryReassignPage(env).getRecords()) {
        if (vo.getId() == REASSIGN_BASE) {
          token = tokenOf(vo.getDownloadUrl());
        }
      }
    } finally {
      BaseUnit.removeCurrentId();
    }
    assertNotNull(token, "改派前必须为 REASSIGN 首行签发下载链接");
    AttachmentDownloadTokenUtil.DownloadToken parsed = env.rawTokenUtil().parseDownloadToken(token);
    assertEquals(REASSIGN_USER_ID, parsed.getUserId(), "令牌必须绑定签发用户且不随改派改变");

    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    ProjectFileEntity entity = env.service().getEntityById(REASSIGN_BASE);
    assertNotNull(entity, "种子文件必须存在");
    controller.arm();
    boolean allowed = env.access().canReadProjectFile(entity, REASSIGN_USER_ID);
    assertTrue(controller.fired(), "下载复核交错必须发生");
    assertFalse(allowed, "已签发令牌的下载复核在改派后必须拒绝");
    assertNotNull(env.rawTokenUtil().parseDownloadToken(token), "令牌本身仍可解析（未吊销），拒绝来自实时复核");
    printf(
        "PFLHOT interleave: download_recheck_after_reassign denied=true token_binding_unchanged=true");
  }

  /** 三维度逐文件交错：活动参与 / 商机归属 / 合同归属，改派后各自必须拒绝。 */
  private void verifyReassignDimensions(Environment env, RoleReassignController controller) {
    for (long fileId = REASSIGN_BASE; fileId <= REASSIGN_BASE + 2; fileId++) {
      reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
      controller.arm();
      boolean allowed = canRead(env, fileId);
      assertTrue(controller.fired(), "交错必须发生，fileId=" + fileId);
      assertFalse(allowed, "改派后维度判定必须拒绝，fileId=" + fileId);
    }
    printf("PFLHOT interleave: dimension_rows_after_reassign all_denied=true");
  }

  /** 共享活动附件入口（{@code canReadAttachments}）同样必须按判定时的当前角色拒绝。 */
  private void verifyReassignSharedActivityEntry(
      Environment env, RoleReassignController controller) {
    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    controller.arm();
    boolean allowed =
        env.access()
            .canReadAttachments(
                ModelName.BUSINESS_ACTIVITY, REASSIGN_ACTIVITY_ID, REASSIGN_USER_ID);
    assertTrue(controller.fired(), "共享活动附件入口交错必须发生");
    assertFalse(allowed, "改派后共享活动附件入口必须拒绝");
    printf("PFLHOT interleave: shared_activity_entry_after_reassign denied=true");
  }

  /** 单因素归因：交错期间只允许 role_id 变化；在职状态与业务参与关系必须原封不动。 */
  private void verifyReassignIsolation(Environment env) {
    assertEquals(
        REASSIGN_DENY_ROLE_ID,
        scalarLong(env, "SELECT role_id FROM sys_user WHERE id = 70"),
        "交错后角色必须已是改派目标");
    assertEquals(1L, scalarLong(env, "SELECT status FROM sys_user WHERE id = 70"), "交错不得改变在职状态");
    assertEquals(
        1L,
        scalarLong(
            env,
            "SELECT COUNT(*) FROM business_activity_user WHERE activity_id = 611 AND user_id = 70"),
        "交错不得改变业务参与关系");
    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    printf("PFLHOT interleave: isolation role_only=true status=1 participation_unchanged=true");
  }

  /** 判定语义等价：有权限链但缺目标 → false；零权限角色 → 旧实现同款异常；显式撤权 → 维度隔离。 */
  private void verifyReassignPermissionSemantics(Environment env) {
    reassignIdentityRole(env, REASSIGN_DENY_ROLE_ID);
    assertFalse(canRead(env, REASSIGN_BASE), "角色有权限链但不含活动查看权限时必须 false（不抛）");

    reassignIdentityRole(env, REASSIGN_EMPTY_ROLE_ID);
    BaseException emptyRole =
        assertThrows(
            BaseException.class, () -> canRead(env, REASSIGN_BASE), "零权限角色必须抛权限异常（与旧实现一致）");
    assertTrue(
        emptyRole.getMessage().contains("没有权限"), "异常文案必须与旧实现一致，实际=" + emptyRole.getMessage());

    reassignIdentityRole(env, REASSIGN_READ_ROLE_ID);
    assertTrue(canRead(env, REASSIGN_BASE), "撤权前活动维度必须可读");
    execute(
        env,
        "DELETE FROM role_permissions WHERE role_id = "
            + REASSIGN_READ_ROLE_ID
            + " AND permissions_id = 225");
    try {
      assertFalse(canRead(env, REASSIGN_BASE), "撤权活动查看权限后活动维度必须拒绝");
      assertTrue(canRead(env, REASSIGN_BASE + 1), "撤权活动查看权限不得影响商机维度（任一维度可读）");
      assertTrue(canRead(env, REASSIGN_BASE + 2), "撤权活动查看权限不得影响合同维度（任一维度可读）");
    } finally {
      execute(
          env,
          "INSERT INTO role_permissions (permissions_id, role_id, creator_id, is_deleted) "
              + "VALUES (225,"
              + REASSIGN_READ_ROLE_ID
              + ",NULL,b'0')");
    }
    assertTrue(canRead(env, REASSIGN_BASE), "恢复权限后活动维度必须可读");
    printf("PFLHOT interleave: semantics empty_role_throws=true revoke_dimension_isolated=true");
  }

  /** 直接走列表/下载共用的记录级入口（不设 BaseUnit，与下载复核一致）。 */
  private boolean canRead(Environment env, long fileId) {
    ProjectFileEntity entity = env.service().getEntityById(fileId);
    assertNotNull(entity, "种子文件必须存在，fileId=" + fileId);
    return env.access().canReadProjectFile(entity, REASSIGN_USER_ID);
  }

  /** 只改角色：保持在职状态与业务参与关系不变，隔离「角色变化」这一个因素。 */
  private static void reassignIdentityRole(Environment env, long roleId) {
    execute(env, "UPDATE sys_user SET role_id = " + roleId + " WHERE id = " + REASSIGN_USER_ID);
  }

  private static void currentUser(long userId, long roleId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    role.setRoleId(roleId);
    BaseUnit.setCurrentRole(role);
  }

  private static long scalarLong(Environment env, String sql) {
    try (Connection connection = env.openConnection();
        Statement statement = connection.createStatement();
        java.sql.ResultSet resultSet = statement.executeQuery(sql)) {
      if (!resultSet.next()) {
        throw new IllegalStateException("查询无结果：" + sql);
      }
      return resultSet.getLong(1);
    } catch (SQLException exception) {
      throw new IllegalStateException("查询 SQL 失败：" + sql, exception);
    }
  }

  /**
   * 确定性角色改派控制器：包装 {@link UserMapper} 代理，在目标用户首次 {@code selectById} 返回后， 由另一条独立连接（DriverManager 默认
   * autocommit）把其 {@code role_id} 改派并提交，再把改派前的旧快照返回给调用方。
   *
   * <p>交错点固定在「首次用户读取返回之后、权限判定之前」，不依赖线程调度；只改角色。
   */
  static final class RoleReassignController implements InvocationHandler {

    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final long targetUserId;
    private final long newRoleId;
    private final AtomicBoolean armed = new AtomicBoolean(false);
    private final AtomicBoolean fired = new AtomicBoolean(false);
    private volatile Long observedRoleId;
    private UserMapper delegate;

    RoleReassignController(
        String jdbcUrl, String jdbcUser, String jdbcPassword, long targetUserId, long newRoleId) {
      this.jdbcUrl = jdbcUrl;
      this.jdbcUser = jdbcUser;
      this.jdbcPassword = jdbcPassword;
      this.targetUserId = targetUserId;
      this.newRoleId = newRoleId;
    }

    void bind(UserMapper delegate) {
      this.delegate = delegate;
    }

    UserMapper wrap() {
      if (delegate == null) {
        throw new IllegalStateException("角色改派控制器尚未绑定 UserMapper");
      }
      return (UserMapper)
          Proxy.newProxyInstance(
              UserMapper.class.getClassLoader(), new Class<?>[] {UserMapper.class}, this);
    }

    /** 进入待触发态：下一次目标用户首次读取返回时改派并提交。 */
    void arm() {
      observedRoleId = null;
      fired.set(false);
      armed.set(true);
    }

    boolean fired() {
      return fired.get();
    }

    Long observedRoleId() {
      return observedRoleId;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      boolean hit = isTargetFirstRead(method, args);
      Object result = method.invoke(delegate, args);
      if (hit && result instanceof UserEntity) {
        observedRoleId = ((UserEntity) result).getRoleId();
        reassignRole();
        fired.set(true);
      }
      return result;
    }

    private boolean isTargetFirstRead(Method method, Object[] args) {
      return armed.get()
          && !fired.get()
          && "selectById".equals(method.getName())
          && args != null
          && args.length == 1
          && Objects.equals(args[0], targetUserId);
    }

    private void reassignRole() throws SQLException {
      try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
          Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE sys_user SET role_id = " + newRoleId + " WHERE id = " + targetUserId);
      }
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

  /** 度量结束后当前线程不得残留业务侧 ThreadLocal（BaseUnit 当前用户 / 账本）。 */
  static void assertThreadLocalClean() {
    if (BaseUnit.getCurrentRole() != null) {
      throw new IllegalStateException("度量结束仍残留 BaseUnit 当前用户 ThreadLocal");
    }
    if (Ledger.current() != null || Ledger.currentScope() != null) {
      throw new IllegalStateException("度量结束仍残留 Ledger ThreadLocal");
    }
  }

  // ---------------------------------------------------------------- 装配

  private Environment assemble(String jdbcUrl, String user, String password, String image)
      throws Exception {
    return assemble(jdbcUrl, user, password, image, null);
  }

  /** 交错回归专用装配：与度量装配同一接线，仅把 UserMapper 换成带角色改派控制的代理。 */
  private Environment assemble(
      String jdbcUrl, String user, String password, String image, RoleReassignController reassign)
      throws Exception {
    DataSource dataSource =
        new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl, user, password);
    MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    factoryBean.setMapperLocations(
        new ClassPathResource("mapper/PermissionsMapper.xml"),
        new ClassPathResource("mapper/BusinessActivityUserMapper.xml"));
    // 计数拦截器先注册 = 最内层：必须看得见分页插件内部生成的 count 查询
    factoryBean.setPlugins(new SqlAttributionInterceptor(), mybatisPlusInterceptor());
    SqlSessionFactory factory = factoryBean.getObject();
    Configuration configuration = factory.getConfiguration();
    configuration.setLogImpl(NoLoggingImpl.class);
    // 生产接线复刻：Spring 上下文的 SqlSessionFactory 用 tangzc MyAnnotationHandler 解析
    // @TableName（已实测 cfgHandler=com.tangzc.mpe.magic.MyAnnotationHandler、tableInfo=project_file）；
    // 不设置则 MP 默认 handler 的元注解递归会解析成 <entity>_entity，与真库表名不符。
    com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration)
        .setAnnotationHandler(new com.tangzc.mpe.magic.MyAnnotationHandler());

    for (Class<?> mapper :
        List.of(
            UserMapper.class,
            PermissionsMapper.class,
            RoleMapper.class,
            ProjectFileMapper.class,
            BusinessActivityMapper.class,
            BusinessActivityUserMapper.class,
            SalesOpportunityMapper.class,
            ContractMapper.class,
            ContractOrderItemMapper.class,
            AssistRequestMapper.class,
            ContactTaskMapper.class,
            SalesStageApprovalMapper.class,
            CustomerCompanyMapper.class,
            CustomerContactMapper.class,
            SysDeptMapper.class)) {
      configuration.addMapper(mapper);
    }

    com.baomidou.mybatisplus.core.metadata.TableInfo projectFileTable =
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(
            ProjectFileEntity.class);
    if (projectFileTable == null || !"project_file".equals(projectFileTable.getTableName())) {
      throw new IllegalStateException(
          "接线与生产不一致：ProjectFileEntity 解析表名="
              + (projectFileTable == null ? "null" : projectFileTable.getTableName())
              + "，期望 project_file（Spring 实测值）");
    }
    System.out.println(
        "PFLHOT wiring: annotation_handler="
            + com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration)
                .getAnnotationHandler()
                .getClass()
                .getName()
            + " project_file_table="
            + projectFileTable.getTableName());

    UserMapper userMapper = dispatch(factory, UserMapper.class);
    if (reassign != null) {
      reassign.bind(userMapper);
      userMapper = reassign.wrap();
    }
    PermissionsMapper permissionsMapper = dispatch(factory, PermissionsMapper.class);
    ProjectFileMapper projectFileMapper = dispatch(factory, ProjectFileMapper.class);
    BusinessActivityMapper businessActivityMapper = dispatch(factory, BusinessActivityMapper.class);
    BusinessActivityUserMapper businessActivityUserMapper =
        dispatch(factory, BusinessActivityUserMapper.class);
    SalesOpportunityMapper salesOpportunityMapper = dispatch(factory, SalesOpportunityMapper.class);
    ContractMapper contractMapper = dispatch(factory, ContractMapper.class);
    ContractOrderItemMapper contractOrderItemMapper =
        dispatch(factory, ContractOrderItemMapper.class);

    PermissionServiceImpl permissionService = new PermissionServiceImpl();
    setField(permissionService, "userMapper", userMapper);
    setField(permissionService, "permissionsMapper", permissionsMapper);
    setField(permissionService, "roleMapper", dispatch(factory, RoleMapper.class));
    PermissionService permissionPort = permissionService;

    TimedAccessService accessService =
        new TimedAccessService(
            dispatch(factory, AssistRequestMapper.class),
            businessActivityMapper,
            businessActivityUserMapper,
            dispatch(factory, ContactTaskMapper.class),
            salesOpportunityMapper,
            contractMapper,
            contractOrderItemMapper,
            dispatch(factory, SalesStageApprovalMapper.class),
            userMapper,
            permissionPort,
            new ObjectMapper());

    CachedNameConvertService nameService =
        new CachedNameConvertService(
            userMapper,
            dispatch(factory, CustomerCompanyMapper.class),
            dispatch(factory, CustomerContactMapper.class),
            salesOpportunityMapper,
            contractMapper,
            dispatch(factory, SysDeptMapper.class));

    CountingTokenUtil tokenUtil = new CountingTokenUtil(LOCAL_TOKEN_SECRET);
    AttachmentDownloadTokenUtil rawTokenUtil = new AttachmentDownloadTokenUtil(LOCAL_TOKEN_SECRET);

    ProjectFileServiceImpl service = new ProjectFileServiceImpl();
    setField(service, "baseMapper", projectFileMapper);
    setField(service, "downloadTokenUtil", tokenUtil);
    setField(service, "request", requestStub());
    setField(service, "businessActivityMapper", businessActivityMapper);
    setField(service, "contractOrderItemMapper", contractOrderItemMapper);
    setField(service, "salesOpportunityMapper", salesOpportunityMapper);
    setField(service, "dataConvertService", nameService);
    setField(service, "attachmentAccessService", accessService);
    setField(service, "userMapper", userMapper);
    setField(service, "basePath", "./test-files");

    return new Environment(
        service,
        accessService,
        nameService,
        rawTokenUtil,
        tokenUtil,
        jdbcUrl,
        user,
        password,
        image);
  }

  private static MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
  }

  private static void setField(Object target, String name, Object value) {
    ReflectionTestUtils.setField(target, name, value);
  }

  /** 仅实现空 contextPath 的请求桩；列表基址解析只读 contextPath。 */
  private static jakarta.servlet.http.HttpServletRequest requestStub() {
    InvocationHandler handler =
        (Object proxy, Method method, Object[] args) -> {
          switch (method.getName()) {
            case "getContextPath":
              return "";
            case "toString":
              return "pflhot-request-stub";
            case "hashCode":
              return 0;
            case "equals":
              return proxy == args[0];
            default:
              return null;
          }
        };
    return (jakarta.servlet.http.HttpServletRequest)
        Proxy.newProxyInstance(
            jakarta.servlet.http.HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {jakarta.servlet.http.HttpServletRequest.class},
            handler);
  }

  // ---------------------------------------------------------------- 建库与种子

  private static void migrateAndSeed(String jdbcUrl, String user, String password) {
    Flyway.configure()
        .dataSource(jdbcUrl, user, password)
        .locations("classpath:db/migration")
        .baselineVersion("0")
        .load()
        .migrate();
    try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
        Statement statement = connection.createStatement()) {
      run(
          statement,
          "INSERT INTO sys_role (id, role_name, role_desc, is_deleted) VALUES "
              + "(1,'pflhot-admin','admin',b'0'),(7,'pflhot-sales','sales',b'0')");
      run(
          statement,
          "INSERT INTO sys_user (id, password, real_name, role_id, status) VALUES "
              + "(1,'x','超管一号',1,1),(50,'x','销售甲',7,1),(51,'x','冻结乙',7,0),"
              + "(52,'x','离职丙',7,2),(99,'x','他人丁',7,1),(60,'x','上传人甲',7,1),"
              + "(61,'x','上传人乙',7,1),(62,'x','上传人丙',7,1),(63,'x','上传人丁',7,1),"
              + "(64,'x','上传人戊',7,1)");
      run(
          statement,
          "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES "
              + "(204,'SALES_VIEW_SALE_OPPORTUNITY','pflhot'),('215','SALES_VIEW_CONTRACT','pflhot'),"
              + "(225,'SALES_VIEW_BUSINESS_ACTIVITY','pflhot'),('229','SALES_VIEW_PROJECT_FILE','pflhot')"
                  .replace("'215'", "215")
                  .replace("'229'", "229"));
      run(
          statement,
          "INSERT INTO role_permissions (permissions_id, role_id, creator_id, is_deleted) VALUES "
              + "(204,7,NULL,b'0'),(215,7,NULL,b'0'),(225,7,NULL,b'0'),(229,7,NULL,b'0')");
      run(
          statement,
          "INSERT INTO customer_company (id, company_name, creator_id, owner_id, is_deleted) "
              + "VALUES (1,'pflhot假客户公司',50,50,0)");
      run(
          statement,
          "INSERT INTO sales_opportunity "
              + "(id, opportunity_name, company_id, stage, owner_id, creator_id, approver_id, "
              + "is_deleted) VALUES (500,'商机-owner',1,1,50,99,99,0),"
              + "(501,'商机-creator',1,1,99,50,99,0),(502,'商机-approver',1,1,99,99,50,0),"
              + "(503,'商机-none',1,1,99,99,99,0)");
      run(
          statement,
          "INSERT INTO business_activity "
              + "(id, activity_title, activity_type, activity_time, creator_id) VALUES "
              + "(600,'活动-创建人50','MEETING','2026-01-01 10:00:00',50),"
              + "(601,'活动-参与人50','MEETING','2026-01-01 10:00:00',99),"
              + "(602,'活动-无关','MEETING','2026-01-01 10:00:00',99),"
              + "(603,'活动-他人参与','MEETING','2026-01-01 10:00:00',99)");
      run(
          statement,
          "INSERT INTO business_activity_user (activity_id, user_id, user_role, creator_id) "
              + "VALUES (601,50,'参加人',99),(603,99,'参加人',99)");
      run(
          statement,
          "INSERT INTO contract "
              + "(id, contract_no, company_id, contract_name, total_amount, sign_date, "
              + "contract_status, owner_id, creator_id) VALUES "
              + "(700,'PFLHOT-700',1,'合同-owner',1000,'2026-01-01 00:00:00',1,50,99),"
              + "(701,'PFLHOT-701',1,'合同-creator',1000,'2026-01-01 00:00:00',1,99,50),"
              + "(702,'PFLHOT-702',1,'合同-none',1000,'2026-01-01 00:00:00',1,99,99)");
      run(
          statement,
          "INSERT INTO contract_order_item "
              + "(id, contract_id, product_name, quantity, unit_price, amount) VALUES "
              + "(800,700,'pflhot-product-ok',1,100,100),(801,702,'pflhot-product-deny',1,100,100)");
      // 角色改派交错回归种子：专用角色（有权限链但不含维度权限 / 零权限）与专用在职用户（参与关系固定）
      run(
          statement,
          "INSERT INTO sys_role (id, role_name, role_desc, is_deleted) VALUES "
              + "(8,'pflhot-reassign-deny','deny',b'0'),(9,'pflhot-reassign-empty','empty',b'0')");
      run(
          statement,
          "INSERT INTO sys_user (id, password, real_name, role_id, status) VALUES "
              + "(70,'x','改派目标',7,1)");
      run(
          statement,
          "INSERT INTO role_permissions (permissions_id, role_id, creator_id, is_deleted) VALUES "
              + "(229,8,NULL,b'0')");
      run(
          statement,
          "INSERT INTO business_activity "
              + "(id, activity_title, activity_type, activity_time, creator_id) VALUES "
              + "(611,'活动-改派参与','MEETING','2026-01-01 10:00:00',99)");
      run(
          statement,
          "INSERT INTO business_activity_user (activity_id, user_id, user_role, creator_id) "
              + "VALUES (611,70,'参加人',99)");
      run(
          statement,
          "INSERT INTO sales_opportunity "
              + "(id, opportunity_name, company_id, stage, owner_id, creator_id, approver_id, "
              + "is_deleted) VALUES (511,'商机-改派owner',1,1,70,99,99,0)");
      run(
          statement,
          "INSERT INTO contract "
              + "(id, contract_no, company_id, contract_name, total_amount, sign_date, "
              + "contract_status, owner_id, creator_id) VALUES "
              + "(711,'PFLHOT-711',1,'合同-改派owner',1000,'2026-01-01 00:00:00',1,70,99)");
      run(
          statement,
          "INSERT INTO project_file (id, file_name, file_path, file_type, file_size, category, "
              + "upload_time, uploader_id, theme, activity_id, opportunity_id, contract_id, order_id) "
              + "VALUES "
              + "(5000,'f-5000.pdf','/tmp/pflhot/','application/pdf',1024,'PROPOSAL',"
              + "DATE_ADD('2026-02-01 00:00:00', INTERVAL 2 SECOND),60,'REASSIGN#0',611,NULL,NULL,NULL),"
              + "(5001,'f-5001.pdf','/tmp/pflhot/','application/pdf',1024,'PROPOSAL',"
              + "DATE_ADD('2026-02-01 00:00:00', INTERVAL 1 SECOND),61,'REASSIGN#1',NULL,511,NULL,NULL),"
              + "(5002,'f-5002.pdf','/tmp/pflhot/','application/pdf',1024,'PROPOSAL',"
              + "DATE_ADD('2026-02-01 00:00:00', INTERVAL 0 SECOND),62,'REASSIGN#2',NULL,NULL,711,NULL)");
      seedProjectFiles(statement);
    } catch (SQLException exception) {
      throw new IllegalStateException("种子数据写入失败", exception);
    }
  }

  /** 项目文件：三组各 120 行（全可见 / 隔行可见 / 全不可见）+ 15 行逐维度探针。 */
  private static void seedProjectFiles(Statement statement) throws SQLException {
    List<String> rows = new ArrayList<>();
    for (int i = 0; i < GROUP_SIZE; i++) {
      rows.add(groupRow(ALL_BASE, "ALL", i, dimensionColumn(i), dimensionValue(i)));
      rows.add(
          groupRow(
              PART_BASE,
              "PART",
              i,
              "activity_id",
              String.valueOf(i % 2 == 0 ? ACT_CREATOR : ACT_NONE)));
      rows.add(
          groupRow(
              NONE_BASE,
              "NONE",
              i,
              i % 2 == 0 ? "activity_id" : "opportunity_id",
              String.valueOf(i % 2 == 0 ? ACT_NONE : OPP_NONE)));
    }
    for (long[] spec : PROBE_SPEC) {
      int index = (int) spec[0];
      rows.add(
          probeRow(
              index,
              literal(spec[1]),
              literal(spec[2]),
              literal(spec[3]),
              literal(spec[4]),
              spec[5]));
    }
    statement.executeUpdate(
        "INSERT INTO project_file (id, file_name, file_path, file_type, file_size, category, "
            + "upload_time, uploader_id, theme, activity_id, opportunity_id, contract_id, order_id) "
            + "VALUES "
            + String.join(",", rows));
  }

  private static String literal(long value) {
    return value == 0 ? "NULL" : String.valueOf(value);
  }

  private static String groupRow(
      long base, String group, int index, String dimensionColumn, String dimensionValue) {
    long id = base + index;
    List<String> dimensions = new ArrayList<>();
    for (String column : List.of("activity_id", "opportunity_id", "contract_id", "order_id")) {
      dimensions.add(column.equals(dimensionColumn) ? dimensionValue : "NULL");
    }
    return "("
        + id
        + ",'f-"
        + id
        + ".pdf','/tmp/pflhot/','application/pdf',1024,'PROPOSAL',DATE_ADD('"
        + UPLOAD_TIME_ORIGIN
        + "', INTERVAL "
        + index
        + " SECOND),"
        + UPLOADER_IDS[index % UPLOADER_IDS.length]
        + ",'"
        + group
        + "#"
        + index
        + "',"
        + String.join(",", dimensions)
        + ")";
  }

  private static String probeRow(
      int index,
      String activity,
      String opportunity,
      String contract,
      String order,
      long uploader) {
    long id = PROBE_BASE + index;
    return "("
        + id
        + ",'f-"
        + id
        + ".pdf','/tmp/pflhot/','application/pdf',1024,'PROPOSAL',DATE_ADD('"
        + UPLOAD_TIME_ORIGIN
        + "', INTERVAL "
        + index
        + " SECOND),"
        + uploader
        + ",'PROBE#"
        + index
        + "',"
        + activity
        + ","
        + opportunity
        + ","
        + contract
        + ","
        + order
        + ")";
  }

  /** ALL 组按 i%4 轮转四种归属维度，覆盖活动/商机/合同/订单四条可读路径。 */
  private static String dimensionColumn(int index) {
    switch (index % 4) {
      case 0:
        return "activity_id";
      case 1:
        return "opportunity_id";
      case 2:
        return "contract_id";
      default:
        return "order_id";
    }
  }

  private static String dimensionValue(int index) {
    switch (index % 4) {
      case 0:
        return String.valueOf(ACT_CREATOR);
      case 1:
        return String.valueOf(OPP_OWNER);
      case 2:
        return String.valueOf(CON_OWNER);
      default:
        return String.valueOf(ORDER_OK);
    }
  }

  private static void run(Statement statement, String sql) throws SQLException {
    statement.executeUpdate(sql);
  }

  // ---------------------------------------------------------------- 冻结行为与反例

  /** 冻结行为与权限正反例（非计时窗口）。 */
  private void runFrozenBehaviorAndProbes(Environment env) {
    printf(
        "PFLHOT frozen: queryPage=db_page_then_row_filter; total=db_condition_count; "
            + "records=readable_subset_of_current_page (may underfill or be empty); "
            + "token_signing=per_row_in_VO_assembly; download_recheck=separate_boundary "
            + "(PublicAttachmentController unchanged)");
    verifyPage(env, condition("ALL-10-SALES", "ALL", SALES_USER_ID, 10), 1);
    verifyPage(env, condition("PART-10-SALES", "PART", SALES_USER_ID, 10), 1);
    verifyPage(env, condition("PART-100-SALES", "PART", SALES_USER_ID, 100), 1);
    verifyPage(env, condition("NONE-10-SALES", "NONE", SALES_USER_ID, 10), 1);
    verifyPage(env, condition("NONE-100-SALES-page2", "NONE", SALES_USER_ID, 100), 2);
    verifyPage(env, condition("ALL-100-SALES-page2", "ALL", SALES_USER_ID, 100), 2);
    verifyProbeRows(env);
    verifyListByPaths(env);
    verifyStatusGate(env);
    verifyRevocationAndDownloadRecheck(env);
    printf("PFLHOT frozen: all frozen-behavior and permission probes passed");
  }

  /** PROBE 逐维度可读性（活动参与、商机归属、合同/订单反查、多归属 OR、独立上传）与令牌绑定。 */
  private void verifyProbeRows(Environment env) {
    List<ProjectFileVO> records = verifyPage(env, probeCondition(), 1);
    for (ProjectFileVO vo : records) {
      assertNotNull(vo.getDownloadUrl(), "可读探针行必须签发下载链接，fileId=" + vo.getId());
      AttachmentDownloadTokenUtil.DownloadToken token =
          env.rawTokenUtil().parseDownloadToken(tokenOf(vo.getDownloadUrl()));
      assertEquals(vo.getId(), token.getAttachmentId(), "令牌必须绑定该文件 ID");
      assertEquals(SALES_USER_ID, token.getUserId(), "令牌必须绑定签发用户");
      assertEquals("project_file", token.getFileType(), "令牌文件类型必须是 project_file");
      assertTrue(!env.rawTokenUtil().isTokenExpired(tokenOf(vo.getDownloadUrl())), "令牌不得过期");
    }
    printf(
        "PFLHOT probe: readable=%s denied=%s", expectedIds(probeCondition(), 1), deniedProbeIds());
  }

  private static List<Long> deniedProbeIds() {
    List<Long> denied = new ArrayList<>();
    for (long[] spec : PROBE_SPEC) {
      if (!PROBE_READABLE[(int) spec[0]]) {
        denied.add(PROBE_BASE + spec[0]);
      }
    }
    return denied;
  }

  /** 冻结/离职用户：total 仍为库内条件总数，但 records 全为空。 */
  private void verifyStatusGate(Environment env) {
    for (long userId : new long[] {FROZEN_USER_ID, LEAVER_USER_ID}) {
      verifyPage(env, condition("ALL-10-status", "ALL", userId, 10), 1);
    }
    printf("PFLHOT gate: frozen/leaver total=120 records=0");
  }

  /** 撤权后旧令牌必须被下载实时复核拒绝（走与下载端点同一入口，不改下载端点）。 */
  private void verifyRevocationAndDownloadRecheck(Environment env) {
    Condition probe = probeCondition();
    List<ProjectFileVO> before = verifyPage(env, probe, 1);
    ProjectFileVO opportunityVo = null;
    for (ProjectFileVO vo : before) {
      if (vo.getId() == PROBE_BASE + 3) {
        opportunityVo = vo;
      }
    }
    assertNotNull(opportunityVo, "撤权前应能读到商机归属探针行");
    String oldToken = tokenOf(opportunityVo.getDownloadUrl());
    assertTrue(downloadRecheckAllowed(env, oldToken, SALES_USER_ID), "撤权前下载复核必须放行");

    execute(
        env,
        "DELETE FROM role_permissions WHERE role_id = "
            + SALES_ROLE_ID
            + " AND permissions_id = 204");
    try {
      assertTrue(!downloadRecheckAllowed(env, oldToken, SALES_USER_ID), "撤权后商机维度必须不可读（令牌资格复核）");
      List<ProjectFileVO> after = fetchRecords(env, probe);
      for (ProjectFileVO vo : after) {
        assertTrue(
            !opportunityDependent(vo.getId()),
            "撤权 SALES_VIEW_SALE_OPPORTUNITY(204) 后商机归属行必须从列表消失，id=" + vo.getId());
      }
      printf(
          "PFLHOT revocation: old_token_still_parses=true recheck_denied=true list_excludes_opportunity_rows=true"
              + " window_total=%d window_readable=%s",
          probe.expectedTotal(), ids(after));
    } finally {
      execute(
          env,
          "INSERT INTO role_permissions (permissions_id, role_id, creator_id, is_deleted) "
              + "VALUES (204,"
              + SALES_ROLE_ID
              + ",NULL,b'0')");
    }
    List<ProjectFileVO> restored = verifyPage(env, probe, 1);
    printf(
        "PFLHOT revocation: permission_restored=true full_readable_set_restored=%s", ids(restored));
  }

  /**
   * 四条 {@code listBy*} 路径指纹（若改动共享授权协作类则必须核对输出与链接不变）。
   *
   * <p>四个维度各取一个固定归属 ID，命中种子里的确定性行集合；全部行对本用户可读， 故「链接覆盖数 == 返回条数」为硬断言。令牌含随机量，只比签发资格、用户绑定与有效期。
   */
  private void verifyListByPaths(Environment env) {
    beginUser(probeCondition());
    try {
      printListBy("activity", terrain(env.service().listByActivityId(ACT_CREATOR), env));
      printListBy("opportunity", terrain(env.service().listByOpportunityId(OPP_OWNER), env));
      printListBy("contract", terrain(env.service().listByContractId(CON_OWNER), env));
      printListBy("order", terrain(env.service().listByOrderId(ORDER_OK), env));
    } finally {
      BaseUnit.removeCurrentId();
    }
  }

  /** listBy* 一条路径的指纹行：条数 + ID 顺序 + 链接覆盖 + 令牌绑定。 */
  private static String terrain(List<ProjectFileVO> records, Environment env) {
    List<Long> ids = new ArrayList<>();
    int urls = 0;
    boolean bindingOk = true;
    for (ProjectFileVO vo : records) {
      ids.add(vo.getId());
      String url = vo.getDownloadUrl();
      if (url == null) {
        continue;
      }
      urls++;
      String raw = tokenOf(url);
      AttachmentDownloadTokenUtil.DownloadToken token = env.rawTokenUtil().parseDownloadToken(raw);
      bindingOk &=
          token.getAttachmentId().equals(vo.getId())
              && token.getUserId().equals(SALES_USER_ID)
              && "project_file".equals(token.getFileType())
              && !env.rawTokenUtil().isTokenExpired(raw);
    }
    assertEquals(records.size(), urls, "listBy* 可读行必须全部签发下载链接");
    assertTrue(records.size() > 0, "listBy* 指纹不得为空");
    return "n"
        + records.size()
        + " urls="
        + urls
        + " bind="
        + (bindingOk ? "ok" : "BAD")
        + " ids="
        + ids;
  }

  private static void printListBy(String path, String fingerprint) {
    printf("PFLHOT listby: %s=%s", path, fingerprint);
  }

  /**
   * 探针行是否**依赖商机维度可读**：规格里挂了 opportunityId 且 SALES 期望可读的行（3/4/5/12）。 仅活动（0/1）与合同/订单/独立上传行不依赖 204。
   */
  static boolean opportunityDependent(long id) {
    for (long[] spec : PROBE_SPEC) {
      if (PROBE_BASE + spec[0] == id) {
        return PROBE_READABLE[(int) spec[0]] && spec[2] != 0;
      }
    }
    return false;
  }

  /** 不复用 {@link #verifyPage} 的取数（其断言为完整期望集）：只校验 total 不变并返回当前页记录。 */
  private List<ProjectFileVO> fetchRecords(Environment env, Condition condition) {
    beginUser(condition);
    try {
      Page<ProjectFileVO> page =
          env.service().queryPage(1, condition.pageSize(), queryOf(condition));
      assertEquals(
          condition.expectedTotal(),
          page.getTotal(),
          "撤权不得改变数据库条件总数（total 口径冻结）：" + condition.label());
      return page.getRecords();
    } finally {
      BaseUnit.removeCurrentId();
    }
  }

  static List<Long> ids(List<ProjectFileVO> records) {
    List<Long> list = new ArrayList<>();
    for (ProjectFileVO vo : records) {
      list.add(vo.getId());
    }
    return list;
  }

  /** 模拟下载端点复核：令牌解析 + 与下载分支同一记录级入口重新校验。 */
  private boolean downloadRecheckAllowed(Environment env, String token, long currentUserId) {
    AttachmentDownloadTokenUtil.DownloadToken parsed = env.rawTokenUtil().parseDownloadToken(token);
    boolean sameUser = parsed.getUserId().equals(currentUserId);
    ProjectFileEntity entity = env.service().getEntityById(parsed.getAttachmentId());
    boolean allowed = entity != null && env.access().canReadProjectFile(entity, currentUserId);
    return sameUser && allowed;
  }

  private static void execute(Environment env, String sql) {
    try (Connection connection = env.openConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    } catch (SQLException exception) {
      throw new IllegalStateException("执行 SQL 失败：" + sql, exception);
    }
  }

  // ---------------------------------------------------------------- 矩阵

  static List<Condition> conditions() {
    List<Condition> list = new ArrayList<>();
    for (int size : PAGE_SIZES) {
      list.add(condition("ALL-" + size + "-SALES", "ALL", SALES_USER_ID, size));
    }
    for (int size : PAGE_SIZES) {
      list.add(condition("PART-" + size + "-SALES", "PART", SALES_USER_ID, size));
    }
    for (int size : PAGE_SIZES) {
      list.add(condition("NONE-" + size + "-SALES", "NONE", SALES_USER_ID, size));
    }
    list.add(condition("ADMIN-10", "ALL", ADMIN_USER_ID, 10));
    list.add(condition("FROZEN-10", "ALL", FROZEN_USER_ID, 10));
    list.add(condition("LEAVER-10", "ALL", LEAVER_USER_ID, 10));
    return list;
  }

  static Condition condition(String label, String group, long userId, int pageSize) {
    return new Condition(
        label, group, userId, pageSize, "PROBE".equals(group) ? PROBE_COUNT : GROUP_SIZE);
  }

  static Condition probeCondition() {
    return condition("PROBE", "PROBE", SALES_USER_ID, 100);
  }

  /** 单条件 × 缓存模式 × 轮次：预热 → 采样 → 形状断言 → 报告。 */
  private void runCondition(Environment env, Condition condition, CacheMode cache, int round) {
    env.nameService().setWarm(cache == CacheMode.WARM);
    if (cache == CacheMode.WARM) {
      env.nameService().prewarm(UPLOADER_IDS);
    }
    for (int i = 0; i < WARMUP; i++) {
      Ledger.begin();
      try {
        guardedRequest(env, condition, 1, cache);
      } finally {
        Ledger.end();
      }
    }
    Aggregate aggregate = new Aggregate();
    for (int i = 0; i < SAMPLES; i++) {
      if (cache == CacheMode.COLD) {
        env.nameService().clearCache();
      }
      Ledger.begin();
      long start = System.nanoTime();
      boolean ok = guardedRequest(env, condition, 1, cache);
      long nanos = System.nanoTime() - start;
      Map<String, long[]> snapshot = Ledger.snapshot();
      Ledger.end();
      if (ok) {
        aggregate.add(snapshot, nanos);
      } else {
        FailureCounter.increment();
      }
    }
    verifyPage(env, condition, 1);
    report("matrix", round, condition, cache, aggregate, SAMPLES);
  }

  /** 并发相：PART/50 与 NONE/50，8 线程 × 20 样本，逐线程独立账本。 */
  private void runConcurrencyPhase(Environment env, int round) throws Exception {
    for (String group : List.of("PART", "NONE")) {
      Condition condition = condition(group + "-50-C8", group, SALES_USER_ID, 50);
      env.nameService().setWarm(true);
      env.nameService().prewarm(UPLOADER_IDS);
      Aggregate aggregate = new Aggregate();
      ExecutorService pool = Executors.newFixedThreadPool(C8_THREADS);
      CountDownLatch ready = new CountDownLatch(C8_THREADS);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int thread = 0; thread < C8_THREADS; thread++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  try {
                    go.await(30, TimeUnit.SECONDS);
                    for (int i = 0; i < C8_SAMPLES_PER_THREAD; i++) {
                      Ledger.begin();
                      long start = System.nanoTime();
                      boolean ok = guardedRequest(env, condition, 1, CacheMode.WARM);
                      long nanos = System.nanoTime() - start;
                      Map<String, long[]> snapshot = Ledger.snapshot();
                      Ledger.end();
                      if (ok) {
                        aggregate.add(snapshot, nanos);
                      } else {
                        FailureCounter.increment();
                      }
                    }
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }));
      }
      ready.await(30, TimeUnit.SECONDS);
      ResourceWindow window = ResourceWindow.start();
      go.countDown();
      for (Future<?> future : futures) {
        future.get(120, TimeUnit.SECONDS);
      }
      window.close();
      pool.shutdownNow();
      report(
          "concurrency",
          round,
          condition,
          CacheMode.WARM,
          aggregate,
          C8_THREADS * C8_SAMPLES_PER_THREAD);
      printf("PFLHOT resource: phase=%s round=%d %s", condition.label(), round, window.summary());
    }
  }

  /** 单次请求：设当前用户 → queryPage → 清理。返回是否无异常。 */
  private boolean guardedRequest(
      Environment env, Condition condition, int pageNum, CacheMode cache) {
    boolean ok = true;
    beginUser(condition);
    try {
      env.service().queryPage(pageNum, condition.pageSize(), queryOf(condition));
    } catch (RuntimeException | AssertionError failure) {
      ok = false;
      printf(
          "PFLHOT failure: cond=%s cache=%s page=%d error=%s",
          condition.label(), cache, pageNum, failure);
    } finally {
      BaseUnit.removeCurrentId();
    }
    return ok;
  }

  /** 形状断言并返回当前页可读记录（total / ID 顺序 / 链接与用户绑定）。 */
  private List<ProjectFileVO> verifyPage(Environment env, Condition condition, int pageNum) {
    beginUser(condition);
    try {
      Page<ProjectFileVO> page =
          env.service().queryPage(pageNum, condition.pageSize(), queryOf(condition));
      List<Long> expected = expectedIds(condition, pageNum);
      assertEquals(
          condition.expectedTotal(),
          page.getTotal(),
          "total 必须保留数据库条件总数：" + condition.label() + " page=" + pageNum);
      List<Long> actual = new ArrayList<>();
      for (ProjectFileVO vo : page.getRecords()) {
        actual.add(vo.getId());
        assertNotNull(
            vo.getDownloadUrl(), "返回行必须签发下载链接：" + condition.label() + " id=" + vo.getId());
      }
      assertEquals(
          expected,
          actual,
          "records 必须是页内可读子集且保持 uploadTime DESC 顺序：" + condition.label() + " page=" + pageNum);
      return page.getRecords();
    } finally {
      BaseUnit.removeCurrentId();
    }
  }

  private static ProjectFileQueryDTO queryOf(Condition condition) {
    ProjectFileQueryDTO query = new ProjectFileQueryDTO();
    query.setTheme(condition.theme());
    return query;
  }

  private static void beginUser(Condition condition) {
    RoleAO role = new RoleAO();
    role.setId(condition.userId());
    role.setRoleId(condition.userId() == ADMIN_USER_ID ? ADMIN_ROLE_ID : SALES_ROLE_ID);
    BaseUnit.setCurrentRole(role);
  }

  /** 页内期望可读 ID（uploadTime DESC 顺序）。 */
  static List<Long> expectedIds(Condition condition, int pageNum) {
    List<Long> ids = new ArrayList<>();
    int size = condition.pageSize();
    int from = (pageNum - 1) * size;
    int to = Math.min(pageNum * size, (int) condition.expectedTotal());
    for (int position = from; position < to; position++) {
      int index = (int) condition.expectedTotal() - 1 - position;
      if (readable(condition, index)) {
        ids.add(baseOf(condition.group()) + index);
      }
    }
    return ids;
  }

  /** 期望可读性：状态闸门优先，其次超管直通，最后按组分布。 */
  static boolean readable(Condition condition, int index) {
    long userId = condition.userId();
    if (userId == FROZEN_USER_ID || userId == LEAVER_USER_ID) {
      return false;
    }
    if (userId == ADMIN_USER_ID) {
      return true;
    }
    switch (condition.group()) {
      case "ALL":
        return true;
      case "PART":
        return index % 2 == 0;
      case "PROBE":
        return index >= 0 && index < PROBE_COUNT && PROBE_READABLE[index];
      default:
        return false;
    }
  }

  static long baseOf(String group) {
    switch (group) {
      case "ALL":
        return ALL_BASE;
      case "PART":
        return PART_BASE;
      case "NONE":
        return NONE_BASE;
      default:
        return PROBE_BASE;
    }
  }

  // ---------------------------------------------------------------- 报告

  private void report(
      String phase,
      int round,
      Condition condition,
      CacheMode cache,
      Aggregate aggregate,
      int requests) {
    long ok = aggregate.okCount();
    double p50 = percentileMillis(aggregate.e2e(), 50.0);
    double p95 = percentileMillis(aggregate.e2e(), 95.0);
    double throughput = 1000.0 / Math.max(0.0001, p50);
    printf(
        "PFLHOT %s: round=%d cond=%s cache=%s requests=%d ok=%d total=%d page1_records=%d "
            + "e2e_p50_ms=%.3f e2e_p95_ms=%.3f e2e_avg_ms=%.3f throughput_req_s=%.1f",
        phase,
        round,
        condition.label(),
        cache,
        requests,
        ok,
        condition.expectedTotal(),
        expectedIds(condition, 1).size(),
        p50,
        p95,
        avgMillis(aggregate.e2e()),
        throughput);
    StringBuilder line = new StringBuilder("PFLHOT " + phase + ":   calls/ms ");
    for (String key : REPORT_KEYS) {
      long calls = aggregate.callsOf(key);
      long nanos =
          key.startsWith("token/") ? aggregate.nanosOf("token/nanos") : aggregate.nanosOf(key);
      if (calls == 0 && nanos == 0) {
        continue;
      }
      line.append(key)
          .append('=')
          .append(avg(calls, ok))
          .append('/')
          .append(ms(nanos, ok))
          .append(' ');
    }
    printf("%s", line.toString().trim());
    long attributable = 0;
    for (String key : LEAF_NANOS_KEYS) {
      attributable += aggregate.nanosOf(key);
    }
    printf(
        "PFLHOT %s:   residual_ms=%.3f (end_to_end_avg minus attributable sql+token)",
        phase, avgMillis(aggregate.e2e()) - ms(attributable, ok));
  }

  private static long avg(long total, long divisor) {
    return total / Math.max(1, divisor);
  }

  private static double ms(long nanos, long divisor) {
    return nanos / 1_000_000.0 / Math.max(1, divisor);
  }

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

  private void printEnvironment(String image) {
    printf(
        "PFLHOT env: evidence_class=%s docker=available mysql_image=%s java=%s",
        "local-real-storage+production-record-auth", image, System.getProperty("java.version"));
    printf(
        "PFLHOT load: page_sizes=%s warmup=%d samples=%d rounds=%d c8_threads=%d "
            + "c8_per_thread=%d group_size=%d probe_rows=%d",
        Arrays.toString(PAGE_SIZES),
        WARMUP,
        SAMPLES,
        ROUNDS,
        C8_THREADS,
        C8_SAMPLES_PER_THREAD,
        GROUP_SIZE,
        PROBE_COUNT);
    printf(
        "PFLHOT identities: admin=%d sales=%d frozen=%d leaver=%d other=%d role_sales=%d "
            + "token_secret=local-16-byte (no real credential read)",
        ADMIN_USER_ID, SALES_USER_ID, FROZEN_USER_ID, LEAVER_USER_ID, OTHER_USER_ID, SALES_ROLE_ID);
    printf("PFLHOT resource: container_resources=unknown (not collected)");
  }

  // ---------------------------------------------------------------- 分页与令牌工具

  private static String tokenOf(String downloadUrl) {
    String marker = "token=";
    int index = downloadUrl.indexOf(marker);
    assertTrue(index > 0, "下载链接必须带 token 参数：" + downloadUrl);
    return downloadUrl.substring(index + marker.length());
  }

  // ---------------------------------------------------------------- 会话

  private static <T> T dispatch(SqlSessionFactory factory, Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (Object proxy, Method method, Object[] args) -> {
              if (method.getName().equals("toString")) {
                return type.getSimpleName() + "@pflhot-dispatch";
              }
              try (SqlSession session = factory.openSession(true)) {
                return method.invoke(session.getMapper(type), args);
              }
            }));
  }

  // ---------------------------------------------------------------- 统计

  /** SQL 归因拦截器：按 MappedStatement id 分类 + 当前 scope 前缀；最内层注册以看见分页 count 查询。 */
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
          CacheKey.class,
          BoundSql.class
        })
  })
  static class SqlAttributionInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
      long start = System.nanoTime();
      try {
        return invocation.proceed();
      } finally {
        Ledger.recordScoped(categorize(statement.getId()), 1, System.nanoTime() - start);
      }
    }

    static String categorize(String statementId) {
      if (statementId.endsWith("_mpCount")) {
        return "pagingCount";
      }
      if (statementId.contains("ProjectFileMapper.selectPage")) {
        return "pagingPage";
      }
      if (statementId.contains("UserMapper.")) {
        return "user";
      }
      if (statementId.contains("PermissionsMapper.")) {
        return "permission";
      }
      if (statementId.contains("BusinessActivityUserMapper.")) {
        return "activityUser";
      }
      if (statementId.contains("BusinessActivityMapper.")) {
        return "activity";
      }
      if (statementId.contains("SalesOpportunityMapper.")) {
        return "opportunity";
      }
      if (statementId.contains("ContractOrderItemMapper.")) {
        return "orderItem";
      }
      if (statementId.contains("ContractMapper.")) {
        return "contract";
      }
      return "other";
    }
  }

  /** 请求级账本：ThreadLocal scope 前缀 + 分类调用次数/耗时；不跨请求保留任何业务数据。 */
  static final class Ledger {

    private static final ThreadLocal<Ledger> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    private final Map<String, long[]> entries = new LinkedHashMap<>();

    static void begin() {
      CURRENT.set(new Ledger());
    }

    static void end() {
      CURRENT.remove();
      SCOPE.remove();
    }

    static Ledger current() {
      return CURRENT.get();
    }

    static String currentScope() {
      return SCOPE.get();
    }

    static String pushScope(String scope) {
      String previous = SCOPE.get();
      SCOPE.set(scope);
      return previous;
    }

    static void popScope(String previous) {
      if (previous == null) {
        SCOPE.remove();
      } else {
        SCOPE.set(previous);
      }
    }

    /** SQL 归因：键自动带当前 scope 前缀（无 scope 记 req/）。 */
    static void recordScoped(String baseKey, long calls, long nanos) {
      String scope = SCOPE.get();
      record(scope == null ? "req/" + baseKey : scope + "/" + baseKey, calls, nanos);
    }

    static void record(String key, long calls, long nanos) {
      Ledger ledger = CURRENT.get();
      if (ledger != null) {
        ledger.add(key, calls, nanos);
      }
    }

    private void add(String key, long calls, long nanos) {
      long[] slot = entries.computeIfAbsent(key, ignored -> new long[2]);
      slot[0] += calls;
      slot[1] += nanos;
    }

    static Map<String, long[]> snapshot() {
      Ledger ledger = CURRENT.get();
      Map<String, long[]> copy = new LinkedHashMap<>();
      if (ledger != null) {
        ledger.entries.forEach((key, value) -> copy.put(key, new long[] {value[0], value[1]}));
      }
      return copy;
    }
  }

  /** 多请求聚合：分类总和 + 端到端样本。 */
  static final class Aggregate {

    private final Map<String, LongAdder> calls = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> nanos = new ConcurrentHashMap<>();
    private final List<Long> e2e = Collections.synchronizedList(new ArrayList<>());

    void add(Map<String, long[]> snapshot, long endToEndNanos) {
      snapshot.forEach(
          (key, value) -> {
            calls.computeIfAbsent(key, ignored -> new LongAdder()).add(value[0]);
            nanos.computeIfAbsent(key, ignored -> new LongAdder()).add(value[1]);
          });
      e2e.add(endToEndNanos);
    }

    long callsOf(String key) {
      LongAdder adder = calls.get(key);
      return adder == null ? 0 : adder.sum();
    }

    long nanosOf(String key) {
      LongAdder adder = nanos.get(key);
      return adder == null ? 0 : adder.sum();
    }

    long okCount() {
      return e2e.size();
    }

    List<Long> e2e() {
      return e2e;
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
              "pflhot-resource-sampler");
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
      return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
  }

  // ---------------------------------------------------------------- 生产组件的计时/计数壳（判定不变）

  /** 生产记录级鉴权的计时壳：只包 scope 与计数，判定完全走 {@code super}。 */
  static final class TimedAccessService extends AttachmentAccessServiceImpl {

    TimedAccessService(
        AssistRequestMapper assistRequestMapper,
        BusinessActivityMapper businessActivityMapper,
        BusinessActivityUserMapper businessActivityUserMapper,
        ContactTaskMapper contactTaskMapper,
        SalesOpportunityMapper salesOpportunityMapper,
        ContractMapper contractMapper,
        ContractOrderItemMapper contractOrderItemMapper,
        SalesStageApprovalMapper salesStageApprovalMapper,
        UserMapper userMapper,
        PermissionService permissionService,
        ObjectMapper objectMapper) {
      super(
          assistRequestMapper,
          businessActivityMapper,
          businessActivityUserMapper,
          contactTaskMapper,
          salesOpportunityMapper,
          contractMapper,
          contractOrderItemMapper,
          salesStageApprovalMapper,
          userMapper,
          permissionService,
          objectMapper);
    }

    @Override
    public boolean canReadProjectFile(ProjectFileEntity file, Long userId) {
      String previous = Ledger.pushScope("auth");
      long start = System.nanoTime();
      try {
        return super.canReadProjectFile(file, userId);
      } finally {
        Ledger.record("auth/calls", 1, System.nanoTime() - start);
        Ledger.popScope(previous);
      }
    }
  }

  /** 生产名称转换的缓存壳：复现 {@code @Cacheable("userName")} 的冷/暖两态，不改查询语义。 */
  static final class CachedNameConvertService extends DataConvertServiceImpl {

    private final Map<Long, String> cache = new ConcurrentHashMap<>();
    private volatile boolean warm;

    CachedNameConvertService(
        UserMapper userMapper,
        CustomerCompanyMapper customerCompanyMapper,
        CustomerContactMapper customerContactMapper,
        SalesOpportunityMapper salesOpportunityMapper,
        ContractMapper contractMapper,
        SysDeptMapper sysDeptMapper) {
      super(
          userMapper,
          customerCompanyMapper,
          customerContactMapper,
          salesOpportunityMapper,
          contractMapper,
          sysDeptMapper);
    }

    void setWarm(boolean warm) {
      this.warm = warm;
      clearCache();
    }

    void clearCache() {
      cache.clear();
    }

    void prewarm(long[] userIds) {
      for (long userId : userIds) {
        cache.put(userId, super.getUserName(userId));
      }
    }

    @Override
    public String getUserName(Long userId) {
      String previous = Ledger.pushScope("name");
      long start = System.nanoTime();
      try {
        if (!warm) {
          return super.getUserName(userId);
        }
        String cached = cache.get(userId);
        if (cached != null) {
          return cached;
        }
        String loaded = super.getUserName(userId);
        if (loaded != null) {
          cache.put(userId, loaded);
        }
        return loaded;
      } finally {
        Ledger.record("name/calls", 1, System.nanoTime() - start);
        Ledger.popScope(previous);
      }
    }
  }

  /** 生产令牌签发的计数壳：不改密文形状与资格判定。 */
  static final class CountingTokenUtil extends AttachmentDownloadTokenUtil {

    CountingTokenUtil(String secretKey) {
      super(secretKey);
    }

    @Override
    public String generateDownloadToken(Long attachmentId, Long userId, String fileType) {
      long start = System.nanoTime();
      try {
        return super.generateDownloadToken(attachmentId, userId, fileType);
      } finally {
        long nanos = System.nanoTime() - start;
        Ledger.record("token/calls", 1, nanos);
        Ledger.record("token/nanos", 1, nanos);
      }
    }
  }

  // ---------------------------------------------------------------- 数据形状

  /** 条件：组 + 请求用户 + 页大小 + 期望数据库总数（主题过滤由组派生）。 */
  record Condition(String label, String group, long userId, int pageSize, long expectedTotal) {

    String theme() {
      switch (group) {
        case "ALL":
          return "ALL#";
        case "PART":
          return "PART#";
        case "NONE":
          return "NONE#";
        default:
          return "PROBE#";
      }
    }
  }

  enum CacheMode {
    COLD,
    WARM
  }

  record Environment(
      ProjectFileServiceImpl service,
      AttachmentAccessServiceImpl access,
      CachedNameConvertService nameService,
      AttachmentDownloadTokenUtil rawTokenUtil,
      CountingTokenUtil tokenUtil,
      String jdbcUrl,
      String jdbcUser,
      String jdbcPassword,
      String mysqlImage) {

    Connection openConnection() throws SQLException {
      return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
    }
  }

  private static void printf(String format, Object... args) {
    System.out.println(String.format(java.util.Locale.ROOT, format, args));
  }
}
