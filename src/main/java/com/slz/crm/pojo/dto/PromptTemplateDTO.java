package com.slz.crm.pojo.dto;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import lombok.Data;

/**
 * 提示词模板创建/更新入参 DTO（add-prompt-template-admin 任务 2.3）。
 *
 * <p>{@code code}/{@code name}/{@code content} 必填；{@code variables} 为 JSON 数组文本 （坏 JSON 由 Service
 * 在建单/更新时校验拒绝）。
 */
@Data
public class PromptTemplateDTO {

  /** 更新时必填（乐观锁）；创建时为空 */
  private Long id;

  /** 更新时必填（乐观锁基线版本）；创建时忽略 */
  private Integer expectedVersion;

  /** 模板业务键（全局唯一） */
  private String code;

  /** 模板名称 */
  private String name;

  /** 模板分类 */
  private String category;

  /** 模板正文（含 {{var}} 占位符） */
  private String content;

  /** 变量定义 JSON 数组文本：[{"name","required","description"}] */
  private String variables;

  /** 备注 */
  private String remark;

  /** 创建/更新前的服务层统一入参校验。 */
  public void validateBase() {
    if (code == null || code.isBlank()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "模板编码 code 不能为空");
    }
    if (name == null || name.isBlank()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "模板名称 name 不能为空");
    }
    if (content == null || content.isBlank()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "模板正文 content 不能为空");
    }
  }
}
