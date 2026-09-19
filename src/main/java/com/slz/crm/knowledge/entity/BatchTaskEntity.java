package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 批量上传任务实体。 */
@Data
@TableName("batch_task")
public class BatchTaskEntity {
  /** 数据库主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 批量任务业务键。 */
  private String taskId;

  /** 创建人跨域引用。 */
  private String userId;

  /** 所属知识库 ID 字符串。 */
  private String knowledgeBase;

  /** 统一类目。 */
  private String category;

  /** 文件总数。 */
  private Integer totalFiles;

  /** 成功数。 */
  private Integer successCount;

  /** 失败数。 */
  private Integer failureCount;

  /** 任务状态。 */
  private String status;

  /** 失败原因。 */
  private String errorMessage;

  /** 创建时间。 */
  private LocalDateTime createTime;

  /** 更新时间。 */
  private LocalDateTime updateTime;

  /** 软删除标记。 */
  @TableLogic private Boolean isDeleted;
}
