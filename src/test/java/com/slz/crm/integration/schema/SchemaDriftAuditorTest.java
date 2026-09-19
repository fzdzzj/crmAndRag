package com.slz.crm.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.CompanyDeptEntity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 实体侧元数据提取单测（audit-entity-table-drift 任务 1.3）。
 *
 * <p>验证三件事：扫描数=登记数（53，防漏审）；已知实体映射正确（{@code approval_attachment} 必含 {@code
 * uploader_id}）；{@code @TableField(exist=false)} 字段不产生物理列。
 */
class SchemaDriftAuditorTest {

  /** 内嵌夹具：只用于本测试直接调用 extractTable，扫描路径会跳过内嵌类，不影响 53 计数。 */
  @TableName("fixture_drift_entity")
  static class ExistFalseFixture {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("real_column")
    private String realField;

    @TableField(exist = false)
    private String virtualField;
  }

  @Test
  void scanMatchesRegistryExactly() {
    List<EntityTable> tables = SchemaDriftAuditor.scanAndExtract();

    assertEquals(
        SchemaDriftAuditor.ENTITY_REGISTRY.size(),
        tables.size(),
        "扫描实体数必须等于登记数（当前登记 53：pojo 38 / knowledge 7 / platform 8）");
    Set<String> scanned = new TreeSet<>();
    for (EntityTable table : tables) {
      scanned.add(table.tableName());
    }
    assertEquals(
        new TreeSet<>(SchemaDriftAuditor.ENTITY_REGISTRY.keySet()),
        scanned,
        "扫描表名集合必须与登记清单完全一致——新实体必须同步登记");
    // 表名与实体类一一对应
    for (EntityTable table : tables) {
      assertEquals(
          SchemaDriftAuditor.ENTITY_REGISTRY.get(table.tableName()),
          table.entityClass(),
          "登记实体类与扫描实体类不一致：" + table.tableName());
    }
  }

  @Test
  void approvalAttachmentMapsUploaderIdAndExplicitColumns() {
    EntityTable table = SchemaDriftAuditor.extractTable(ApprovalAttachmentEntity.class);

    assertEquals("approval_attachment", table.tableName());
    // V24 已补列的根修对象：实体侧必须映射出 uploader_id
    assertTrue(
        table.columns().contains("uploader_id"), "ApprovalAttachmentEntity 必须映射出 uploader_id 列");
    // @TableField 显式列名优先（andId → and_id）
    assertTrue(table.columns().contains("and_id"), "andId 应映射为 and_id（@TableField 显式列名）");
    assertEquals("java.lang.Long", table.javaTypeOf("uploader_id"));
  }

  @Test
  void camelCaseFieldMapsToSnakeCaseColumn() {
    EntityTable table = SchemaDriftAuditor.extractTable(CompanyDeptEntity.class);

    // 无注解字段走 MP 驼峰转下划线
    assertTrue(table.columns().contains("group_id"), "groupId 应默认映射为 group_id");
    assertTrue(table.columns().contains("dept_name"), "deptName 应默认映射为 dept_name");
    assertFalse(table.columns().contains("groupId"), "不应保留驼峰原形");
  }

  @Test
  void existFalseFieldProducesNoColumn() {
    EntityTable table = SchemaDriftAuditor.extractTable(ExistFalseFixture.class);

    Map<String, String> columnTypes = table.columnTypes();
    assertTrue(columnTypes.containsKey("id"));
    assertTrue(columnTypes.containsKey("real_column"));
    assertFalse(
        table.columns().stream().anyMatch(c -> c.toLowerCase().contains("virtual")),
        "exist=false 字段不得产生物理列，实际列集=" + table.columns());
  }

  @Test
  void keyColumnCountedEvenWhenIdNotAutoColumn() {
    EntityTable table = SchemaDriftAuditor.extractTable(CompanyDeptEntity.class);

    assertNotNull(table.javaTypeOf("id"), "主键列 id 必须计入列集（@TableId 计入）");
    assertEquals("java.lang.Long", table.javaTypeOf("id"));
  }
}
