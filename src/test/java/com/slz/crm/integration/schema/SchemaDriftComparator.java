package com.slz.crm.integration.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * schema 漂移比对器（audit-entity-table-drift 任务 2.2，纯函数）。
 *
 * <p>输入实体侧与库侧两个 Map，输出分级 finding 列表：</p>
 * <ul>
 *   <li><b>CRITICAL</b>：实体映射的表在真库缺失，或实体列在表缺失（运行期必炸类，V24 同类）；</li>
 *   <li><b>WARN</b>：粗粒度类型不亲和（实体 Java 类型与库 data_type 分属不同亲和组）；</li>
 *   <li><b>INFO</b>：表列实体未映射（冗余列）、有表无实体（{@code flyway_schema_history} 豁免）。</li>
 * </ul>
 *
 * <p>类型亲和只做粗粒度分组，避免长度/精度噪声；布尔实体（Boolean）与 tinyint/int 视为兼容
 * （MySQL {@code tinyint(1)} 在 information_schema 只暴露 {@code tinyint}，无法区分）。</p>
 */
public final class SchemaDriftComparator {

    /** Flyway 自身元数据表，豁免"有表无实体"INFO。 */
    public static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    private SchemaDriftComparator() {
    }

    /**
     * 比对实体侧与库侧 schema。
     *
     * @param entitySide 表名 → 实体映射元数据（含列名与 Java 类型）
     * @param dbSide     表名 → 列名 → 库侧列元数据
     * @return 分级 finding 列表（顺序：CRITICAL → WARN → INFO，各组内按表名/列名稳定序）
     */
    public static List<DriftFinding> compare(Map<String, EntityTable> entitySide,
                                             Map<String, Map<String, DbColumn>> dbSide) {
        List<DriftFinding> critical = new ArrayList<>();
        List<DriftFinding> warn = new ArrayList<>();
        List<DriftFinding> info = new ArrayList<>();

        // 实体表缺失（CRITICAL）
        for (String table : sorted(entitySide.keySet())) {
            if (!dbSide.containsKey(table)) {
                critical.add(DriftFinding.critical(table, null,
                        "实体表在真库缺失——实体 " + entitySide.get(table).entityClass().getName() + " 映射的任意查询都会 SQL 报错"));
            }
        }
        // 实体列缺失 + 类型不亲和
        for (String table : sorted(entitySide.keySet())) {
            Map<String, DbColumn> dbColumns = dbSide.get(table);
            if (dbColumns == null) {
                continue;
            }
            EntityTable entity = entitySide.get(table);
            for (String column : entity.columns()) {
                DbColumn dbColumn = dbColumns.get(column);
                if (dbColumn == null) {
                    critical.add(DriftFinding.critical(table, column,
                            "实体列在真库表缺失——实体 " + entity.entityClass().getName() + "." + column
                                    + " 的全字段查询会 SQL 报错（V24 同类漂移）"));
                    continue;
                }
                String entityGroup = affinityGroup(entity.javaTypeOf(column));
                String dbGroup = dbAffinityGroup(dbColumn.dataType());
                if (entityGroup != null && dbGroup != null
                        && !affinityCompatible(entityGroup).contains(dbGroup)) {
                    warn.add(DriftFinding.warn(table, column,
                            "类型不亲和：实体 " + entity.javaTypeOf(column) + " ↔ 库 " + dbColumn.dataType()));
                }
            }
        }
        // 冗余列 + 有表无实体（INFO）
        for (String table : sorted(dbSide.keySet())) {
            if (FLYWAY_HISTORY_TABLE.equals(table)) {
                continue;
            }
            EntityTable entity = entitySide.get(table);
            if (entity == null) {
                info.add(DriftFinding.info(table, null, "有表无实体——库侧存在但无 @TableName 实体映射，暂不处理"));
                continue;
            }
            Set<String> entityColumns = entity.columns();
            for (String column : sorted(dbSide.get(table).keySet())) {
                if (!entityColumns.contains(column)) {
                    info.add(DriftFinding.info(table, column, "冗余列——库侧存在但实体未映射，暂不处理"));
                }
            }
        }

        List<DriftFinding> findings = new ArrayList<>(critical.size() + warn.size() + info.size());
        findings.addAll(critical);
        findings.addAll(warn);
        findings.addAll(info);
        return findings;
    }

    /**
     * 实体 Java 类型 → 亲和组。未知类型返回 {@code null}（不产生 WARN，避免误报）。
     * 枚举（{@code @EnumValue} String 入库，如 {@code KnowledgeBaseMemberRole}）按字符串处理。
     */
    static String affinityGroup(String javaTypeName) {
        switch (javaTypeName) {
            case "java.lang.Long":
            case "java.lang.Integer":
            case "java.lang.Short":
            case "java.lang.Byte":
            case "long":
            case "int":
                return "integer";
            case "java.lang.String":
                return "string";
            case "java.math.BigDecimal":
            case "java.lang.Double":
            case "java.lang.Float":
            case "double":
            case "float":
                return "decimal";
            case "java.time.LocalDateTime":
            case "java.time.LocalDate":
            case "java.util.Date":
            case "java.sql.Timestamp":
            case "java.sql.Date":
            case "java.time.Instant":
                return "temporal";
            case "java.lang.Boolean":
            case "boolean":
                return "boolean";
            case "[B":
            case "byte[]":
                return "binary";
            default:
                // 枚举类型（com.slz.crm...*Role / *Visibility）与自定义包装：按 @EnumValue String 语义归字符串
                if (isEnumType(javaTypeName)) {
                    return "string";
                }
                return null;
        }
    }

    private static boolean isEnumType(String javaTypeName) {
        if (!javaTypeName.contains(".")) {
            return false;
        }
        try {
            return Class.forName(javaTypeName, false, SchemaDriftComparator.class.getClassLoader()).isEnum();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 库侧 MySQL data_type → 亲和组；未知类型返回 {@code null}。 */
    static String dbAffinityGroup(String dataType) {
        String t = dataType.toLowerCase(Locale.ROOT);
        if (t.startsWith("varchar") || t.startsWith("char") || t.contains("text")) {
            return "string";
        }
        if (t.equals("bigint") || t.equals("int") || t.equals("integer")
                || t.equals("smallint") || t.equals("mediumint") || t.equals("tinyint")) {
            return "integer";
        }
        if (t.equals("bit")) {
            return "boolean";
        }
        if (t.equals("decimal") || t.equals("numeric") || t.equals("double") || t.equals("float")) {
            return "decimal";
        }
        if (t.equals("datetime") || t.equals("timestamp") || t.equals("date") || t.equals("time")) {
            return "temporal";
        }
        if (t.contains("blob") || t.startsWith("binary") || t.startsWith("varbinary")) {
            return "binary";
        }
        if (t.equals("json")) {
            return "json";
        }
        return null;
    }

    /** 实体亲和组可接受的库侧组集合（Boolean 实体兼容 tinyint 等整数列）。 */
    private static Set<String> affinityCompatible(String entityGroup) {
        return switch (entityGroup) {
            case "boolean" -> Set.of("boolean", "integer");
            case "integer" -> Set.of("integer", "boolean");
            default -> Set.of(entityGroup);
        };
    }

    private static List<String> sorted(Set<String> keys) {
        List<String> list = new ArrayList<>(keys);
        list.sort(String::compareTo);
        return list;
    }
}
