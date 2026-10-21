package com.slz.crm.unit.server.handler;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.result.Result;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.handler.SQLExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

/**
 * SQL 异常映射单测（fix-engineering-debt-nplus1-loop-clone 任务 3.3；fix-backlog-batch2 ⑦ 扩充）。
 *
 * <p>锁定两件事：①唯一索引名 → 业务字段名的映射只剩 application.yml 的 {@code slz.unique-index-field-map} 一个维护点，列名 映射
 * {@code slz.database-field-map} 与它键空间隔离（原手写 switch 已删）；②duplicate-key 三态（组合 / 单列 / 未知）与
 * 「缺少必填字段」裸列名路径的文案逐路径固定，任何索引名输入（含裸名、空名）都不越界。测试直接从 yml 读回映射注入 handler， 因此 yml
 * 少键/写错值/键串段会直接红，而不是「测试里复制一份映射值」的自证。
 */
@DisplayName("SQL 异常字段映射（配置单源 + 键空间隔离）")
class SQLExceptionHandlerTest {

  private static final Path APPLICATION_YML = Paths.get("src/main/resources/application.yml");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static SQLExceptionHandler handler;

  private static Map<String, String> fieldMap;

  private static Map<String, String> indexFieldMap;

  @BeforeAll
  static void loadYamlMapping() throws Exception {
    String yaml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
    Map<String, Object> root = new Yaml().load(yaml);
    Map<String, Object> slz = asMap(root.get("slz"));
    fieldMap =
        OBJECT_MAPPER.readValue(
            (String) slz.get("database-field-map"), new TypeReference<Map<String, String>>() {});
    indexFieldMap =
        OBJECT_MAPPER.readValue(
            (String) slz.get("unique-index-field-map"),
            new TypeReference<Map<String, String>>() {});
    Map<String, String> compositeMap =
        OBJECT_MAPPER.readValue(
            (String) slz.get("composite-unique-index-map"),
            new TypeReference<Map<String, String>>() {});

    handler = new SQLExceptionHandler();
    ReflectionTestUtils.setField(handler, "databaseFieldMap", fieldMap);
    ReflectionTestUtils.setField(handler, "uniqueIndexFieldMap", indexFieldMap);
    ReflectionTestUtils.setField(handler, "compositeUniqueIndexMap", compositeMap);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  private DuplicateKeyException duplicate(String value, String tableDotIndex) {
    return new DuplicateKeyException(
        "duplicate",
        new SQLIntegrityConstraintViolationException(
            "Duplicate entry '" + value + "' for key '" + tableDotIndex + "'"));
  }

  @SuppressWarnings("unchecked")
  private Result<String> invoke(String method, Exception ex) {
    return (Result<String>) ReflectionTestUtils.invokeMethod(handler, method, ex);
  }

  @Test
  @DisplayName("yml 单源完备：7 个唯一索引名键逐项在 unique-index-field-map 里且值不变")
  void yml_carriesAllSevenIndexKeys() {
    assertEquals("手机号", indexFieldMap.get("uk_phone"));
    assertEquals("邮箱", indexFieldMap.get("uk_email"));
    assertEquals("企业名字", indexFieldMap.get("uk_company_name"));
    assertEquals("联系电话", indexFieldMap.get("idx_mobile"));
    assertEquals("合同编号", indexFieldMap.get("uk_contract_no"));
    assertEquals("支付编号", indexFieldMap.get("uk_payment_no"));
    assertEquals("发票编号", indexFieldMap.get("uk_invoice_no"));
  }

  @Test
  @DisplayName("键空间隔离：索引名键不再出现在列名映射里，列名键也不出现在索引名映射里")
  void keySpacesAreIsolated() {
    assertFalse(fieldMap.containsKey("uk_phone"), "索引名键必须迁出 database-field-map");
    assertFalse(fieldMap.containsKey("uk_email"), "索引名键必须迁出 database-field-map");
    assertFalse(fieldMap.containsKey("idx_mobile"), "索引名键必须迁出 database-field-map");
    assertEquals("用户ID", fieldMap.get("user_id"), "列名映射仍须保留列名键");
    assertEquals("客户ID", fieldMap.get("company_id"), "列名映射仍须保留列名键");
    assertNull(indexFieldMap.get("user_id"), "索引名映射不得混入列名键");
    assertNull(indexFieldMap.get("company_id"), "索引名映射不得混入列名键");
  }

  @Test
  @DisplayName("duplicate-key 已知索引：文案 = 业务字段名 + 冲突值 + 已存在")
  void duplicateKey_knownIndex_mapsToBusinessField() {
    Result<String> result =
        handler.handleDuplicateKeyException(duplicate("138066666666", "sys_user.uk_phone"));
    assertEquals("手机号 138066666666 已存在", result.getMsg());
    // fix-backlog-batch3 任务 1.2：原断言锁 code==0，改为锁精确码 92029（文案不变）
    assertEquals(ErrorCode.DATA_ALREADY_EXISTS.getCode(), result.getCode());
  }

  @Test
  @DisplayName("duplicate-key 其余 6 项逐项映射（配置驱动的完整路由）")
  void duplicateKey_allKnownIndexesMap() {
    assertEquals(
        "邮箱 a@b.com 已存在",
        handler.handleDuplicateKeyException(duplicate("a@b.com", "sys_user.uk_email")).getMsg());
    assertEquals(
        "企业名字 阿尔法 已存在",
        handler
            .handleDuplicateKeyException(duplicate("阿尔法", "customer_company.uk_company_name"))
            .getMsg());
    assertEquals(
        "联系电话 13900000000 已存在",
        handler
            .handleDuplicateKeyException(duplicate("13900000000", "customer_contact.idx_mobile"))
            .getMsg());
    assertEquals(
        "合同编号 HT-001 已存在",
        handler
            .handleDuplicateKeyException(duplicate("HT-001", "contract.uk_contract_no"))
            .getMsg());
    assertEquals(
        "支付编号 PA-001 已存在",
        handler
            .handleDuplicateKeyException(duplicate("PA-001", "payment_record.uk_payment_no"))
            .getMsg());
    assertEquals(
        "发票编号 FP-001 已存在",
        handler
            .handleDuplicateKeyException(duplicate("FP-001", "invoice_info.uk_invoice_no"))
            .getMsg());
  }

  @Test
  @DisplayName("duplicate-key 未知索引：回落默认值「数据」，文案形态不变")
  void duplicateKey_unknownIndex_fallsBackToDefault() {
    Result<String> result =
        handler.handleDuplicateKeyException(duplicate("X-1", "sys_user.uk_not_registered"));
    assertEquals("数据 X-1 已存在", result.getMsg());
  }

  @Test
  @DisplayName("duplicate-key 组合索引：走 composite-unique-index-map，取冲突值首段")
  void duplicateKey_compositeIndex_usesCompositeMap() {
    Result<String> result =
        handler.handleDuplicateKeyException(duplicate("客户A-角色B", "data_share.uk_share_unique"));
    assertEquals("共享 客户A 已存在", result.getMsg());
  }

  @Test
  @DisplayName("越界守卫 ①：裸索引名（无表名前缀）不再数组越界，按同一映射命中")
  void duplicateKey_bareIndexName_doesNotThrow() {
    Result<String> result =
        handler.handleDuplicateKeyException(duplicate("138066666666", "uk_phone"));
    assertEquals("手机号 138066666666 已存在", result.getMsg());
  }

  @Test
  @DisplayName("越界守卫 ①：裸组合索引名同样走组合文案；未知裸名与空名回落「数据」")
  void duplicateKey_bareIndexName_statesCovered() {
    assertEquals(
        "共享 客户A 已存在",
        handler.handleDuplicateKeyException(duplicate("客户A-角色B", "uk_share_unique")).getMsg());
    assertEquals(
        "数据 X-2 已存在",
        handler.handleDuplicateKeyException(duplicate("X-2", "uk_not_registered")).getMsg());
    assertEquals("数据 X-3 已存在", handler.handleDuplicateKeyException(duplicate("X-3", "")).getMsg());
    assertEquals("数据 X-4 已存在", handler.handleDuplicateKeyException(duplicate("X-4", ".")).getMsg());
  }

  @Test
  @DisplayName("duplicate-key 报文不可解析：保留兜底文案")
  void duplicateKey_unparsableMessage_keepsFallback() {
    Result<String> result =
        handler.handleDuplicateKeyException(
            new DuplicateKeyException(
                "boom", new SQLIntegrityConstraintViolationException("nope")));
    assertEquals("数据已存在，无法重复添加", result.getMsg());
  }

  @Test
  @DisplayName("FK 路径回归：外键列名经列名映射为业务字段名（不受索引名键迁出影响）")
  void foreignKey_usesColumnKeyOfSameConfig() {
    Result<String> result =
        invoke(
            "handleDataIntegrityViolationException",
            new SQLIntegrityConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                    + "(`crm`.`contract`, CONSTRAINT `fk_contract_user` FOREIGN KEY (`user_id`) "
                    + "REFERENCES `sys_user` (`id`))"));
    assertEquals(String.format(MessageConstant.ID_IS_NULL, "用户ID"), result.getMsg());
  }

