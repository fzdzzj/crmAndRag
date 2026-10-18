package com.slz.crm.unit.server.aspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.InvoiceInfoEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.aspect.QueryWrapperAspect;
import com.slz.crm.server.constant.ResourceTypeConstant;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.InvoiceInfoMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataScopeService;
import com.slz.crm.server.service.impl.InvoiceInfoServiceImpl;
import java.lang.reflect.Proxy;
import java.util.List;
import org.aspectj.lang.JoinPoint;
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
 * invoice_info 纳管与作废越权专项测试（harden-security-attachment-reset-and-invoice 任务 3.3，纯 JVM Mockito）。
 *
 * <p>覆盖：{@code invoice_info} 进入 {@code MANAGED_TABLES}/{@code TABLE_USER_FIELDS} 后，查询切面对
 * InvoiceInfoMapper 的 QueryWrapper/LambdaQueryWrapper 查询按表名 {@code invoice_info} 注入三级数据权限； 以及
 * {@code voidInvoice} 仅创建人或超管可作废（越权 12002）、重复作废与记录缺失的规范化错误码。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceDataScopeSecurityTest invoice_info 纳管与作废越权专项测试")
class InvoiceDataScopeSecurityTest {

  private static final String INVOICE_TABLE = "invoice_info";
  private static final Long CURRENT_USER_ID = 7L;
  private static final Long INVOICE_ID = 5L;

  @Mock private DataScopeService dataScopeService;

  @InjectMocks private QueryWrapperAspect aspect;

  @Mock private InvoiceInfoMapper invoiceInfoMapper;

  @Mock private ContractMapper contractMapper;

  @Mock private PaymentRecordMapper paymentRecordMapper;

  @Mock private UserMapper userMapper;

  @InjectMocks private InvoiceInfoServiceImpl invoiceInfoService;

  private RoleAO currentUser;

