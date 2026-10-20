package com.slz.crm.pojo.dto;

/**
 * 请求体 Bean Validation 分组（fix-backlog-batch2 ⑧）。
 *
 * <p>同一 DTO 在创建与更新两条写路径上语义不同：创建时身份字段由数据库生成（请求体不带 id），更新时身份字段必填。用分组把「仅更新必填」
 * 的约束表达出来，既不放松更新路径，也不会给创建路径制造假拦截（创建仍走 Default 组）。
 */
public final class ValidationGroups {

  private ValidationGroups() {}

  /** 更新路径校验分组（与 {@link jakarta.validation.groups.Default} 并用，不替代 Default）。 */
  public interface Update {}
}
