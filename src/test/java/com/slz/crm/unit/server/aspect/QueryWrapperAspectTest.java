package com.slz.crm.unit.server.aspect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
import com.slz.crm.server.aspect.QueryWrapperAspect;
import com.slz.crm.server.service.DataScopeService;
import java.lang.reflect.Proxy;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * QueryWrapperAspect fail-closed 反向阻断单测。
 *
 * <p>核心语义：托管表 + 已登录用户场景下，数据权限注入失败必须抛 12002 阻断（严禁吞咽）、重载转发失败严禁回退 proceed()；系统/后台无用户与非托管表保持放行。
 */
@ExtendWith(MockitoExtension.class)
public class QueryWrapperAspectTest {

  private static final String MANAGED_TABLE = "customer_company";
  private static final String DENIED_MESSAGE = "数据权限过滤失败，拒绝查询";

  @Mock private DataScopeService dataScopeService;

  @InjectMocks private QueryWrapperAspect aspect;

  private RoleAO currentUser;

  /** 模拟托管表 Mapper 接口；生产中 MyBatis 走 JDK 动态代理，表名提取依赖接口名 CustomerCompanyMapper */
  public interface CustomerCompanyMapper {
    Object selectList();

    Object selectList(QueryWrapper<?> wrapper);
  }

  /** 模拟非托管表 Mapper 接口（system_config 不在 MANAGED_TABLES 白名单内） */
  public interface SystemConfigMapper {
    Object selectList();

    Object selectList(QueryWrapper<?> wrapper);
  }

