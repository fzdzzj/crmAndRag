package com.slz.crm.server.aspect;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.server.constant.ResourceTypeConstant;
import com.slz.crm.server.service.DataScopeService;
import java.lang.reflect.Method;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mapper查询切面 拦截所有Mapper中需要传入QueryWrapper或UpdateWrapper类型参数的方法 在方法执行前对QueryWrapper参数进行统一修改
 * 支持三级数据权限过滤
 */
@Aspect
@Component
public class QueryWrapperAspect {

  private static final Logger logger = LoggerFactory.getLogger(QueryWrapperAspect.class);

  @Autowired private DataScopeService dataScopeService;

  /** 定义切点：拦截Mapper接口中的list、page、selectList、selectPage方法 并且方法参数中包含QueryWrapper或其子类 */
  @Pointcut(
      "(execution(* com.slz.crm.server.mapper..*.list*(..)) || "
          + "execution(* com.slz.crm.server.mapper..*.page*(..)) || "
          + "execution(* com.slz.crm.server.mapper..*.selectList(..)) || "
          + "execution(* com.slz.crm.server.mapper..*.selectPage(..))) && "
          + "args(.., com.baomidou.mybatisplus.core.conditions.Wrapper+)")
  public void mapperMethodsWithWrapper() {}

  /** 定义切点：拦截Mapper接口中的list、page、selectList、selectPage方法 但方法参数中不包含QueryWrapper */
  @Pointcut(
      "(execution(* com.slz.crm.server.mapper..*.list*(..)) || "
          + "execution(* com.slz.crm.server.mapper..*.page*(..)) || "
          + "execution(* com.slz.crm.server.mapper..*.selectList(..)) || "
          + "execution(* com.slz.crm.server.mapper..*.selectPage(..))) && "
          + "!args(.., com.baomidou.mybatisplus.core.conditions.Wrapper+)")
  public void mapperMethodsWithoutWrapper() {}

  /**
   * 前置通知:拦截带QueryWrapper参数的list和page方法
   *
   * @param joinPoint 连接点
   */
  @Before("mapperMethodsWithWrapper()")
  public void beforeQueryWithWrapper(JoinPoint joinPoint) {
    Object[] args = joinPoint.getArgs();

    if (args == null) {
      return;
    }

    // 遍历方法参数,查找QueryWrapper、LambdaQueryWrapper、UpdateWrapper或LambdaUpdateWrapper类型的参数
    for (Object arg : args) {
      if (arg instanceof QueryWrapper<?> wrapper) {
        logger.debug("拦截到带QueryWrapper参数的方法");
        addDataScopeCondition(joinPoint, wrapper);
        modifyQueryWrapper(arg);
      } else if (arg instanceof LambdaQueryWrapper<?> lambdaWrapper) {
        logger.debug("拦截到带LambdaQueryWrapper参数的方法");
        addDataScopeCondition(joinPoint, lambdaWrapper);
        modifyQueryWrapper(arg);
      } else if (arg instanceof UpdateWrapper<?>) {
        logger.debug("拦截到带UpdateWrapper参数的方法");
        modifyQueryWrapper(arg);
      } else if (arg instanceof LambdaUpdateWrapper<?> lambdaUpdateWrapper) {
        logger.debug("拦截到带LambdaUpdateWrapper参数的方法");
        modifyQueryWrapper(arg);
      }
    }
  }

