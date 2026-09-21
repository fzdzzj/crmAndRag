package com.slz.crm.common.untils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.DataShareEntity;
import com.slz.crm.server.config.ForeignKeyConfig;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 外键关联数据删除工具类（支持递归级联删除）
 *
 * @author evi
 */
@Component
@Slf4j
public class ForeignKeyDeleteUtil {

  @Autowired private ApplicationContext applicationContext;

  /**
   * 递归删除所有关联数据（多米诺骨牌式）
   *
   * @param entityClass 主表实体类
   * @param ids 主表ID列表
   * @param resourceType 资源类型
   */
  public <T> void deleteCascade(Class<T> entityClass, List<Long> ids, Integer resourceType) {
    log.info(
        "开始递归删除关联数据，实体类: {}, ids: {}, resourceType: {}",
        entityClass.getSimpleName(),
        ids,
        resourceType);

    // 1. 获取当前实体的直接子依赖关系
    Map<Class<?>, List<String>> directRelations = ForeignKeyConfig.ALL_RELATIONS.get(entityClass);
    if (directRelations == null || directRelations.isEmpty() || ids.isEmpty()) {
      log.info("没有直接关联的子表，无需处理");
      return;
    }

    // 2. 先处理所有直接子依赖表
    for (Map.Entry<Class<?>, List<String>> entry : directRelations.entrySet()) {
      Class<?> childEntityClass = entry.getKey();
      List<String> foreignKeys = entry.getValue();

      for (String foreignKey : foreignKeys) {
        // 获取子表中需要删除的记录ID
        List<Long> childIds = getChildIds(childEntityClass, foreignKey, ids, resourceType);
        if (childIds.isEmpty()) {
          log.info("子表 {} 没有关联记录，跳过", childEntityClass.getSimpleName());
          continue;
        }

        // 3. 递归处理子表的子依赖（先删除孙子辈）
        log.info("递归处理子表 {} 的依赖关系", childEntityClass.getSimpleName());
        deleteCascade(childEntityClass, childIds, resourceType);

        // 4. 删除当前子表的关联记录（再删除子辈）
        deleteChildRecords(childEntityClass, foreignKey, ids, resourceType);
      }
    }
  }

  /**
   * 逻辑删除单个实体及其关联数据
   *
   * @param entityClass 实体类
   * @param id 主表ID
   * @return 删除成功数量
   */
  public <T> Integer logicalDeleteByCascade(Class<T> entityClass, Long id) {
    List<Long> idList = new ArrayList<>();
    idList.add(id);

    // 调用级联删除方法，资源类型设为null（非数据共享表）
    deleteCascade(entityClass, idList, null);

    // 逻辑删除主表记录
    return logicalDeleteMainRecord(entityClass, id);
  }

  /** 逻辑删除主表记录 */
  private <T> Integer logicalDeleteMainRecord(Class<T> entityClass, Long id) {
    Integer result = 0;
    String mapperBeanName = getMapperBeanName(entityClass);
    try {
      BaseMapper<T> mapper = applicationContext.getBean(mapperBeanName, BaseMapper.class);

      // 创建更新实体，设置isDeleted=true
      Object updateEntity = entityClass.getDeclaredConstructor().newInstance();

      // 使用反射设置ID和isDeleted
      entityClass.getMethod("setId", Long.class).invoke(updateEntity, id);

      try {
        entityClass.getMethod("setIsDeleted", Boolean.class).invoke(updateEntity, true);
        result = mapper.updateById((T) updateEntity);
        log.info("逻辑删除主表记录: {}, 影响行数: {}", entityClass.getSimpleName(), result);
      } catch (NoSuchMethodException e) {
        log.warn("实体类 {} 没有isDeleted字段，跳过逻辑删除", entityClass.getSimpleName());
      }
    } catch (Exception e) {
      log.error("逻辑删除主表记录失败: {}", entityClass.getSimpleName(), e);
      throw new BaseException(ErrorCode.DATA_DELETE_FAILED);
    }
    return result;
  }

