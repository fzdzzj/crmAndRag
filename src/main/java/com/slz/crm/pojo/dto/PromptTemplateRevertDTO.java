package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 提示词模板回滚入参 DTO（prompt-template-enhance 任务 2.1）。
 *
 * <p>{@code targetVersion} 是待恢复为目标的历史版本号（必须存在于该模板 history）；{@code expectedVersion} 为当前版本
 * 乐观锁基线——与模板当前版本不符即抛版本冲突（92001），防并发回滚覆盖。
 */
@Data
public class PromptTemplateRevertDTO {

  /** 目标历史版本号（内容+variables 将被作为新版本落档） */
  private Integer targetVersion;

  /** 当前版本乐观锁基线 */
  private Integer expectedVersion;
}
