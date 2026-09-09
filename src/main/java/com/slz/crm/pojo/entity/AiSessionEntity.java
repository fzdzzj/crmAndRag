package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话实体
 */
@Data
@AutoTable
@Table(value = "ai_session", comment = "AI会话")
@TableName("ai_session")
@TableIndex(name = "idx_user_time", fields = {"userId", "updatedTime"})
public class AiSessionEntity {
    @TableId(type = IdType.AUTO)
    @Column(comment = "会话ID")
    private Long id;
    @Column(comment = "所属用户")
    private Long userId;
    @Column(comment = "会话标题")
    private String title;
    @Column(comment = "1=活跃 0=归档")
    private Integer status;
    @Column(comment = "创建时间")
    private LocalDateTime createdTime;
    @Column(comment = "最后活跃时间")
    private LocalDateTime updatedTime;
}
