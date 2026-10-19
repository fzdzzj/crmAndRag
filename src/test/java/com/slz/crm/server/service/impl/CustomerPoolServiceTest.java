package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 客户公海池业务服务单测（add-customer-public-sea-pool 任务 6.1）。
 *
 * <p>覆盖认领（含私海 50 配额满额拦截）、主动退回（负责人/主管权限分支）、主管分派（权限与目标配额）、流转日志查询， 以及并发 CAS
 * 语义（fix-pool-claim-race：条件更新 affected=0 冲突、配额检查先锁用户行再计数）。 通过 {@code BaseUnit.setCurrentRole}
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

  /**
   * 纯 Mockito 测试 JVM 无 MyBatis-Plus 启动流程，配额锁（LambdaQueryWrapper&lt;UserEntity&gt;）与 CAS wrapper 的
   * lambda 列名解析依赖实体 TableInfo 缓存，按 CustomerIngestionServiceTest 先例显式初始化。
   */
  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), CustomerCompanyEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserEntity.class);
  }

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
  @DisplayName("认领：配额未满合法认领，CAS 条件更新置私海并插入 CLAIM 日志")
  void claim_happyPath_updatesPoolAndLogsClaim() {
    CustomerCompanyEntity company = publicCompany(5001L);
    when(customerCompanyMapper.selectById(5001L)).thenReturn(company);
    when(customerCompanyMapper.selectCount(any())).thenReturn(10L);
    when(customerCompanyMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    assertTrue(service.claim(5001L));

    LambdaUpdateWrapper<CustomerCompanyEntity> cas = captureCasUpdate();
    String sqlSet = cas.getSqlSet();
    assertTrue(sqlSet.contains("pool_status="));
    assertTrue(sqlSet.contains("owner_id="));
    assertTrue(sqlSet.contains("pool_release_time="));
    assertTrue(sqlSet.contains("pool_release_reason="));
    String where = cas.getSqlSegment();
    assertTrue(where.contains("id ="));
    assertTrue(where.contains("pool_status ="));
    assertTrue(where.contains("is_deleted ="));
    Map<String, Object> params = cas.getParamNameValuePairs();
    assertTrue(params.containsValue(5001L));
    assertTrue(params.containsValue(Long.valueOf(OPERATOR_ID)));
    assertTrue(params.containsValue(CustomerPoolConstant.POOL_STATUS_PUBLIC));

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(CustomerPoolConstant.ACTION_CLAIM, log.getValue().getActionType());
    assertEquals(Long.valueOf(OPERATOR_ID), log.getValue().getNewOwnerId());
  }

  /** 捕获认领/分派 CAS 条件更新的 wrapper 参数（update(null, wrapper) 单参形态）。 */
  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<CustomerCompanyEntity> captureCasUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<CustomerCompanyEntity>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(customerCompanyMapper).update(isNull(), captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("认领：私海保有量满 50 抛配额异常并不写库")
  void claim_quotaExceeded_throws() {
    when(customerCompanyMapper.selectCount(any())).thenReturn(50L);

    BaseException ex = assertThrows(BaseException.class, () -> service.claim(5001L));
    assertEquals(ErrorCode.CUSTOMER_POOL_QUOTA_EXCEEDED.getCode(), ex.getCode());
    verify(customerCompanyMapper, never()).updateById(any());
    verify(customerCompanyMapper, never()).update(any(), any());
    verifyNoInteractions(customerPoolLogMapper);
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
  @DisplayName("认领：CAS affected=0（并发被抢）抛状态变更错误且零副作用")
  void claim_casConflict_throwsWithNoSideEffects() {
    when(customerCompanyMapper.selectCount(any())).thenReturn(10L);
    when(customerCompanyMapper.selectById(5001L)).thenReturn(publicCompany(5001L));
    when(customerCompanyMapper.update(any(), any())).thenReturn(0); // 并发先到者已抢走

    BaseException ex = assertThrows(BaseException.class, () -> service.claim(5001L));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
    assertTrue(ex.getMessage().contains("已被他人认领"));
    verifyNoInteractions(customerPoolLogMapper);
    verify(customerCompanyMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("配额临界并发：先锁用户行再计数（同用户串行化），满 50 拒绝且零写库")
  void claim_quotaCritical_locksUserRowBeforeCount() {
    when(customerCompanyMapper.selectCount(any())).thenReturn(50L); // 先到者提交后重新计数已满

    BaseException ex = assertThrows(BaseException.class, () -> service.claim(5001L));
    assertEquals(ErrorCode.CUSTOMER_POOL_QUOTA_EXCEEDED.getCode(), ex.getCode());

    ArgumentCaptor<LambdaQueryWrapper<UserEntity>> lock =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(userMapper).selectList(lock.capture());
    assertTrue(lock.getValue().getSqlSegment().contains("FOR UPDATE"));
    InOrder inOrder = inOrder(userMapper, customerCompanyMapper);
    inOrder.verify(userMapper).selectList(any());
    inOrder.verify(customerCompanyMapper).selectCount(any());
    verify(customerCompanyMapper, never()).selectById(any());
    verify(customerCompanyMapper, never()).update(any(), any());
    verifyNoInteractions(customerPoolLogMapper);
  }

  @Test
  @DisplayName("退回：负责人可退回，显式 UpdateWrapper 真清空 owner_id 并插入 RELEASE 日志")
  void release_byOwner_updatesAndLogsRelease() {
    CustomerCompanyEntity company = privateCompany(6001L, OPERATOR_ID);
    when(customerCompanyMapper.selectById(6001L)).thenReturn(company);
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    assertTrue(service.release(6001L, "客户不匹配"));

    // fix-minor-debt-family 任务 5.1：实体 NOT_NULL 策略下 updateById 置空是空操作，必须走显式 set null
    LambdaUpdateWrapper<CustomerCompanyEntity> wrapper = captureCasUpdate();
    String sqlSet = wrapper.getSqlSet();
    assertTrue(sqlSet.contains("pool_status="));
    assertTrue(sqlSet.contains("owner_id="));
    assertTrue(sqlSet.contains("pool_release_time="));
    assertTrue(sqlSet.contains("pool_release_reason="));
    // SET 子句绑定参数里必须出现 null，证明 owner_id 的置空真的进了 SQL（而非被 NOT_NULL 策略丢弃）
    assertTrue(wrapper.getParamNameValuePairs().containsValue(null));
    assertTrue(wrapper.getSqlSegment().contains("id ="));
    assertTrue(wrapper.getParamNameValuePairs().containsValue(6001L));
    assertTrue(wrapper.getParamNameValuePairs().containsValue("客户不匹配"));
    verify(customerCompanyMapper, never()).updateById(any());

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(CustomerPoolConstant.ACTION_RELEASE, log.getValue().getActionType());
    assertEquals(Long.valueOf(OPERATOR_ID), log.getValue().getOperatorId());
    assertEquals("客户不匹配", log.getValue().getReason());
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
    verify(customerCompanyMapper, never()).update(any(), any());
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
  @DisplayName("分派：主管合法分派，CAS 条件更新置私海并插入 ASSIGN 日志")
  void assign_managerHappyPath_updatesAndLogsAssign() {
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(1L));
    when(userMapper.selectById(8888L)).thenReturn(activeUser(8888L, 5L));
    when(customerCompanyMapper.selectCount(any())).thenReturn(5L); // 目标配额未满
    CustomerCompanyEntity company = publicCompany(7002L);
    when(customerCompanyMapper.selectById(7002L)).thenReturn(company);
    when(customerCompanyMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
    when(customerPoolLogMapper.insert(any())).thenReturn(1);

    CustomerPoolAssignDTO dto = new CustomerPoolAssignDTO();
    dto.setCompanyId(7002L);
    dto.setTargetUserId(8888L);
    dto.setReason("重点客户跟进");

    assertTrue(service.assign(dto));

    // 目标用户存在性/在职校验必须先于配额行锁（fix-minor-debt-family 任务 4.1）
    InOrder order = inOrder(userMapper);
    order.verify(userMapper).selectById(8888L);
    order.verify(userMapper).selectList(any());

    LambdaUpdateWrapper<CustomerCompanyEntity> cas = captureCasUpdate();
    String sqlSet = cas.getSqlSet();
    assertTrue(sqlSet.contains("pool_status="));
    assertTrue(sqlSet.contains("owner_id="));
    String where = cas.getSqlSegment();
    assertTrue(where.contains("id ="));
    assertTrue(where.contains("pool_status ="));
    assertTrue(where.contains("is_deleted ="));
    Map<String, Object> params = cas.getParamNameValuePairs();
    assertTrue(params.containsValue(7002L));
    assertTrue(params.containsValue(Long.valueOf(8888L)));

    ArgumentCaptor<CustomerPoolLogEntity> log =
        ArgumentCaptor.forClass(CustomerPoolLogEntity.class);
    verify(customerPoolLogMapper).insert(log.capture());
    assertEquals(CustomerPoolConstant.ACTION_ASSIGN, log.getValue().getActionType());
    assertEquals(Long.valueOf(8888L), log.getValue().getNewOwnerId());
    assertEquals(Long.valueOf(OPERATOR_ID), log.getValue().getOperatorId());
  }

  @Test
  @DisplayName("分派：CAS affected=0（并发被抢）抛状态变更错误且零副作用")
  void assign_casConflict_throwsWithNoSideEffects() {
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(1L));
    when(userMapper.selectById(8888L)).thenReturn(activeUser(8888L, 5L));
    when(customerCompanyMapper.selectCount(any())).thenReturn(10L);
    when(customerCompanyMapper.selectById(7002L)).thenReturn(publicCompany(7002L));
    when(customerCompanyMapper.update(any(), any())).thenReturn(0); // 并发先到者已抢走

    CustomerPoolAssignDTO dto = new CustomerPoolAssignDTO();
    dto.setCompanyId(7002L);
    dto.setTargetUserId(8888L);

    BaseException ex = assertThrows(BaseException.class, () -> service.assign(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
    assertTrue(ex.getMessage().contains("已被他人认领"));
    verifyNoInteractions(customerPoolLogMapper);
    verify(customerCompanyMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("分派：目标用户不存在时拒绝，且不进入配额行锁（不产生 gap 锁）")
  void assign_targetUserMissing_rejectedBeforeQuotaLock() {
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(1L));
    when(userMapper.selectById(8888L)).thenReturn(null);

    CustomerPoolAssignDTO dto = new CustomerPoolAssignDTO();
    dto.setCompanyId(7001L);
    dto.setTargetUserId(8888L);

    BaseException ex = assertThrows(BaseException.class, () -> service.assign(dto));
    assertEquals(ErrorCode.ID_NOT_EXISTS.getCode(), ex.getCode());
    assertTrue(ex.getMessage().contains("目标业务员"));
    // 存在性校验前置于配额闸：既无 FOR UPDATE 锁，也无计数/写库/日志
    verify(userMapper, never()).selectList(any());
    verify(customerCompanyMapper, never()).selectCount(any());
    verify(customerCompanyMapper, never()).update(any(), any());
    verify(customerCompanyMapper, never()).updateById(any());
    verifyNoInteractions(customerPoolLogMapper);
  }

  @Test
  @DisplayName("分派：目标用户冻结（roleId=0）拒绝，复用 USER_IS_FROZEN 码")
  void assign_targetUserFrozen_rejected() {
    assertAssignTargetRejected(activeUser(8888L, 0L, 0), ErrorCode.USER_IS_FROZEN);
  }

  @Test
  @DisplayName("分派：目标用户离职（roleId=2）拒绝，复用 USER_IS_QUIT 码")
  void assign_targetUserQuit_rejected() {
    assertAssignTargetRejected(activeUser(8888L, 2L, 0), ErrorCode.USER_IS_QUIT);
  }

  @Test
  @DisplayName("分派：目标用户其他非正常状态拒绝，复用 USER_STATUS_EXCEPTION 码")
  void assign_targetUserStatusAbnormal_rejected() {
    assertAssignTargetRejected(activeUser(8888L, 5L, 0), ErrorCode.USER_STATUS_EXCEPTION);
  }

  /** 目标用户非法时统一断言：码透传 + 未进入配额锁与写库。 */
  private void assertAssignTargetRejected(UserEntity target, ErrorCode expected) {
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithRole(1L));
    when(userMapper.selectById(8888L)).thenReturn(target);

    CustomerPoolAssignDTO dto = new CustomerPoolAssignDTO();
    dto.setCompanyId(7001L);
    dto.setTargetUserId(8888L);

    BaseException ex = assertThrows(BaseException.class, () -> service.assign(dto));
    assertEquals(expected.getCode(), ex.getCode());
    verify(userMapper, never()).selectList(any());
    verify(customerCompanyMapper, never()).update(any(), any());
    verifyNoInteractions(customerPoolLogMapper);
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

  /** 在职正常用户（status=1）：分派目标用户的合法形态。 */
  private UserEntity activeUser(Long id, Long roleId) {
    return activeUser(id, roleId, 1);
  }

  private UserEntity activeUser(Long id, Long roleId, Integer status) {
    UserEntity u = new UserEntity();
    u.setId(id);
    u.setRoleId(roleId);
    u.setStatus(status);
    return u;
  }
}
