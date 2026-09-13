package com.slz.crm.integration.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 实体侧单表映射元数据（audit-entity-table-drift 任务 1.2，来自 MyBatis-Plus TableInfoHelper）。
 *
 * @param tableName   实体 {@code @TableName} 映射的真实表名
 * @param entityClass 实体类
 * @param columnTypes 物理列名 → Java 类型全限定名（{@code @TableId} 计入、{@code @TableField(exist=false)}
 *                    排除、无注解字段走驼峰转下划线），LinkedHashMap 保序
 */
public record EntityTable(String tableName, Class<?> entityClass, Map<String, String> columnTypes) {

    /** 防御性拷贝：record 访问器不暴露内部可变 Map。 */
    public EntityTable {
        columnTypes = Collections.unmodifiableMap(new LinkedHashMap<>(columnTypes));
    }

    /** 物理列名全集（保序视图）。 */
    public Set<String> columns() {
        return columnTypes.keySet();
    }

    /** 单列 Java 类型全限定名，无则 {@code null}。 */
    public String javaTypeOf(String column) {
        return columnTypes.get(column);
    }
}
