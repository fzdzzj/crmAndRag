package com.slz.crm.server.handler;

import com.slz.crm.common.result.Result;
import com.slz.crm.server.constant.MessageConstant;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SQL 异常 → 业务文案的统一翻译层。
 *
 * <p>键空间隔离（fix-backlog-batch2 ⑦-③）：唯一索引名（如 {@code uk_phone}）与列名（如 {@code user_id}）是两个互不相交的键空间， 分别由
 * {@code slz.unique-index-field-map} 与 {@code slz.database-field-map} 承载，任一键只在一个映射里有定义。
 *
 * <ul>
 *   <li>duplicate-key 路径：组合索引命中 → 组合文案；否则单列索引文案（索引名映射）；索引名不可解析 → 兜底「数据」；
 *   <li>FK 路径与「缺少默认值」路径：一律按列名映射直查（未知列名回落「未知字段」）。
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class SQLExceptionHandler {

  /** 索引名/列名都未登记时的兜底业务字段名（对外文案保持不变）。 */
  private static final String DEFAULT_FIELD_NAME = "数据";

  /** 未知列名兜底文案（外键与「缺少必填字段」两条列名路径共用）。 */
  private static final String UNKNOWN_FIELD_NAME = "未知字段";

  // 正则表达式：匹配 MySQL 唯一约束冲突的错误信息
  // 例如："Duplicate entry '138066666666' for key 'sys_user.uk_phone'"
  private static final Pattern DUPLICATE_KEY_PATTERN =
      Pattern.compile("Duplicate entry '(.*?)' for key '(.*?)'");

  /** 列名映射（外键列名、「缺少必填字段」裸列名两条路径专用；不含任何索引名键） */
  @Value("#{${slz.database-field-map}}")
  private Map<String, String> databaseFieldMap;

  /** 唯一索引名映射（duplicate-key 路径专用；键为不含表名前缀的裸索引名，与列名键空间隔离） */
  @Value("#{${slz.unique-index-field-map}}")
  private Map<String, String> uniqueIndexFieldMap;

  /** 组合唯一约束映射 */
  @Value("#{${slz.composite-unique-index-map}}")
  private Map<String, String> compositeUniqueIndexMap;

  /** 处理数据库唯一约束冲突异常 */
  @ExceptionHandler(DuplicateKeyException.class)
  public Result<String> handleDuplicateKeyException(DuplicateKeyException e) {
    // 获取数据库原生异常（可能被多层包装）
    Throwable rootCause = e.getRootCause();
    final Result<String> result;
    if (rootCause instanceof SQLIntegrityConstraintViolationException sqlEx) {
      String errorMsg = sqlEx.getMessage();
      // 解析错误信息，提取冲突的值和索引名
      Matcher matcher = DUPLICATE_KEY_PATTERN.matcher(errorMsg == null ? "" : errorMsg);
      if (matcher.find()) {
        String duplicateValue = matcher.group(1); // 冲突的值（如 '138066666666'）
        // 索引名（MySQL 8 形如 'sys_user.uk_phone'，也兼容只有裸名 'uk_phone' 的报文）
        String indexName = matcher.group(2);
        result = duplicateKeyResult(indexName, duplicateValue);
      } else {
        log.error("未知数据库字段冲突！！！{}", e.getMessage());
        // 若解析失败，返回默认提示
        result = Result.error("数据已存在，无法重复添加");
      }
    } else {
      log.error("未知数据库字段冲突！！！{}", e.getMessage());
      // 若解析失败，返回默认提示
      result = Result.error("数据已存在，无法重复添加");
    }
    return result;
  }

  /**
   * 唯一约束冲突文案三态（fix-backlog-batch2 ⑦-②）：组合索引命中 → 组合文案；未命中 → 单列索引文案；索引名不可解析 → 兜底文案。
   *
   * <p>索引名先经 {@link #bareIndexName(String)} 归一（去表名前缀、容忍裸名/空名/尾部点号），任何输入都不会触发数组越界。
   */
  private Result<String> duplicateKeyResult(String indexName, String duplicateValue) {
    String bareIndex = bareIndexName(indexName);
    String compositeName = bareIndex == null ? null : compositeUniqueIndexMap.get(bareIndex);
    final Result<String> result;
    if (compositeName != null) {
      result = Result.error(compositeName + " " + firstValueSegment(duplicateValue) + " 已存在");
    } else {
      // 将索引名转换为业务字段名（如 'uk_phone' → '手机号'），未登记索引名回落「数据」
      result = Result.error(getFieldNameByIndex(indexName) + " " + duplicateValue + " 已存在");
    }
    return result;
  }

  /** 组合冲突值的首段（如 '客户A-角色B' → '客户A'）；无分隔符时全值即首段（split 至少返回一个元素，不会越界）。 */
  private static String firstValueSegment(String duplicateValue) {
    return duplicateValue.split("-")[0];
  }

  /**
   * 归一索引名：取最后一个 {@code '.'} 之后的裸索引名，因此 {@code "sys_user.uk_phone"}、{@code "uk_phone"} 等价；
   * 空名/尾部点号/全空白一律返回 {@code null}（调用侧走兜底），不再对 split 结果做裸下标访问。
   */
  private static String bareIndexName(String indexName) {
    String result = null;
    if (indexName != null) {
      int dot = indexName.lastIndexOf('.');
      String bare = (dot < 0 ? indexName : indexName.substring(dot + 1)).trim();
      if (!bare.isEmpty()) {
        result = bare;
      }
    }
    return result;
  }

  /**
   * 根据唯一索引名映射到业务字段名（映射单源：application.yml 的 slz.unique-index-field-map）。
   *
   * <p>索引名格式通常为 "表名.索引名"（如 "sys_user.uk_phone"），裸索引名等价处理；未登记索引名或空名一律回落默认值「数据」。
   */
  private String getFieldNameByIndex(String indexName) {
    String bareIndex = bareIndexName(indexName);
    String fieldName = bareIndex == null ? null : uniqueIndexFieldMap.get(bareIndex);
    return fieldName == null ? DEFAULT_FIELD_NAME : fieldName;
  }

  /** 根据列名获取该列的中文名（映射单源同上，缺失或空名时回落「未知字段」） */
  private String getNameByIndex(String columnName) {
    String fieldName = columnName == null ? null : databaseFieldMap.get(columnName);
    return fieldName == null ? UNKNOWN_FIELD_NAME : fieldName;
  }

  // 正则表达的字段
  private static final Pattern FK_FIELD_PATTERN = Pattern.compile("FOREIGN KEY \\(`(.*?)`\\)");

  /**
   * 捕获外键冲突，统一处理关联ID不存在问题
   *
   * @param e 数据库完整性约束违反异常
   * @return 错误结果
   */
  @ExceptionHandler(SQLIntegrityConstraintViolationException.class)
  private Result<String> handleDataIntegrityViolationException(
      SQLIntegrityConstraintViolationException e) {
    String errorMessage = e.getMessage();
    final Result<String> result;
    if (errorMessage == null || errorMessage.isEmpty()) {
      result = null;
    } else {
      String errorMsg;
      String fieldName = null;

      // 判断错误是否由什么操作产生
      if (errorMessage.contains("insert") || errorMessage.contains("update")) {
        // 3. 用正则表达式匹配外键字段名
        Matcher matcher = FK_FIELD_PATTERN.matcher(errorMessage);
        if (matcher.find()) {
          // 返回匹配到的字段名（如 user_id）
          fieldName = matcher.group(1);
        }

        // 4. 构建错误信息
        if (fieldName != null) {
          errorMsg = String.format(MessageConstant.ID_IS_NULL, getNameByIndex(fieldName));
          log.info("外键约束冲突，字段：{}，错误信息：{}", fieldName, errorMessage);
        } else {
          log.error("未知字段存在关联数据!!!{}", errorMessage);
          errorMsg = "未知字段存在关联数据";
        }
      } else {
        log.error("未知错误!!!{}", errorMessage);
        errorMsg = "未知错误";
      }

      result = Result.error(errorMsg);
    }
    return result;
  }

  /** 捕获SQL异常，并且对其中非空字段为空进行处理 */
  @ExceptionHandler(SQLException.class)
  private Result<String> handleSQLException(SQLException e) {
    String errorMessage = e.getMessage();
    final Result<String> result;
    if (errorMessage == null || errorMessage.isEmpty()) {
      log.error("SQL异常：消息为空", e);
      // 服务器异常
      result = Result.error(MessageConstant.SERVER_ERROR);
    } else if (errorMessage.contains("doesn't have a default value")) {
      // 报文形如 "Field `company_id` doesn't have a default value"：此处是裸列名，按列名映射直查
      // （fix-backlog-batch2 ⑦-④：曾误走索引名映射致文案回落「数据」，现给出真实字段名）
      String fieldName = getNameByIndex(bareColumnName(errorMessage));
      log.warn("SQL异常：缺少必填字段，字段名={}", fieldName);
      result = Result.error("缺少必填字段：" + fieldName);
    } else {
      log.error("SQL异常：", e);
      result = Result.error(MessageConstant.SERVER_ERROR);
    }
    return result;
  }

  /** 从缺失默认值报文里取裸列名；报文不含反引号时返回 {@code null}（调用侧回落「未知字段」），不做裸下标访问。 */
  private static String bareColumnName(String errorMessage) {
    String[] parts = errorMessage.split("`");
    return parts.length > 1 ? parts[1] : null;
  }
}
