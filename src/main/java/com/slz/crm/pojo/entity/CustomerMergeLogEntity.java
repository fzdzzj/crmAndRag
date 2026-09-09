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
 * 客户合并历史
 */
@Data
@AutoTable
@Table(value = "customer_merge_log", comment = "客户合并历史表")
@TableName("customer_merge_log")
@TableIndex(name = "idx_target", fields = {"targetCompanyId"})
public class CustomerMergeLogEntity {
    @TableId(type = IdType.AUTO)
    @Column(comment = "合并记录ID")
    private Long id;
    /**
     * 目标公司ID
     */
    @Column(comment = "目标客户ID(合并后保留的客户)", type = "bigint", notNull = true)
    private Long targetCompanyId;
    /**
     * 被合并公司ID
     */
    @Column(comment = "被合并客户ID(合并后失效的客户)", type = "bigint", notNull = true)
    private Long mergedCompanyId;
    /**
     * 操作人ID
     */
    @Column(comment = "操作人ID(执行合并的用户)", type = "bigint", notNull = true)
    private Long operatorId;
    /**
     * 操作时间
     */
    @Column(comment = "合并操作时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime mergeTime;
    /**
     * 操作备注
     */
    @Column(comment = "合并说明（如：合并原因、处理方式）", type = "varchar(500)")
    private String remark;


}
