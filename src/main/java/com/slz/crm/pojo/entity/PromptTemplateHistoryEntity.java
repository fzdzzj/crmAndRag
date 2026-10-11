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
 * 提示词模板版本历史实体（表 {@code prompt_template_history}，Flyway V32 建表 + V34 扩 source_version
 * 列，add-prompt-template-admin 任务 1.1 / prompt-template-enhance 任务 1.1）。
 *
 * <p>每次模板变更追加一行：内容变更（CREATE/UPDATE）、启停用（STATUS）、版本回滚（REVERT）均留承。 参照 dynamic_config_history 双表模式，
 * 删除模板时同事务先删 history 再删主表（无外键约束，Service 层保证）。{@code source_version} 为 V34 新增可空列，<b>仅 REVERT
 * 笔非空</b>：记录被回滚的目标版本号，供审计链显式追溯；既有 create/update/status 存量笔为 NULL。
 *
 * <p>{@code @AutoTable} + {@code @TableName} 与 DDL 严格对齐（SchemaDriftAuditIT 面向新增列零 CRITICAL/NEW）。
 */
@Data
@AutoTable
@Table(value = "prompt_template_history", comment = "提示词模板版本历史表")
@TableName("prompt_template_history")
@TableIndex(
    name = "idx_prompt_template_history_tpl_version",
    fields = {"templateId", "version"})
public class PromptTemplateHistoryEntity {

  /** 历史记录ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "历史记录ID", type = "bigint")
  private Long id;

  /** 模板ID */
  @Column(comment = "模板ID", type = "bigint", notNull = true)
  private Long templateId;

  /** 本次变更后的版本号 */
  @Column(comment = "本次变更后的版本号", type = "int", notNull = true)
  private Integer version;

  /** revert 时被回滚的源版本号（V34 新增，仅 REVERT 笔非空，其余笔为 NULL） */
  @Column(comment = "revert 时被回滚的源版本号", type = "int")
  private Integer sourceVersion;

  /** 历史正文快照 */
  @Column(comment = "历史正文快照", type = "text", notNull = true)
  private String content;

  /** 历史变量定义 JSON 数组文本，Java 侧以 String 承载 */
  @Column(comment = "历史变量定义 JSON 数组文本", type = "text")
  private String variables;

  /** 操作类型：CREATE/UPDATE/STATUS */
  @Column(comment = "操作类型：CREATE/UPDATE/STATUS", type = "varchar(16)", notNull = true)
  private String operation;

  /** 操作人用户ID */
  @Column(comment = "操作人用户ID", type = "bigint")
  private Long operator;

  /** 操作时间 */
  @Column(comment = "操作时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime operatedAt;
}