  @BeforeEach
  void setUp() {
    currentUser = new RoleAO();
    currentUser.setId(1L);
    currentUser.setRoleId(3L);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  void shouldThrowPermissionDeniedWhenQueryWrapperDataScopeFails() {
    BaseUnit.setCurrentRole(currentUser);
    QueryWrapper<Object> wrapper = new QueryWrapper<>();
    doThrow(new IllegalStateException("db down"))
        .when(dataScopeService)
        .addDataScopeCondition(any(QueryWrapper.class), any(RoleAO.class), eq(MANAGED_TABLE));

    JoinPoint joinPoint = beforeJoinPoint(mapperProxy(CustomerCompanyMapper.class, null), wrapper);

    BaseException thrown =
        assertThrows(BaseException.class, () -> aspect.beforeQueryWithWrapper(joinPoint));
    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), thrown.getCode());
    assertEquals(DENIED_MESSAGE, thrown.getMessage());
  }

  @Test
  void shouldThrowPermissionDeniedWhenLambdaQueryWrapperDataScopeFails() {
    BaseUnit.setCurrentRole(currentUser);
    LambdaQueryWrapper<Object> wrapper = new LambdaQueryWrapper<>();
    doThrow(new IllegalStateException("db down"))
        .when(dataScopeService)
        .addDataScopeCondition(any(LambdaQueryWrapper.class), any(RoleAO.class), eq(MANAGED_TABLE));

    JoinPoint joinPoint = beforeJoinPoint(mapperProxy(CustomerCompanyMapper.class, null), wrapper);

    BaseException thrown =
        assertThrows(BaseException.class, () -> aspect.beforeQueryWithWrapper(joinPoint));
    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), thrown.getCode());
    assertEquals(DENIED_MESSAGE, thrown.getMessage());
  }

  @Test
  void shouldThrowPermissionDeniedWhenForwardingFails() throws Throwable {
    BaseUnit.setCurrentRole(currentUser);
    ProceedingJoinPoint joinPoint =
        aroundJoinPoint(
            mapperProxy(CustomerCompanyMapper.class, new IllegalStateException("boom")));

    BaseException thrown =
        assertThrows(BaseException.class, () -> aspect.aroundQueryWithoutWrapper(joinPoint));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), thrown.getCode());
    assertEquals("数据权限方法转发失败: boom", thrown.getMessage());
    verify(joinPoint, never()).proceed();
  }

  @Test
  void shouldAllowQueryWhenDataScopeSucceeds() {
    BaseUnit.setCurrentRole(currentUser);
    QueryWrapper<Object> wrapper = new QueryWrapper<>();
    doNothing()
        .when(dataScopeService)
        .addDataScopeCondition(any(QueryWrapper.class), any(RoleAO.class), eq(MANAGED_TABLE));

    JoinPoint joinPoint = beforeJoinPoint(mapperProxy(CustomerCompanyMapper.class, null), wrapper);

    assertDoesNotThrow(() -> aspect.beforeQueryWithWrapper(joinPoint));
    verify(dataScopeService)
        .addDataScopeCondition(same(wrapper), same(currentUser), eq(MANAGED_TABLE));
  }

  @Test
  void shouldBypassWhenUserNullForSystemTask() throws Throwable {
    BaseUnit.removeCurrentId();
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    MethodSignature signature = mock(MethodSignature.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.getName()).thenReturn("selectList");
    when(joinPoint.proceed()).thenReturn("raw-result");

    Object result = aspect.aroundQueryWithoutWrapper(joinPoint);

    assertEquals("raw-result", result);
    verifyNoInteractions(dataScopeService);
  }

  @Test
  void shouldBypassForUnmanagedTable() throws Throwable {
    BaseUnit.setCurrentRole(currentUser);
    ProceedingJoinPoint joinPoint = aroundJoinPoint(mapperProxy(SystemConfigMapper.class, null));
    when(joinPoint.proceed()).thenReturn("raw-result");

    Object result = aspect.aroundQueryWithoutWrapper(joinPoint);

    assertEquals("raw-result", result);
    verifyNoInteractions(dataScopeService);
  }

  @Test
  void shouldRethrowBaseExceptionAsIsWhenInjectionFails() {
    BaseUnit.setCurrentRole(currentUser);
    BaseException businessDenied = new BaseException(ErrorCode.PERMISSION_DENIED, "权限不足");
    QueryWrapper<Object> wrapper = new QueryWrapper<>();
    doThrow(businessDenied)
        .when(dataScopeService)
        .addDataScopeCondition(any(QueryWrapper.class), any(RoleAO.class), eq(MANAGED_TABLE));

    JoinPoint joinPoint = beforeJoinPoint(mapperProxy(CustomerCompanyMapper.class, null), wrapper);

    BaseException thrown =
        assertThrows(BaseException.class, () -> aspect.beforeQueryWithWrapper(joinPoint));
    assertSame(businessDenied, thrown);
  }

  @Test
  void shouldRethrowBaseExceptionWhenForwardTargetThrows() throws Throwable {
    BaseUnit.setCurrentRole(currentUser);
    BaseException businessDenied = new BaseException(ErrorCode.PERMISSION_DENIED, "权限不足");
    ProceedingJoinPoint joinPoint =
        aroundJoinPoint(mapperProxy(CustomerCompanyMapper.class, businessDenied));

    BaseException thrown =
        assertThrows(BaseException.class, () -> aspect.aroundQueryWithoutWrapper(joinPoint));

    assertSame(businessDenied, thrown);
    verify(joinPoint, never()).proceed();
  }

  /**
   * 构造 Mapper 的 JDK 动态代理：生产中 MyBatis Mapper 即 JDK 代理，切面的表名提取按 $Proxy* → 接口名链路工作。
   *
   * @param mapperInterface Mapper 接口
   * @param forwardFailure 转发执行时抛出的异常；null 表示正常执行返回 null
   */
  private Object mapperProxy(Class<?> mapperInterface, RuntimeException forwardFailure) {
    return Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[] {mapperInterface},
        (proxy, method, args) -> {
          if (forwardFailure != null) {
            throw forwardFailure;
          }
          return null;
        });
  }

  /** 构造 @Before 通知用连接点：返回目标代理与单个 Wrapper 参数 */
  private JoinPoint beforeJoinPoint(Object mapper, Object wrapper) {
    JoinPoint joinPoint = mock(JoinPoint.class);
    when(joinPoint.getTarget()).thenReturn(mapper);
    when(joinPoint.getArgs()).thenReturn(new Object[] {wrapper});
    return joinPoint;
  }

  /** 构造 @Around 通知用连接点：方法名 selectList，目标为指定 Mapper 代理 */
  private ProceedingJoinPoint aroundJoinPoint(Object mapper) {
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    MethodSignature signature = mock(MethodSignature.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.getName()).thenReturn("selectList");
    when(joinPoint.getTarget()).thenReturn(mapper);
    return joinPoint;
  }
}
