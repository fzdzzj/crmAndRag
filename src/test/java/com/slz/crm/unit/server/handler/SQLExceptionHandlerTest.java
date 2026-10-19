package com.slz.crm.unit.server.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * SQL 异常映射单测（fix-engineering-debt-nplus1-loop-clone 任务 3.3）。
 *
 * <p>锁定 A-1 双轨收敛：唯一索引名 → 业务字段名的映射只剩 application.yml 的 {@code slz.database-field-map} 一个维护点（原手写
 * switch 已删）。测试直接从 yml 读回映射注入 handler，因此 yml 少键/写错值会直接红， 而不是「测试里复制一份 switch 值」的自证。
 */
@DisplayName("SQL 异常字段映射（配置单源）")
class SQLExceptionHandlerTest {

  private static final Path APPLICATION_YML = Paths.get("src/main/resources/application.yml");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static SQLExceptionHandler handler;

  private static Map<String, String> fieldMap;

  @BeforeAll
  static void loadYamlMapping() throws Exception {
    String yaml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
    Map<String, Object> root = new Yaml().load(yaml);
    Map<String, Object> slz = asMap(root.get("slz"));
    fieldMap =
        OBJECT_MAPPER.readValue(
            (String) slz.get("database-field-map"), new TypeReference<Map<String, String>>() {});
    Map<String, String> compositeMap =
        OBJECT_MAPPER.readValue(
            (String) slz.get("composite-unique-index-map"),
            new TypeReference<Map<String, String>>() {});

    handler = new SQLExceptionHandler();
    ReflectionTestUtils.setField(handler, "databaseFieldMap", fieldMap);
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
  @DisplayName("yml 单源完备：7 个唯一索引名键逐项在配置里且值与原 switch 一致")
  void yml_carriesAllSevenIndexKeys() {
    assertEquals("手机号", fieldMap.get("uk_phone"));
    assertEquals("邮箱", fieldMap.get("uk_email"));
    assertEquals("企业名字", fieldMap.get("uk_company_name"));
    assertEquals("联系电话", fieldMap.get("idx_mobile"));
    assertEquals("合同编号", fieldMap.get("uk_contract_no"));
    assertEquals("支付编号", fieldMap.get("uk_payment_no"));
    assertEquals("发票编号", fieldMap.get("uk_invoice_no"));
  }

  @Test
  @DisplayName("duplicate-key 已知索引：文案 = 业务字段名 + 冲突值 + 已存在")
  void duplicateKey_knownIndex_mapsToBusinessField() {
    Result<String> result =
        handler.handleDuplicateKeyException(duplicate("138066666666", "sys_user.uk_phone"));
    assertEquals("手机号 138066666666 已存在", result.getMsg());
    assertEquals(0, result.getCode());
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
  @DisplayName("duplicate-key 报文不可解析：保留兜底文案")
  void duplicateKey_unparsableMessage_keepsFallback() {
    Result<String> result =
        handler.handleDuplicateKeyException(
            new DuplicateKeyException(
                "boom", new SQLIntegrityConstraintViolationException("nope")));
    assertEquals("数据已存在，无法重复添加", result.getMsg());
  }

  @Test
  @DisplayName("FK 路径回归：外键列名经同一配置映射为业务字段名")
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
  @DisplayName("doesn't-have-default-value 路径回归：裸列名仍回落「数据」，文案不变")
  void missingDefaultValue_keepsBareColumnFallback() {
    Result<String> result =
        invoke(
            "handleSQLException",
            new SQLException("Field `company_id` doesn't have a default value"));
    assertEquals("缺少必填字段：数据", result.getMsg());
  }
}