  /**
   * 环绕通知：拦截不带QueryWrapper参数的list和page方法 自动转发到带QueryWrapper参数的重载方法
   *
   * @param joinPoint 连接点
   * @return 方法执行结果
   * @throws Throwable 异常
   */
  @Around("mapperMethodsWithoutWrapper()")
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 反射invoke+proceed多源，失败回退原方法防中断
  public Object aroundQueryWithoutWrapper(ProceedingJoinPoint joinPoint) throws Throwable {
    String methodName = joinPoint.getSignature().getName();

    logger.debug("拦截到不带QueryWrapper的方法 - 方法名: {}", methodName);

    // 1. 获取当前用户
    RoleAO currentUser = com.slz.crm.common.untils.BaseUnit.getCurrentRole();
    if (currentUser == null) {
      logger.warn("当前用户未登录,直接执行原方法");
      return joinPoint.proceed();
    }

    // 2. 提取表名
    String tableName = extractTableName(joinPoint);

    // 3. 判断表是否需要权限管理
    if (!ResourceTypeConstant.isManagedTable(tableName)) {
      logger.debug("表 {} 不需要数据权限控制,直接执行原方法", tableName);
      return joinPoint.proceed();
    }

    // 4. 查找带 QueryWrapper 参数的重载方法
    Method methodWithWrapper = findMethodWithWrapper(joinPoint);
    if (methodWithWrapper == null) {
      logger.warn("未找到带QueryWrapper参数的重载方法: {}, 直接执行原方法", methodName);
      return joinPoint.proceed();
    }

    // 5. 创建 QueryWrapper 并添加权限条件
    QueryWrapper<?> wrapper = new QueryWrapper<>();
    dataScopeService.addDataScopeCondition(wrapper, currentUser, tableName);

    logger.info(
        "数据权限过滤(自动转发) - 用户ID: {}, 角色ID: {}, 表名: {}, 方法: {}",
        currentUser.getId(),
        currentUser.getRoleId(),
        tableName,
        methodName);

    // 6. 调用带 Wrapper 的方法
    try {
      Object mapper = joinPoint.getTarget();
      return methodWithWrapper.invoke(mapper, wrapper);
    } catch (Exception e) {
      logger.error("调用带QueryWrapper的方法失败,回退到原方法", e);
      return joinPoint.proceed();
    }
  }