  /** 获取子表中需要删除的记录ID */
  private <T> List<Long> getChildIds(
      Class<T> childEntityClass, String foreignKey, List<Long> parentIds, Integer resourceType) {
    List<Long> result = List.of();
    String mapperBeanName = getMapperBeanName(childEntityClass);
    try {
      BaseMapper<T> mapper = applicationContext.getBean(mapperBeanName, BaseMapper.class);

      QueryWrapper<T> wrapper = new QueryWrapper<>();
      String dbColumnName = ColumnUtils.getColumn(childEntityClass, foreignKey);
      wrapper.in(dbColumnName, parentIds);

      // 特殊处理数据共享表
      if (DataShareEntity.class.isAssignableFrom(childEntityClass)) {
        wrapper.eq("resource_type", resourceType);
      }

      // 获取子表ID列表（假设主键字段为id）
      List<Object> objList = mapper.selectObjs(wrapper);
      result =
          objList.stream()
              .map(
                  obj -> {
                    // 处理可能的类型转换（如Integer转Long，或字符串转Long）
                    if (obj instanceof Number) {
                      return ((Number) obj).longValue(); // 数字类型直接转Long
                    } else if (obj instanceof String) {
                      return Long.parseLong((String) obj); // 字符串转Long（根据实际场景决定是否需要）
                    } else {
                      log.warn("无法转换类型 {} 为 Long，值：{}", obj.getClass(), obj);
                      return null; // 或抛出异常，根据业务处理
                    }
                  })
              .filter(Objects::nonNull) // 过滤转换失败的null
              .collect(Collectors.toList());
    } catch (BeansException e) {
      log.error("获取子表ID失败: {}", mapperBeanName, e);
      result = List.of();
    }
    return result;
  }

  /** 删除子表的关联记录 */
  private <T> void deleteChildRecords(
      Class<T> childEntityClass, String foreignKey, List<Long> parentIds, Integer resourceType) {
    String mapperBeanName = getMapperBeanName(childEntityClass);
    try {
      BaseMapper<T> mapper = applicationContext.getBean(mapperBeanName, BaseMapper.class);

      QueryWrapper<T> wrapper = new QueryWrapper<>();
      String dbColumnName = ColumnUtils.getColumn(childEntityClass, foreignKey);
      wrapper.in(dbColumnName, parentIds);

      // 特殊处理数据共享表
      if (DataShareEntity.class.isAssignableFrom(childEntityClass)) {
        wrapper.eq("resource_type", resourceType);
      }

      int deleteCount = mapper.delete(wrapper);
      log.info("删除子表 {} 记录数: {}", childEntityClass.getSimpleName(), deleteCount);
    } catch (BeansException e) {
      log.error("删除子表记录失败: {}", mapperBeanName, e);
    }
  }

  /** 生成Mapper Bean名称 */
  private String getMapperBeanName(Class<?> entityClass) {
    String entitySimpleName = entityClass.getSimpleName();
    String baseName = entitySimpleName.replaceAll("Entity$", "");
    return Introspector.decapitalize(baseName) + "Mapper";
  }

  /** 内部工具类：处理数据库字段与实体类属性的映射 */
  private static class ColumnUtils {
    public static <T> String getColumn(Class<T> entityClass, String propertyName) {
      try {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(entityClass);
        String result = propertyName;
        if (tableInfo != null) {
          TableFieldInfo fieldInfo =
              tableInfo.getFieldList().stream()
                  .filter(info -> info.getProperty().equals(propertyName))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new NoSuchFieldException(
                              "实体类" + entityClass.getName() + "不存在属性" + propertyName));
          result = fieldInfo.getColumn();
        }
        return result;
      } catch (Exception e) {
        throw new BaseException(
            ErrorCode.ID_NOT_EXISTS,
            String.format(
                ErrorCode.ID_NOT_EXISTS.getMessage(), entityClass.getName() + "." + propertyName));
      }
    }
  }
}
