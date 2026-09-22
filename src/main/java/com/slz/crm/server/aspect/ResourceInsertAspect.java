package com.slz.crm.server.aspect;

import com.baomidou.mybatisplus.annotation.TableName;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.TageResourceBindingEntity;
import com.slz.crm.server.constant.ResourceTypeConstant;
import com.slz.crm.server.mapper.TageResourceBindingMapper;
import com.slz.crm.server.mapper.TageRoleBindingMapper;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 资源插入切面
 *
 * <p>拦截所有Mapper的insert方法，自动为需要数据权限管理的资源绑定标签
 */
@Aspect
@Component
@Slf4j
public class ResourceInsertAspect {

  @Autowired private TageResourceBindingMapper tageResourceBindingMapper;

  @Autowired private TageRoleBindingMapper tageRoleBindingMapper;

  /** 定义切点：拦截所有Mapper的insert方法 */
  @Pointcut("execution(* com.slz.crm.server.mapper..*.insert(..))")
  public void mapperInsertMethod() {}

  /** 后置通知：在insert方法成功执行后自动绑定标签 */
  @AfterReturning(pointcut = "mapperInsertMethod()", returning = "result")
  @SuppressWarnings({
    "PMD.AvoidCatchingGenericException", // 标签绑定兜底：ORM/反射多源，失败不抛出影响正常业务
    "PMD.OnlyOneReturn", // 7 处前置守卫各带独立日志语义，单出口化需深嵌套，损害可读性（tighten-pmd-residual-325 任务 6.3）
  })
  public void afterInsert(JoinPoint joinPoint, Object result) {
    // 如果插入失败，直接返回
    if (result == null || (result instanceof Integer && (Integer) result <= 0)) {
      return;
    }

    try {
      // 1. 获取插入的实体对象
      Object[] args = joinPoint.getArgs();
      if (args == null || args.length == 0) {
        return;
      }

      Object entity = args[0];
      if (entity == null) {
        return;
      }

      // 2. 通过@TableName注解获取表名
      TableName tableNameAnnotation = entity.getClass().getAnnotation(TableName.class);
      if (tableNameAnnotation == null) {
        return;
      }

      String tableName = tableNameAnnotation.value();

      // 3. 判断表是否需要权限管理
      if (!ResourceTypeConstant.isManagedTable(tableName)) {
        // 不需要权限管理
        return;
      }

      // 4. 获取资源ID
      Long resourceId = getResourceId(entity);
      if (resourceId == null) {
        log.warn("无法获取资源ID，实体类: {}", entity.getClass().getSimpleName());
        return;
      }

      // 5. 获取当前用户
      RoleAO currentUser = BaseUnit.getCurrentRole();
      if (currentUser == null) {
        log.warn("当前用户未登录，无法绑定标签");
        return;
      }

      // 6. 查询角色的唯一标签ID
      Long tageId = tageRoleBindingMapper.selectTageIdByRoleId(currentUser.getRoleId());
      if (tageId == null) {
        // 角色没有标签，不插入绑定记录
        log.debug("角色 {} 没有绑定标签。跳过自动绑定", currentUser.getRoleId());
        return;
      }

      // 7. 插入tage_resource_binding表(直接使用表名作为资源类型)
      TageResourceBindingEntity binding = new TageResourceBindingEntity();
      binding.setTageId(tageId);
      binding.setResourceType(tableName);
      binding.setResourceId(resourceId);
      binding.setCreatorId(currentUser.getId());
      binding.setCreateTime(LocalDateTime.now());

      try {
        tageResourceBindingMapper.insert(binding);
        log.info("自动绑定标签成功 - 标签ID: {}, 表名: {}, 资源ID: {}", tageId, tableName, resourceId);
      } catch (DuplicateKeyException e) {
        // 忽略重复绑定
        log.debug("标签绑定已存在 - 标签ID: {}, 表名: {}, 资源ID: {}", tageId, tableName, resourceId);
      }
    } catch (Exception e) {
      log.error("自动绑定标签失败", e);
      // 不抛出异常，避免影响正常业务
    }
  }

  /**
   * 通过反射获取实体的ID
   *
   * @param entity 实体对象
   * @return 实体ID，如果获取失败返回null
   */
  private Long getResourceId(Object entity) {
    Long resourceId = null;
    try {
      // 尝试获取id字段
      Field idField = entity.getClass().getDeclaredField("id");
      idField.setAccessible(true);
      Object idValue = idField.get(entity);

      if (idValue instanceof Long) {
        resourceId = (Long) idValue;
      } else if (idValue instanceof Integer) {
        resourceId = ((Integer) idValue).longValue();
      }

    } catch (NoSuchFieldException e) {
      log.debug("实体类 {} 没有id字段", entity.getClass().getSimpleName());
    } catch (IllegalAccessException e) {
      log.warn("无法访问实体类 {} 的id字段", entity.getClass().getSimpleName());
    }
    return resourceId;
  }
}
