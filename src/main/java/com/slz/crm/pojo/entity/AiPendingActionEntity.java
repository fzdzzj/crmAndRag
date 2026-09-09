package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 待确认操作实体
 */
@Data
@AutoTable
@Table(value = "ai_pending_action", comment = "AI待确认操作")
@TableName("ai_pending_action")
@TableIndex(name = "uk_pending", fields = {"pendingId"}, type = IndexTypeEnum.UNIQUE)
@TableIndex(name = "idx_user_status", fields = {"userId", "status"})
@TableIndex(name = "idx_session", fields = {"sessionId"})
public class AiPendingActionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @Column(comment = "对外标识")
    private String pendingId;
    @Column(comment = "来源会话")
    private Long sessionId;
    @Column(comment = "操作归属用户")
    private Long userId;
    @Column(comment = "操作类型 CREATE_ORDER/CREATE_CONTRACT等")
    private String actionType;
    @Column(comment = "DRAFTING/PENDING/CONFIRMED/CANCELLED/EXPIRED/FAILED")
    private String status;
    @Column(comment = "执行参数JSON，以本字段为准")
    private String payload;
    @Column(comment = "缺失字段JSON数组")
    private String missingFields;
    @Column(comment = "已追问轮数")
    private Integer askRound;
    @Column(comment = "确认卡片展示摘要JSON")
    private String preview;
    @Column(comment = "创建时间")
    private LocalDateTime createdTime;
    @Column(comment = "更新时间")
    private LocalDateTime updatedTime;
    @Column(comment = "确认/取消时间")
    private LocalDateTime confirmedTime;
    @Column(comment = "PENDING超时时间")
    private LocalDateTime expireTime;
    @Column(comment = "执行结果JSON")
    private String result;
}
