package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 知识库实体。 */
@Data
@TableName("knowledge_base")
public class KnowledgeBaseEntity {
  /** 数据库主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 唯一名称。 */
  private String name;

  /** 展示名称。 */
  private String displayName;

  /** 负责人跨域引用。 */
  private String ownerUserId;

  /** 可见范围。 */
  private KnowledgeBaseVisibility visibility;

  /** 创建时间。 */
  private LocalDateTime createTime;

  /** 更新时间。 */
  private LocalDateTime updateTime;

  /** 软删除标记。 */
  @TableLogic private Boolean isDeleted;
}
