package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提示词模板使用反馈实体（表 {@code prompt_template_feedback}，Flyway V34 建表，prompt-template-enhance 任务 1.1）。
 *
 * <p>记录模板某个版本的评分（score 1-5）与文字意见（comment ≤512），供模板管理者据此判断版本效果并决定是否回滚。 参照 prompt_template_history
 * 单表模式，索引 template_id+version 便于按模板版本检索。
 *
 * <p>{@code @AutoTable} + {@code @TableName} 与 V34 DDL 严格对齐（SchemaDriftAuditIT 面向新表零 CRITICAL/NEW）。
 */
@Data
@AutoTable
@Table(value = "prompt_template_feedback", comment = "提示词模板使用反馈表")
@TableName("prompt_template_feedback")
@TableIndex(
    name = "idx_prompt_template_feedback_tpl_version",
    fields = {"templateId", "version"})
public class PromptTemplateFeedbackEntity {

  /** 反馈记录ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "反馈记录ID", type = "bigint")
  private Long id;

  /** 提示词模板ID */
  @Column(comment = "提示词模板ID", type = "bigint", notNull = true)
  private Long templateId;

  /** 被反馈的模板版本号 */
  @Column(comment = "被反馈的模板版本号", type = "int", notNull = true)
  private Integer version;

  /** 评分 1-5 */
  @Column(comment = "评分 1-5", type = "tinyint", notNull = true)
  private Integer score;

  /** 反馈意见（≤512 字符） */
  @Column(comment = "反馈意见", type = "varchar(512)")
  private String comment;

  /** 反馈人用户ID */
  @Column(comment = "反馈人用户ID", type = "bigint")
  private Long operator;

  /** 反馈时间 */
  @Column(comment = "反馈时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createdAt;
}
