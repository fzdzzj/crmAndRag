package com.slz.crm.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.server.service.CustomerPoolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * release 置空真库回环（harden-test-backlog-batch1 ②）。
 *
 * <p>缺陷口径：实体默认 {@code NOT_NULL} 更新策略下用 {@code updateById} 把 {@code owner_id} 置 null 是空操作，
 * 退回公海会留下「公海状态但仍有负责人」的脏行。生产已改显式 {@code UpdateWrapper.set(ownerId, null)}，本 IT 在真实 MySQL 上锁定 「置空确实落库
 * + 行仍可见 + 字段值成组正确」，补上 {@code CustomerPoolServiceTest} 用 mock 无法证明的持久化缺口。
 *
 * <p>seed：每个用例在事务内插入一条自有客户行（owner=本线程登录人，pool_status=0 私海），release 后逐列读真库； 事务回滚，故对共享容器零残留。
 */
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerPoolReleaseRoundTripIT extends AbstractMySqlIT {

  /** 真库 init_data.sql 的 sys_user 只种 id 1/2/3：owner/creator 外键必须落在真实用户行上。 */
  private static final long OWNER_ID = 2L;

  private static final long COMPANY_ID = 9_001L;
  private static final String COMPANY_NAME = "RELEASE-IT-回环客户";

  @Autowired private CustomerPoolService customerPoolService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedPrivatePoolCompany() {
    RoleAO current = new RoleAO();
    current.setId(OWNER_ID);
    BaseUnit.setCurrentRole(current);
    jdbcTemplate.update(
        "INSERT INTO customer_company (id, company_name, creator_id, owner_id, is_deleted,"
            + " pool_status, grade) VALUES (?, ?, ?, ?, 0, 0, 1)",
        COMPANY_ID,
        COMPANY_NAME,
        OWNER_ID,
        OWNER_ID);
  }

  @AfterEach
  void clearLoginContext() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("release 显式置空 owner：真库已 NULL、公海字段成组落库、行仍可见、RELEASE 日志成对")
  void releasePersistsOwnerNulling() {
    // seed 自检：置空前负责人确实存在（否则「置空」可能只是从未写入的假绿）
    assertThat(ownerIdOfCompany())
        .as("seed 前置：客户行必须有负责人，否则置空断言无法区分「清空」与「本就没有」")
        .isEqualTo(OWNER_ID);

    assertThat(customerPoolService.release(COMPANY_ID, "真库回环验证：主动放弃")).isTrue();

    assertThat(ownerIdOfCompany())
        .as("release 必须把 owner_id 真正写成 NULL（NOT_NULL 策略下 updateById 置 null 会静默不落库）")
        .isNull();
    assertThat(queryInteger("SELECT pool_status FROM customer_company WHERE id = ?"))
        .as("release 必须把 pool_status 置为公海(1)")
        .isEqualTo(1);
    assertThat(
            queryInteger(
                "SELECT COUNT(*) FROM customer_company WHERE id = ? AND pool_release_time IS NOT NULL"))
        .as("pool_release_time 必须随 release 落库")
        .isEqualTo(1);
    assertThat(queryString("SELECT pool_release_reason FROM customer_company WHERE id = ?"))
        .as("pool_release_reason 必须保留调用方传入的原因")
        .isEqualTo("真库回环验证：主动放弃");
    assertThat(
            queryInteger(
                "SELECT COUNT(*) FROM customer_company WHERE id = ? AND is_deleted = 0 AND owner_id IS NULL"))
        .as("置空后行必须仍可见（软删回环：不删行、只清负责人）")
        .isEqualTo(1);

    assertReleaseLog("真库回环验证：主动放弃");
  }

  @Test
  @DisplayName("release 空白原因回退默认文案：owner 同样置空，reason 落「销售主动放弃」")
  void releaseWithBlankReasonFallsBackToDefault() {
    assertThat(customerPoolService.release(COMPANY_ID, "   ")).isTrue();

    assertThat(ownerIdOfCompany()).as("空白原因不得影响 owner 置空").isNull();
    assertThat(queryString("SELECT pool_release_reason FROM customer_company WHERE id = ?"))
        .as("空白原因必须回退到默认退回原因")
        .isEqualTo("销售主动放弃");
    assertReleaseLog("销售主动放弃");
  }

  @Test
  @DisplayName("置空后公海可见性：queryPool 能查到该行且 VO 负责人为 null")
  void releasedCompanyVisibleInPublicPool() {
    assertThat(customerPoolService.release(COMPANY_ID, "可见性验证")).isTrue();

    Page<CustomerCompanyVO> pool = customerPoolService.queryPool(1, 200, COMPANY_NAME, null, null);

    assertThat(pool.getRecords())
        .as("退回公海的客户必须出现在公海分页（pool_status=1 AND is_deleted=0）")
        .anySatisfy(
            vo -> {
              assertThat(vo.getId()).isEqualTo(COMPANY_ID);
              assertThat(vo.getOwnerId()).as("公海行 VO 不得再带负责人").isNull();
            });
  }

  private Long ownerIdOfCompany() {
    return jdbcTemplate.queryForObject(
        "SELECT owner_id FROM customer_company WHERE id = ?", Long.class, COMPANY_ID);
  }

  private Integer queryInteger(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class, COMPANY_ID);
  }

  private String queryString(String sql) {
    return jdbcTemplate.queryForObject(sql, String.class, COMPANY_ID);
  }

  /** RELEASE 日志必须成对记录「前一负责人 → null」，证明置空是同一次流转而非后续覆盖。 */
  private void assertReleaseLog(String expectedReason) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT action_type FROM customer_pool_log WHERE company_id = ?",
                String.class,
                COMPANY_ID))
        .as("release 必须落一条 RELEASE 流转日志")
        .isEqualTo("RELEASE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT operator_id FROM customer_pool_log WHERE company_id = ?",
                Long.class,
                COMPANY_ID))
        .as("日志操作人必须是当前登录人")
        .isEqualTo(OWNER_ID);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT previous_owner_id FROM customer_pool_log WHERE company_id = ?",
                Long.class,
                COMPANY_ID))
        .as("日志必须记录置空前的负责人")
        .isEqualTo(OWNER_ID);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT new_owner_id FROM customer_pool_log WHERE company_id = ?",
                Long.class,
                COMPANY_ID))
        .as("日志 new_owner_id 必须为空（退回公海后无人负责）")
        .isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reason FROM customer_pool_log WHERE company_id = ?",
                String.class,
                COMPANY_ID))
        .as("日志原因必须与客户行一致")
        .isEqualTo(expectedReason);
  }
}
