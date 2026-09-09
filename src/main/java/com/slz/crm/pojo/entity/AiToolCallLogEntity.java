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
 * AI 工具调用审计实体
 */
@Data
@AutoTable
@Table(value = "ai_tool_call_log", comment = "AI工具调用审计")
@TableName("ai_tool_call_log")
@TableIndex(name = "idx_user_time", fields = {"userId", "createdTime"})
public class AiToolCallLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @Column(comment = "会话ID")
    private Long sessionId;
    @Column(comment = "用户")
    private Long userId;
    @Column(comment = "工具名")
    private String toolName;
    @Column(comment = "调用参数JSON")
    private String args;
    @Column(comment = "返回结果（脱敏后）")
    private String result;
    @Column(comment = "是否成功")
    private Integer success;
    @Column(comment = "耗时（毫秒）")
    private Integer costMs;
    @Column(comment = "调用时间")
    private LocalDateTime createdTime;
}
