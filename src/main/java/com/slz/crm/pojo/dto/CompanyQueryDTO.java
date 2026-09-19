package com.slz.crm.pojo.dto;

import java.util.Map;
import lombok.Data;

/**
 * 公司查询条件数据传输对象
 *
 * @author evi
 */
@Data
public class CompanyQueryDTO {
  /** * 精确查询字段 (字段名 -> 值) */
  private Map<String, Object> exactFields;

  /** * 模糊查询字段 (字段名 -> 值) */
  private Map<String, String> fuzzyFields;

  /** * 组合查询逻辑 (AND/OR) 默认值：AND */
  private String logic = "AND";
}
