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
 * 用户交接记录实体类
 * 记录用户离职时的数据交接历史
 */
@Data
@AutoTable
@Table(value = "user_handover", comment = "用户交接记录表")
@TableName("user_handover")
@TableIndex(name = "idx_from_user", fields = {"fromUserId"})
@TableIndex(name = "idx_to_user", fields = {"toUserId"})
@TableIndex(name = "idx_handover_time", fields = {"handoverTime"})
public class UserHandoverEntity {

    @TableId(type = IdType.AUTO)
    @Column(comment = "交接记录ID")
    private Long id;

    /**
     * 原用户ID（离职用户）
     */
    @Column(comment = "原用户ID（离职用户）", type = "bigint", notNull = true)
    private Long fromUserId;

    /**
     * 交接用户ID（接收人）
     */
    @Column(comment = "交接用户ID（接收人）", type = "bigint", notNull = true)
    private Long toUserId;

    /**
     * 交接任务数量
     */
    @Column(comment = "交接任务数量", type = "int", defaultValue = "0")
    private Integer taskCount;

    /**
     * 交接客户数量
     */
    @Column(comment = "交接客户数量", type = "int", defaultValue = "0")
    private Integer customerCount;

    /**
     * 交接销售机会数量
     */
    @Column(comment = "交接销售机会数量", type = "int", defaultValue = "0")
    private Integer opportunityCount;

    /**
     * 交接时间
     */
    @Column(comment = "交接时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime handoverTime;

    /**
     * 操作人ID
     */
    @Column(comment = "操作人ID", type = "bigint", notNull = true)
    private Long operatorId;

    /**
     * 交接备注
     */
    @Column(comment = "交接备注", type = "varchar(500)")
    private String remark;
}
