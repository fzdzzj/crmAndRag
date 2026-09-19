package com.slz.crm.integration.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.server.service.CustomerCompanyService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/** 客户公司"公司名+部门"唯一约束集成测试： 并发新增由数据库唯一索引兜底，软删后重建允许、恢复冲突被拦截。 */
@AutoConfigureMockMvc
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerCompanyDeptUniqueIT extends AbstractMySqlIT {

  @Autowired private CustomerCompanyService customerCompanyService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanupTestData() {
    jdbcTemplate.update("DELETE FROM customer_company WHERE company_name LIKE 'UNIQ_IT_%'");
    BaseUnit.removeCurrentId();
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }

  private CustomerCompanyDTO companyDto(String companyName, String dept) {
    CustomerCompanyDTO dto = new CustomerCompanyDTO();
    dto.setCompanyName(companyName);
    dto.setDept(dept);
    return dto;
  }

  @Test
  @DisplayName("并发新增同公司同部门时数据库唯一约束兜底，只有一个成功")
  void concurrentCreateSameCompanyDeptAllowsOnlyOne() throws Exception {
    authenticateAs(1L);
    int threadCount = 2;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    List<Future<Boolean>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                authenticateAs(1L);
                try {
                  customerCompanyService.add(companyDto("UNIQ_IT_并发公司", "研发部"));
                  return true;
                } catch (BaseException e) {
                  return false;
                }
              }));
    }
    start.countDown();
    int success = 0;
    for (Future<Boolean> future : futures) {
      if (future.get()) {
        success++;
      }
    }
    pool.shutdown();
    assertEquals(1, success, "并发同公司同部门应只有一个新增成功");
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM customer_company WHERE company_name = 'UNIQ_IT_并发公司' AND is_deleted = 0",
            Integer.class);
    assertEquals(1, count, "正常记录中应只有一条同公司同部门数据");
  }

  @Test
  @DisplayName("软删后可重建同公司同部门，但恢复原记录被唯一约束拦截")
  void softDeletedCanRecreateButRestoreConflicts() {
    authenticateAs(1L);
    assertNotNull(customerCompanyService.add(companyDto("UNIQ_IT_恢复公司", "市场部")));
    Long originalId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM customer_company WHERE company_name = 'UNIQ_IT_恢复公司' AND is_deleted = 0",
            Long.class);

    // 软删（deleteOrRecoverByIds 参数语义：true=软删、false=恢复）后生成列唯一键失效，可再建同公司同部门
    customerCompanyService.deleteOrRecoverByIds(List.of(originalId), true);
    assertNotNull(customerCompanyService.add(companyDto("UNIQ_IT_恢复公司", "市场部")));

    // 恢复原记录：正常表中已存在同公司同部门，判重/数据库唯一约束拦截
    BaseException ex =
        assertThrows(
            BaseException.class,
            () -> customerCompanyService.deleteOrRecoverByIds(List.of(originalId), false));
    assertTrue(ex.getMessage().contains("恢复失败") || ex.getMessage().contains("已存在"));
  }
}
