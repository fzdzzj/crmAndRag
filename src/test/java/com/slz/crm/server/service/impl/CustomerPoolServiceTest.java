package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.CustomerPoolAssignDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerPoolLogEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.constant.CustomerPoolConstant;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerPoolLogMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 客户公海池业务服务单测（add-customer-public-sea-pool 任务 6.1）。
 *
 * <p>覆盖认领（含私海 50 配额满额拦截）、主动退回（负责人/主管权限分支）、主管分派（权限与目标配额）、流转日志查询。 通过 {@code BaseUnit.setCurrentRole}
 * 驱动当前用户，mock mapper 隔离数据库；语义向量 hook 依赖未装配时整体跳过（不阻断业务流）。
 */
@DisplayName("客户公海池业务服务")
@ExtendWith(MockitoExtension.class)
class CustomerPoolServiceTest {

  @Mock private CustomerCompanyMapper customerCompanyMapper;
  @Mock private CustomerPoolLogMapper customerPoolLogMapper;
  @Mock private UserMapper userMapper;
  @Mock private SysDeptMapper sysDeptMapper;

  @InjectMocks private CustomerPoolServiceImpl service;

  private static final long OPERATOR_ID = 1001L;

  @BeforeEach
  void setUp() {
    RoleAO current = new RoleAO();
    current.setId(OPERATOR_ID);
    current.setRoleId(5L); // 普通业务员
    BaseUnit.setCurrentRole(current);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  private CustomerCompanyEntity privateCompany(Long id, Long ownerId) {
    CustomerCompanyEntity c = new CustomerCompanyEntity();
    c.setId(id);
    c.setIsDeleted(false);
    c.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PRIVATE);
    c.setOwnerId(ownerId);
    return c;
  }

