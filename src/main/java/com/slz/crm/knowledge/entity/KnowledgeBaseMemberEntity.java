package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 知识库成员实体。 */
@Data
@TableName("knowledge_base_member")
public class KnowledgeBaseMemberEntity {
  /** 数据库主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 所属知识库。 */
  private Long knowledgeBaseId;

  /** 成员跨域引用。 */
  private String userId;

  /** 成员角色。 */
  private KnowledgeBaseMemberRole memberRole;

  /** 创建时间。 */
  private LocalDateTime createTime;

  /** 更新时间。 */
  private LocalDateTime updateTime;

  /** 软删除标记。 */
  @TableLogic private Boolean isDeleted;
}
