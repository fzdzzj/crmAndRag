package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 知识库级检索策略覆盖实体（表 {@code kb_retrieval_strategy}，Flyway V29 建表）。
 *
 * <p>add-per-kb-retrieval-strategy-override 任务 1.3。语义对齐 {@code dynamic_config_item}（V6）软删模式： {@code
 * (kb_id, strategy_key)} 活覆盖唯一（软删行与活行同 key 时复活而非新建）；{@code version} 每次变更自增作乐观并发； {@code
 * is_deleted}=1 表示该库该键覆盖失效、回落全局。
 *
 * <p>注意：本实体不使用 tangzc auto-table 注解（表结构完全由 Flyway V29 管理，生产 auto-table.mode=none， 避免双轨建表冲突）。
 */
@Data
@TableName("kb_retrieval_strategy")
public class KbRetrievalStrategy {

  /** 覆盖记录 ID */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 知识库 ID（对齐 knowledge_base.id bigint） */
  @TableField("kb_id")
  private Long kbId;

  /** 覆盖键（12 键白名单内全限定键，如 rag.retrieval.topK） */
  @TableField("strategy_key")
  private String strategyKey;

  /** 覆盖值（按注册表类型的规范化文本） */
  @TableField("config_value")
  private String configValue;

  /** 当前版本号（每次变更/回滚 +1） */
  private Integer version;

  /** 软删除（true=已删除：覆盖失效回落全局） */
  @TableField("is_deleted")
  private Boolean isDeleted;

  /** 创建人 user:&lt;id&gt; */
  @TableField("created_by")
  private String createdBy;

  /** 最后修改人 user:&lt;id&gt; */
  @TableField("updated_by")
  private String updatedBy;

  /** 创建时间 */
  @TableField("created_at")
  private LocalDateTime createdAt;

  /** 最后更新时间（由应用在变更时显式维护） */
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
