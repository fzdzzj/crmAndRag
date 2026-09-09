package com.slz.crm.integration.controller;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.CompanyDeptDTO;
import com.slz.crm.pojo.dto.CompanyGroupDTO;
import com.slz.crm.server.service.CompanyDeptService;
import com.slz.crm.server.service.CompanyGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织架构删除一致性集成测试：
 * 含部门集团删除被业务层拒绝，数据库外键 RESTRICT 兜底并发场景，部门改组唯一冲突拦截。
 */
@AutoConfigureMockMvc
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CompanyGroupDeleteIT extends AbstractMySqlIT {

    @Autowired
    private CompanyGroupService companyGroupService;

    @Autowired
    private CompanyDeptService companyDeptService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM company_dept WHERE dept_name LIKE 'UNIQ_IT_%'");
        jdbcTemplate.update("DELETE FROM company_group WHERE group_name LIKE 'UNIQ_IT_%'");
        BaseUnit.removeCurrentId();
    }

    private void authenticateAs(Long userId) {
        RoleAO role = new RoleAO();
        role.setId(userId);
        BaseUnit.setCurrentRole(role);
    }

    @Test
    @DisplayName("含部门的集团删除被业务层拒绝")
    void deleteGroupWithDeptRejectedByBusiness() {
        authenticateAs(1L);
        CompanyGroupDTO group = new CompanyGroupDTO();
        group.setGroupName("UNIQ_IT_集团A");
        Long groupId = companyGroupService.add(group).getId();

        CompanyDeptDTO dept = new CompanyDeptDTO();
        dept.setGroupId(groupId);
        dept.setDeptName("UNIQ_IT_研发部");
        companyDeptService.add(dept);

        BaseException ex = assertThrows(BaseException.class,
                () -> companyGroupService.deleteById(groupId));
        assertTrue(ex.getMessage().contains("存在部门"));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company_group WHERE id = ?", Long.class, groupId);
        assertTrue(count > 0, "业务层拒绝后集团应保留");
    }

    @Test
    @DisplayName("数据库外键 RESTRICT：绕过业务层直接删除含部门集团被拒绝")
    void databaseForeignKeyRestrictsGroupDeletion() {
        authenticateAs(1L);
        CompanyGroupDTO group = new CompanyGroupDTO();
        group.setGroupName("UNIQ_IT_集团B");
        Long groupId = companyGroupService.add(group).getId();

        CompanyDeptDTO dept = new CompanyDeptDTO();
        dept.setGroupId(groupId);
        dept.setDeptName("UNIQ_IT_市场部");
        companyDeptService.add(dept);

        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM company_group WHERE id = ?", groupId));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company_group WHERE id = ?", Long.class, groupId);
        assertTrue(count > 0, "外键 RESTRICT 应阻止删除含部门的集团");
    }

    @Test
    @DisplayName("部门改组到已存在的同集团部门名被唯一约束拦截")
    void deptRenameToExistingNameRejected() {
        authenticateAs(1L);
        CompanyGroupDTO group = new CompanyGroupDTO();
        group.setGroupName("UNIQ_IT_集团C");
        Long groupId = companyGroupService.add(group).getId();

        CompanyDeptDTO first = new CompanyDeptDTO();
        first.setGroupId(groupId);
        first.setDeptName("UNIQ_IT_财务部");
        companyDeptService.add(first);

        CompanyDeptDTO second = new CompanyDeptDTO();
        second.setGroupId(groupId);
        second.setDeptName("UNIQ_IT_销售部");
        Long secondId = companyDeptService.add(second).getId();

        CompanyDeptDTO rename = new CompanyDeptDTO();
        rename.setId(secondId);
        rename.setGroupId(groupId);
        rename.setDeptName("UNIQ_IT_财务部");
        assertThrows(Exception.class, () -> companyDeptService.update(rename));
    }

    @Test
    @DisplayName("空集团删除与部门新增并发时外键兜底，任何交错都不产生孤儿部门")
    void concurrentDeptAddAndGroupDeleteNeverOrphans() throws Exception {
        authenticateAs(1L);
        CompanyGroupDTO group = new CompanyGroupDTO();
        group.setGroupName("UNIQ_IT_并发集团");
        Long groupId = companyGroupService.add(group).getId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Boolean> deleteFuture = pool.submit(() -> {
            start.await();
            authenticateAs(1L);
            try {
                companyGroupService.deleteById(groupId);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        Future<Boolean> addFuture = pool.submit(() -> {
            start.await();
            authenticateAs(1L);
            try {
                CompanyDeptDTO dept = new CompanyDeptDTO();
                dept.setGroupId(groupId);
                dept.setDeptName("UNIQ_IT_并发部门");
                companyDeptService.add(dept);
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        start.countDown();
        boolean deleted = deleteFuture.get();
        boolean added = addFuture.get();
        pool.shutdown();

        // 业务前置检查通过后唯一可能的交错是部门插入撞上集团删除，
        // 外键必须保证二者不能同时成功，否则就是孤儿部门。
        assertTrue(!(deleted && added), "集团删除与部门新增不能同时成功");
        Integer orphan = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company_dept d LEFT JOIN company_group g ON d.group_id = g.id "
                        + "WHERE g.id IS NULL AND d.dept_name LIKE 'UNIQ_IT_%'", Integer.class);
        assertEquals(0, orphan, "任何交错下都不允许出现孤儿部门");
        Integer deptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company_dept WHERE group_id = ?", Integer.class, groupId);
        if (deleted) {
            assertEquals(0, deptCount, "集团删除成功时部门插入必须被外键拒绝");
        } else {
            assertEquals(1, deptCount, "集团删除失败时部门应正常落库");
        }
    }
}