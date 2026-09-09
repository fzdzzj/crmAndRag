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
 * 部门主数据实体类
 * <p>部门归属于集团，客户表仍保留 dept 文本字段，不强外键</p>
 *
 * @author CRM Team
 */
@Data
@AutoTable
@Table(value = "company_dept", comment = "部门主数据表")
@TableName("company_dept")
@TableIndex(name = "uk_company_group_dept", fields = {"groupId", "deptName"}, type = IndexTypeEnum.UNIQUE)
@TableIndex(name = "idx_company_dept_group", fields = {"groupId"})
public class CompanyDeptEntity {

    /**
     * 部门ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "部门ID")
    private Long id;
    /**
     * 所属集团ID
     */
    @Column(comment = "所属集团ID", type = "bigint", notNull = true)
    private Long groupId;
    /**
     * 部门名称
     */
    @Column(comment = "部门名称", type = "varchar(50)", notNull = true)
    private String deptName;
    /**
     * 状态
     */
    @Column(comment = "状态（1-启用，0-停用）", type = "tinyint", notNull = true, defaultValue = "1")
    private Integer status;
    /**
     * 创建时间
     */
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    @Column(comment = "更新时间", type = "datetime")
    private LocalDateTime updateTime;
}