  private CustomerCompanyEntity publicCompany(Long id) {
    CustomerCompanyEntity c = new CustomerCompanyEntity();
    c.setId(id);
    c.setIsDeleted(false);
    c.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PUBLIC);
    c.setOwnerId(null);
    return c;
  }

  @Test
  @DisplayName("认领：配额未满合法认领，更新状态与负责人并插入 CLAIM 日志")
  void claim_happyPath_updatesPoolAndLogsClaim() {
    CustomerCompanyEntity company = publicCompany(5001L);
    when(customerCompanyMapper.selectById(5001L)).thenReturn(company);
    when(customerCompanyMapper.selectCount(any())).thenReturn(10L);
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    assertTrue(service.claim(5001L));

    ArgumentCaptor<CustomerCompanyEntity> update =
        ArgumentCaptor.forClass(CustomerCompanyEntity.class);
    verify(customerCompanyMapper).updateById(update.capture());
    assertEquals(CustomerPoolConstant.POOL_STATUS_PRIVATE, update.getValue().getPoolStatus());
    assertEquals(Long.valueOf(OPERATOR_ID), update.getValue().getOwnerId());

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(CustomerPoolConstant.ACTION_CLAIM, log.getValue().getActionType());
    assertEquals(Long.valueOf(OPERATOR_ID), log.getValue().getNewOwnerId());
  }

  @Test
  @DisplayName("认领：私海保有量满 50 抛配额异常并不写库")
  void claim_quotaExceeded_throws() {
    when(customerCompanyMapper.selectCount(any())).thenReturn(50L);

    BaseException ex = assertThrows(BaseException.class, () -> service.claim(5001L));
    assertEquals(ErrorCode.CUSTOMER_POOL_QUOTA_EXCEEDED.getCode(), ex.getCode());
    verify(customerCompanyMapper, never()).updateById(any());
    verify(customerPoolLogMapper, never()).insert(any());
  }

  @Test
  @DisplayName("认领：客户不在公海（私海）时拒绝")
  void claim_notOnPublicPool_rejected() {
    when(customerCompanyMapper.selectCount(any())).thenReturn(5L);
    when(customerCompanyMapper.selectById(5001L)).thenReturn(privateCompany(5001L, 2002L));

    BaseException ex = assertThrows(BaseException.class, () -> service.claim(5001L));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
    verify(customerPoolLogMapper, never()).insert(any());
  }

  @Test
  @DisplayName("退回：负责人可退回并插入 RELEASE 日志")
  void release_byOwner_updatesAndLogsRelease() {
    CustomerCompanyEntity company = privateCompany(6001L, OPERATOR_ID);
    when(customerCompanyMapper.selectById(6001L)).thenReturn(company);
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    assertTrue(service.release(6001L, "客户不匹配"));

    ArgumentCaptor<CustomerCompanyEntity> update =
        ArgumentCaptor.forClass(CustomerCompanyEntity.class);
    verify(customerCompanyMapper).updateById(update.capture());
    assertEquals(CustomerPoolConstant.POOL_STATUS_PUBLIC, update.getValue().getPoolStatus());
    assertTrue(update.getValue().getOwnerId() == null);
    assertEquals("客户不匹配", update.getValue().getPoolReleaseReason());

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(CustomerPoolConstant.ACTION_RELEASE, log.getValue().getActionType());
    assertEquals(Long.valueOf(OPERATOR_ID), log.getValue().getOperatorId());
  }

  @Test
  @DisplayName("退回：非负责人且非主管拒绝")
  void release_notOwnerNorManager_rejected() {
    // 当前用户非负责人（owner 是别人），且非部门 leader
    CustomerCompanyEntity company = privateCompany(6002L, 9999L);
    when(customerCompanyMapper.selectById(6002L)).thenReturn(company);
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(5L));
    when(sysDeptMapper.selectCount(any())).thenReturn(0L);

    BaseException ex = assertThrows(BaseException.class, () -> service.release(6002L, "x"));
    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
    verify(customerCompanyMapper, never()).updateById(any());
    verify(customerPoolLogMapper, never()).insert(any());
  }

  @Test
  @DisplayName("退回：超管（roleId=1）可退回他人客户")
  void release_superAdminByPassesOwner() {
    CustomerCompanyEntity company = privateCompany(6003L, 9999L);
    when(customerCompanyMapper.selectById(6003L)).thenReturn(company);
    when(customerPoolLogMapper.insert(any())).thenReturn(1);
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(1L));

    assertTrue(service.release(6003L, "离职划转"));

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(Long.valueOf(9999L), log.getValue().getPreviousOwnerId());
  }

  @Test
  @DisplayName("分派：非主管拒绝")
  void assign_notManager_rejected() {
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(5L));
    when(sysDeptMapper.selectCount(any())).thenReturn(0L);

    CustomerPoolAssignDTO dto = new CustomerPoolAssignDTO();
    dto.setCompanyId(7001L);
    dto.setTargetUserId(8888L);

    BaseException ex = assertThrows(BaseException.class, () -> service.assign(dto));
    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
    verify(customerCompanyMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("分派：主管合法分派，校验目标配额后插入 ASSIGN 日志")
  void assign_managerHappyPath_updatesAndLogsAssign() {
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(1L));
    when(customerCompanyMapper.selectCount(any())).thenReturn(5L); // 目标配额未满
    CustomerCompanyEntity company = publicCompany(7002L);
    when(customerCompanyMapper.selectById(7002L)).thenReturn(company);
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    CustomerPoolAssignDTO dto = new CustomerPoolAssignDTO();
    dto.setCompanyId(7002L);
    dto.setTargetUserId(8888L);
    dto.setReason("重点客户跟进");

    assertTrue(service.assign(dto));

    ArgumentCaptor<CustomerCompanyEntity> update =
        ArgumentCaptor.forClass(CustomerCompanyEntity.class);
    verify(customerCompanyMapper).updateById(update.capture());
    assertEquals(CustomerPoolConstant.POOL_STATUS_PRIVATE, update.getValue().getPoolStatus());
    assertEquals(Long.valueOf(8888L), update.getValue().getOwnerId());

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(CustomerPoolConstant.ACTION_ASSIGN, log.getValue().getActionType());
    assertEquals(Long.valueOf(8888L), log.getValue().getNewOwnerId());
    assertEquals(Long.valueOf(OPERATOR_ID), log.getValue().getOperatorId());
  }

  @Test
  @DisplayName("流转日志查询：返回按时间倒序的全生命周期记录")
  void queryLogs_returnsTimeLine() {
    CustomerPoolLogEntity claim = new CustomerPoolLogEntity();
    claim.setActionType(CustomerPoolConstant.ACTION_CLAIM);
    claim.setCompanyId(7002L);
    claim.setOperatorId(OPERATOR_ID);
    CustomerPoolLogEntity release = new CustomerPoolLogEntity();
    release.setActionType(CustomerPoolConstant.ACTION_RELEASE);
    release.setCompanyId(7002L);
    release.setOperatorId(OPERATOR_ID);
    when(customerPoolLogMapper.selectList(any())).thenReturn(List.of(release, claim));
    when(userMapper.selectById(anyLong())).thenReturn(null); // operator 用户缺失则姓名 null

    var logs = service.queryLogs(7002L);

    assertEquals(2, logs.size());
    assertEquals(CustomerPoolConstant.ACTION_CLAIM, logs.get(1).getActionType());
    assertEquals(CustomerPoolConstant.ACTION_RELEASE, logs.get(0).getActionType());
  }

  private UserEntity userWithRole(Long roleId) {
    UserEntity u = new UserEntity();
    u.setId(OPERATOR_ID);
    u.setRoleId(roleId);
    return u;
  }
}