  @Test
  @DisplayName("FK 路径回归：配置里没有的列名回落「未知字段」")
  void foreignKey_unknownColumn_fallsBackToUnknownField() {
    Result<String> result =
        invoke(
            "handleDataIntegrityViolationException",
            new SQLIntegrityConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                    + "(`crm`.`contract`, CONSTRAINT `fk_x` FOREIGN KEY (`ghost_id`) "
                    + "REFERENCES `x` (`id`))"));
    assertEquals(String.format(MessageConstant.ID_IS_NULL, "未知字段"), result.getMsg());
  }

  @Test
  @DisplayName("缺少必填字段 ④：裸列名走列名映射，文案由「数据」改为真实字段名")
  void missingDefaultValue_usesColumnName() {
    Result<String> result =
        invoke(
            "handleSQLException",
            new SQLException("Field `company_id` doesn't have a default value"));
    assertEquals("缺少必填字段：客户ID", result.getMsg());
  }

  @Test
  @DisplayName("缺少必填字段：未登记的裸列名回落「未知字段」；报文无反引号同样安全不越界")
  void missingDefaultValue_unknownColumnAndNoBacktick_staySafe() {
    assertEquals(
        "缺少必填字段：未知字段",
        invoke(
                "handleSQLException",
                new SQLException("Field `ghost_column` doesn't have a default value"))
            .getMsg());
    assertEquals(
        "缺少必填字段：未知字段",
        invoke("handleSQLException", new SQLException("doesn't have a default value")).getMsg());
  }

  @Test
  @DisplayName("⑤ 精确码：duplicate-key 三出口一律 92029，文案逐字节不变")
  void duplicateKey_allThreeExits_carryPreciseCode() {
    Result<String> composite =
        handler.handleDuplicateKeyException(duplicate("客户A-角色B", "data_share.uk_share_unique"));
    Result<String> single =
        handler.handleDuplicateKeyException(duplicate("138066666666", "sys_user.uk_phone"));
    Result<String> fallback =
        handler.handleDuplicateKeyException(
            new DuplicateKeyException(
                "boom", new SQLIntegrityConstraintViolationException("nope")));

    assertAll(
        () -> assertEquals(92029, composite.getCode()),
        () -> assertEquals("共享 客户A 已存在", composite.getMsg()),
        () -> assertEquals(92029, single.getCode()),
        () -> assertEquals("手机号 138066666666 已存在", single.getMsg()),
        () -> assertEquals(92029, fallback.getCode()),
        () -> assertEquals("数据已存在，无法重复添加", fallback.getMsg()));
  }

  @Test
  @DisplayName("⑤ 边界：FK 路径与「缺少必填字段」路径的错误码未动（仍走原先的无码 0）")
  void nonDuplicateKeyPaths_keepLegacyCode() {
    Result<String> foreignKey =
        invoke(
            "handleDataIntegrityViolationException",
            new SQLIntegrityConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                    + "(`crm`.`contract`, CONSTRAINT `fk_contract_user` FOREIGN KEY (`user_id`) "
                    + "REFERENCES `sys_user` (`id`))"));
    Result<String> missingDefault =
        invoke(
            "handleSQLException",
            new SQLException("Field `company_id` doesn't have a default value"));

    assertAll(
        () -> assertEquals(0, foreignKey.getCode()),
        () -> assertEquals(0, missingDefault.getCode()));
  }
}