  @BeforeEach
  void setUp() {
    currentUser = new RoleAO();
    currentUser.setId(CURRENT_USER_ID);
    currentUser.setRoleId(3L);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("1. 纳管生效：QueryWrapper 列表查询按表名 invoice_info 注入数据权限")
  void shouldRouteInvoiceInfoQueryWrapperIntoDataScopeInjection() {
    BaseUnit.setCurrentRole(currentUser);
    QueryWrapper<InvoiceInfoEntity> wrapper = new QueryWrapper<>();
    doNothing()
        .when(dataScopeService)
        .addDataScopeCondition(any(QueryWrapper.class), any(RoleAO.class), eq(INVOICE_TABLE));

    aspect.beforeQueryWithWrapper(beforeJoinPoint(invoiceMapperProxy(), wrapper));

    verify(dataScopeService)
        .addDataScopeCondition(same(wrapper), same(currentUser), eq(INVOICE_TABLE));
  }

  @Test
  @DisplayName("2. 纳管生效：LambdaQueryWrapper 分页查询按表名 invoice_info 注入数据权限")
  void shouldRouteInvoiceInfoLambdaWrapperIntoDataScopeInjection() {
    BaseUnit.setCurrentRole(currentUser);
    LambdaQueryWrapper<InvoiceInfoEntity> wrapper = new LambdaQueryWrapper<>();
    doNothing()
        .when(dataScopeService)
        .addDataScopeCondition(any(LambdaQueryWrapper.class), any(RoleAO.class), eq(INVOICE_TABLE));

    aspect.beforeQueryWithWrapper(beforeJoinPoint(invoiceMapperProxy(), wrapper));

    verify(dataScopeService)
        .addDataScopeCondition(same(wrapper), same(currentUser), eq(INVOICE_TABLE));
  }

  @Test
  @DisplayName("3. 常量映射：invoice_info 为受管表且用户字段为 creator_id")
  void shouldExposeInvoiceInfoAsManagedTableWithCreatorField() {
    assertTrue(ResourceTypeConstant.isManagedTable(INVOICE_TABLE));
    assertEquals(List.of("creator_id"), ResourceTypeConstant.TABLE_USER_FIELDS.get(INVOICE_TABLE));
    assertEquals(
        List.of("creator_id"), ResourceTypeConstant.getUserFieldsByTableName(INVOICE_TABLE));
  }

  @Test
  @DisplayName("4. 作废越权：非创建人非超管拦截 12002 且零 SQL（发票状态未被修改）")
  void voidInvoiceDeniedForUnrelatedUser() {
    BaseUnit.setCurrentRole(currentUser);
    when(invoiceInfoMapper.selectById(INVOICE_ID)).thenReturn(invoiceOf(INVOICE_ID, 9L, 0));
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(userOf(CURRENT_USER_ID, 3L));

    BaseException exception =
        assertThrows(BaseException.class, () -> invoiceInfoService.voidInvoice(INVOICE_ID));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), exception.getCode());
    assertEquals("仅发票创建人或超级管理员可作废发票", exception.getMessage());
    verify(invoiceInfoMapper, never()).updateById(any(InvoiceInfoEntity.class));
  }

  @Test
  @DisplayName("5. 创建人作废：正向放行且状态置为已作废")
  void voidInvoiceAllowedForCreator() {
    BaseUnit.setCurrentRole(currentUser);
    when(invoiceInfoMapper.selectById(INVOICE_ID))
        .thenReturn(invoiceOf(INVOICE_ID, CURRENT_USER_ID, 0));
    when(userMapper.selectById(CURRENT_USER_ID)).thenReturn(userOf(CURRENT_USER_ID, 3L));
    when(invoiceInfoMapper.updateById(any(InvoiceInfoEntity.class))).thenReturn(1);

    assertTrue(invoiceInfoService.voidInvoice(INVOICE_ID));

    ArgumentCaptor<InvoiceInfoEntity> captor = ArgumentCaptor.forClass(InvoiceInfoEntity.class);
    verify(invoiceInfoMapper).updateById(captor.capture());
    assertEquals(1, captor.getValue().getStatus(), "作废后状态必须置为 1");
  }

  @Test
  @DisplayName("6. 超管作废：非创建人但 roleId=1 正向放行")
  void voidInvoiceAllowedForSuperAdmin() {
    RoleAO admin = new RoleAO();
    admin.setId(8L);
    admin.setRoleId(1L);
    BaseUnit.setCurrentRole(admin);
    when(invoiceInfoMapper.selectById(INVOICE_ID)).thenReturn(invoiceOf(INVOICE_ID, 9L, 0));
    when(userMapper.selectById(8L)).thenReturn(userOf(8L, 1L));
    when(invoiceInfoMapper.updateById(any(InvoiceInfoEntity.class))).thenReturn(1);

    assertTrue(invoiceInfoService.voidInvoice(INVOICE_ID));
  }

  @Test
  @DisplayName("7. 重复作废：规范化为 BUSINESS_ERROR 错误码（92000）")
  void voidInvoiceAlreadyVoidedRejectedWithBusinessError() {
    BaseUnit.setCurrentRole(currentUser);
    when(invoiceInfoMapper.selectById(INVOICE_ID)).thenReturn(invoiceOf(INVOICE_ID, 9L, 1));

    BaseException exception =
        assertThrows(BaseException.class, () -> invoiceInfoService.voidInvoice(INVOICE_ID));

    assertEquals(ErrorCode.BUSINESS_ERROR.getCode(), exception.getCode());
    assertEquals("发票已作废，无法重复作废", exception.getMessage());
    verifyNoInteractions(userMapper);
  }

  @Test
  @DisplayName("8. 记录缺失：规范化为 DATA_NULL 错误码（91001）")
  void voidInvoiceMissingRecordRejectedWithDataNull() {
    BaseUnit.setCurrentRole(currentUser);
    when(invoiceInfoMapper.selectById(INVOICE_ID)).thenReturn(null);

    BaseException exception =
        assertThrows(BaseException.class, () -> invoiceInfoService.voidInvoice(INVOICE_ID));

    assertEquals(ErrorCode.DATA_NULL.getCode(), exception.getCode());
    assertEquals("开票信息不存在", exception.getMessage());
  }

  /**
   * 构造 InvoiceInfoMapper 的 JDK 动态代理：生产中 MyBatis Mapper 即 JDK 代理， 切面的表名提取按 $Proxy* →
   * 接口名链路工作（InvoiceInfoMapper → invoice_info）。
   */
  private Object invoiceMapperProxy() {
    return Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {InvoiceInfoMapper.class},
        (proxy, method, args) -> null);
  }

  /** 构造 @Before 通知用连接点：目标为 InvoiceInfoMapper 代理，参数含待注入 Wrapper。 */
  private JoinPoint beforeJoinPoint(Object mapper, Object wrapper) {
    JoinPoint joinPoint = mock(JoinPoint.class);
    when(joinPoint.getTarget()).thenReturn(mapper);
    when(joinPoint.getArgs()).thenReturn(new Object[] {wrapper});
    return joinPoint;
  }

  private InvoiceInfoEntity invoiceOf(Long id, Long creatorId, int status) {
    InvoiceInfoEntity entity = new InvoiceInfoEntity();
    entity.setId(id);
    entity.setCreatorId(creatorId);
    entity.setStatus(status);
    return entity;
  }

  private UserEntity userOf(Long id, Long roleId) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setRoleId(roleId);
    user.setStatus(1);
    return user;
  }
}
