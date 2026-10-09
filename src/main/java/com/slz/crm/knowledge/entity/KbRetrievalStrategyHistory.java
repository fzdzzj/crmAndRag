package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 知识库级检索策略版本历史实体（表 {@code kb_retrieval_strategy_history}，Flyway V29 建表）。
 *
 * <p>add-per-kb-retrieval-strategy-override 任务 1.3。语义对齐 {@code dynamic_config_history}（V6）：每次变更
 * （CREATE/UPDATE/DELETE/REVIVE/ROLLBACK）追加一行；回滚语义：回滚到版本 N = 取该版本行的 {@code new_value} 作为当前值（回滚自身也 +1
 * 版本并留痕）。冗余存 {@code strategy_key}/{@code kb_id} 便于按键查历史与回滚定位。
 */
@Data
@TableName("kb_retrieval_strategy_history")
public class KbRetrievalStrategyHistory {

  /** 历史记录 ID */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 覆盖记录 ID（软删后仍保留引用） */
  @TableField("strategy_id")
  private Long strategyId;

  /** 知识库 ID（冗余存储） */
  @TableField("kb_id")
  private Long kbId;

  /** 覆盖键（冗余存储） */
  @TableField("strategy_key")
  private String strategyKey;

  /** 本次变更后的版本号（回滚/复活也 +1） */
  private Integer version;

  /** 操作类型：CREATE/UPDATE/DELETE/REVIVE/ROLLBACK */
  @TableField("operation_type")
  private String operationType;

  /** 变更前值（CREATE/REVIVE 为空） */
  @TableField("old_value")
  private String oldValue;

  /** 变更后值（DELETE 为空） */
  @TableField("new_value")
  private String newValue;

  /** 操作人 user:&lt;id&gt; */
  @TableField("operator_ref")
  private String operatorRef;

  /** 操作备注（回滚时记录目标版本号） */
  private String remark;

  /** 操作时间 */
  @TableField("created_at")
  private LocalDateTime createdAt;
}
