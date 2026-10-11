package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 提示词模板实体（表 {@code prompt_template}，Flyway V32 建表，add-prompt-template-admin 任务 1.1）。
 *
 * <p>提示词模板是 AI 助手域可复用资产：正文含 {@code {{var}}} 占位符，{@code variables} 为 JSON 数组变量定义
 * （name/required/description），渲染时按定义校验并替换占位符。{@code version} 每次内容变更 +1，作为并发乐观锁依据； {@code status} 0
 * 启用 / 1 停用（启停用不改版本）。历史快照入 {@code prompt_template_history}。
 *
 * <p>参照 AssistRequestEntity 模式：{@code @AutoTable} + {@code @TableName} + 索引注解，DDL 与 V32 严格对齐
 * （SchemaDriftAuditIT 面向新表零 CRITICAL/NEW）。
 */
@Data
@AutoTable
@Table(value = "prompt_template", comment = "提示词模板表")
@TableName("prompt_template")
@TableIndex(
    name = "uk_prompt_template_code",
    fields = {"code"},
    type = IndexTypeEnum.UNIQUE)
@TableIndex(
    name = "idx_prompt_template_category_status",
    fields = {"category", "status"})
public class PromptTemplateEntity {

  /** 提示词模板ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "提示词模板ID", type = "bigint")
  private Long id;

  /** 模板业务键（全局唯一） */
  @Column(comment = "模板业务键（全局唯一）", type = "varchar(64)", notNull = true)
  private String code;

  /** 模板名称 */
  @Column(comment = "模板名称", type = "varchar(128)", notNull = true)
  private String name;

  /** 模板分类（如 agent-executor/agent-reviewer） */
  @Column(comment = "模板分类（如 agent-executor/agent-reviewer）", type = "varchar(64)")
  private String category;

  /** 模板正文（含 {{var}} 占位符） */
  @Column(comment = "模板正文（含 {{var}} 占位符）", type = "text", notNull = true)
  private String content;

  /** 变量定义 JSON 数组文本：[{"name","required","description"}]，Java 侧以 String 承载，坏 JSON 建单/更新即拒 */
  @Column(comment = "变量定义 JSON 数组文本", type = "text")
  private String variables;

  /** 当前版本号（每次内容变更 +1） */
  @Column(comment = "当前版本号（每次内容变更 +1）", type = "int", notNull = true, defaultValue = "1")
  private Integer version;

  /** 状态：0 启用 / 1 停用 */
  @Column(comment = "状态：0 启用 / 1 停用", type = "tinyint", notNull = true, defaultValue = "0")
  private Integer status;

  /** 备注 */
  @Column(comment = "备注", type = "varchar(512)")
  private String remark;

  /** 创建人用户ID */
  @Column(comment = "创建人用户ID", type = "bigint")
  private Long createdBy;

  /** 创建时间 */
  @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createdAt;

  /** 最后修改人用户ID */
  @Column(comment = "最后修改人用户ID", type = "bigint")
  private Long updatedBy;

  /** 最后修改时间 */
  @Column(comment = "最后修改时间", type = "datetime")
  private LocalDateTime updatedAt;
}