  /**
   * 查找带 QueryWrapper 参数的重载方法
   *
   * @param joinPoint 连接点
   * @return 带 QueryWrapper 参数的方法,如果找不到返回 null
   */
  @SuppressWarnings("PMD.EmptyCatchBlock") // 有意吞：NoSuchMethodException 是「接口无该重载」的正常答案，非异常
  private Method findMethodWithWrapper(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    String methodName = signature.getName();
    Class<?> targetClass = joinPoint.getTarget().getClass();

    // 获取所有接口(包括父接口)
    Class<?>[] interfaces = targetClass.getInterfaces();

    for (Class<?> iface : interfaces) {
      try {
        // 查找带 QueryWrapper 参数的方法
        Method method = iface.getMethod(methodName, QueryWrapper.class);
        if (method != null) {
          return method;
        }
      } catch (NoSuchMethodException e) {
        // 继续查找下一个接口
      }

      // 也尝试查找带 Wrapper 父类参数的方法
      try {
        Method method =
            iface.getMethod(methodName, com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        if (method != null) {
          return method;
        }
      } catch (NoSuchMethodException e) {
        // 继续查找
      }
    }

    return null;
  }

  /**
   * 修改QueryWrapper参数
   *
   * @param wrapper QueryWrapper或UpdateWrapper对象
   */
  private void modifyQueryWrapper(Object wrapper) {
    if (wrapper instanceof QueryWrapper<?> queryWrapper) {
      logger.debug("QueryWrapper当前条件: {}", queryWrapper);
    } else if (wrapper instanceof UpdateWrapper<?> updateWrapper) {
      logger.debug("UpdateWrapper当前条件: {}", updateWrapper);
    }
  }

  /**
   * 添加数据权限条件
   *
   * @param joinPoint 连接点
   * @param wrapper QueryWrapper 对象
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 数据权限外呼服务多源，失败仅告警不阻断查询
  private void addDataScopeCondition(JoinPoint joinPoint, QueryWrapper<?> wrapper) {
    try {
      // 1. 获取当前用户
      RoleAO currentUser = com.slz.crm.common.untils.BaseUnit.getCurrentRole();
      if (currentUser == null) {
        logger.warn("当前用户未登录,不添加数据权限条件");
        return;
      }

      // 2. 从方法签名中提取表名
      String tableName = extractTableName(joinPoint);

      // 3. 判断表是否需要权限管理
      if (!ResourceTypeConstant.isManagedTable(tableName)) {
        logger.debug("表 {} 不需要数据权限控制", tableName);
        return;
      }

      logger.debug(
          "数据权限过滤 - 用户ID: {}, 角色ID: {}, 表名: {}",
          currentUser.getId(),
          currentUser.getRoleId(),
          tableName);

      // 4. 调用数据权限服务添加条件
      dataScopeService.addDataScopeCondition(wrapper, currentUser, tableName);

    } catch (Exception e) {
      logger.error("添加数据权限条件失败", e);
    }
  }

  /**
   * 添加数据权限条件（LambdaQueryWrapper版本）
   *
   * @param joinPoint 连接点
   * @param wrapper LambdaQueryWrapper 对象
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 数据权限外呼服务多源，失败仅告警不阻断查询
  private void addDataScopeCondition(JoinPoint joinPoint, LambdaQueryWrapper<?> wrapper) {
    try {
      // 1. 获取当前用户
      RoleAO currentUser = com.slz.crm.common.untils.BaseUnit.getCurrentRole();
      if (currentUser == null) {
        logger.warn("当前用户未登录,不添加数据权限条件");
        return;
      }

      // 2. 从方法签名中提取表名
      String tableName = extractTableName(joinPoint);

      // 3. 判断表是否需要权限管理
      if (!ResourceTypeConstant.isManagedTable(tableName)) {
        logger.debug("表 {} 不需要数据权限控制", tableName);
        return;
      }

      logger.debug(
          "数据权限过滤(Lambda) - 用户ID: {}, 角色ID: {}, 表名: {}",
          currentUser.getId(),
          currentUser.getRoleId(),
          tableName);

      // 4. 调用数据权限服务添加条件（使用 Wrapper 基类方法）
      dataScopeService.addDataScopeCondition(wrapper, currentUser, tableName);

    } catch (Exception e) {
      logger.error("添加数据权限条件失败(Lambda)", e);
    }
  }

  /**
   * 从 JoinPoint 中提取表名
   *
   * @param joinPoint 连接点
   * @return 表名(小写下划线命名)
   */
  private String extractTableName(JoinPoint joinPoint) {
    String mapperClassName = null;

    // 获取目标对象
    Object target = joinPoint.getTarget();
    Class<?> targetClass = target.getClass();

    // 判断是否为 JDK 动态代理（MyBatis Mapper 使用 JDK 动态代理）
    String simpleName = targetClass.getSimpleName();
    if (simpleName.startsWith("$Proxy")) {
      // JDK 动态代理：获取接口
      Class<?>[] interfaces = targetClass.getInterfaces();
      if (interfaces.length > 0) {
        mapperClassName = interfaces[0].getSimpleName();
      }
    } else {
      // CGLIB 代理：从类名中提取
      mapperClassName = targetClass.getSimpleName();
      // 去掉可能的代理类前缀（如 CustomerCompanyMapper$$EnhancerBySpringCGLIB$$...）
      if (mapperClassName.contains("$$")) {
        mapperClassName = mapperClassName.substring(0, mapperClassName.indexOf("$$"));
      }
    }

    final String result;
    if (mapperClassName == null) {
      logger.warn("无法从 JoinPoint 提取 Mapper 类名");
      result = "";
    } else {
      // 去掉 "Mapper" 后缀
      String entityName = mapperClassName.replace("Mapper", "");

      // 转换为下划线命名(驼峰转下划线)
      result = camelToUnderscore(entityName);
    }
    return result;
  }

  /**
   * 驼峰转下划线
   *
   * @param camelCase 驼峰命名字符串
   * @return 下划线命名字符串
   */
  private String camelToUnderscore(String camelCase) {
    return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
  }
}
