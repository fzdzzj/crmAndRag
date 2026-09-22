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

@Slf4j
@RestControllerAdvice
public class SQLExceptionHandler {

  // 正则表达式：匹配 MySQL 唯一约束冲突的错误信息
  // 例如："Duplicate entry '138066666666' for key 'sys_user.uk_phone'"
  private static final Pattern DUPLICATE_KEY_PATTERN =
      Pattern.compile("Duplicate entry '(.*?)' for key '(.*?)'");

  /** 字段名映射 */
  @Value("#{${slz.database-field-map}}")
  private Map<String, String> databaseFieldMap;

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
      Matcher matcher = DUPLICATE_KEY_PATTERN.matcher(errorMsg);
      if (matcher.find()) {
        String duplicateValue = matcher.group(1); // 冲突的值（如 '138066666666'）
        String indexName = matcher.group(2); // 唯一索引名（如 'sys_user.uk_phone'）
        String a;
        // 检查是否为组合唯一约束
        if (compositeUniqueIndexMap.containsKey(a = indexName.split("\\.")[1])) {
          String[] split = duplicateValue.split("-");
          if (split.length > 0) {
            result = Result.error(compositeUniqueIndexMap.get(a) + " " + split[0] + " 已存在");
          } else {
            // 将索引名转换为业务字段名（如 'uk_phone' → '手机号'）
            String fieldName = getFieldNameByIndex(indexName);
            // 返回友好提示
            result = Result.error(fieldName + " " + duplicateValue + " 已存在");
          }
        } else {
          // 将索引名转换为业务字段名（如 'uk_phone' → '手机号'）
          String fieldName = getFieldNameByIndex(indexName);
          // 返回友好提示
          result = Result.error(fieldName + " " + duplicateValue + " 已存在");
        }
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

  /** 根据唯一索引名映射到业务字段名（需根据实际表结构维护） */
  private String getFieldNameByIndex(String indexName) {
    // 示例：索引名与业务字段的映射关系
    // 格式通常为 "表名.索引名"，如 "sys_user.uk_phone"
    String[] parts = indexName.split("\\.");
    final String result;
    if (parts.length < 2) {
      result = "数据";
    } else {
      String index = parts[1]; // 提取索引名（如 'uk_phone'）

      // 维护索引名到业务字段名的映射
      result =
          switch (index) {
            case "uk_phone" -> "手机号";
            case "uk_email" -> "邮箱";
            case "uk_company_name" -> "企业名字";
            case "idx_mobile" -> "联系电话";
            case "uk_contract_no" -> "合同编号";
            case "uk_payment_no" -> "支付编号";
            case "uk_invoice_no" -> "发票编号";
            default -> "数据"; // 未知索引名，返回默认值
          };
    }
    return result;
  }

  /** 根据字段名获取该字段的评论 TODO注意维护 */
  private String getNameByIndex(String indexName) {
    return databaseFieldMap.get(indexName) == null ? "未知字段" : databaseFieldMap.get(indexName);
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
      // 处理空指针异常
      // 获取字段对应名字
      String fieldName = errorMessage.split("`")[1];
      fieldName = getFieldNameByIndex(fieldName);
      log.warn("SQL异常：缺少必填字段，字段名={}", fieldName);
      result = Result.error("缺少必填字段：" + fieldName);
    } else {
      log.error("SQL异常：", e);
      result = Result.error(MessageConstant.SERVER_ERROR);
    }
    return result;
  }
}
