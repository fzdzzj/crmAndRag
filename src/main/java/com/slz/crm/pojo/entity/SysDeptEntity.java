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
 * 部门表
 */
@Data
@AutoTable
@Table(value = "sys_dept", comment = "部门表")
@TableName("sys_dept")
@TableIndex(name = "idx_dept_parent", fields = {"parentId"})
public class SysDeptEntity {
    /**
     * 部门ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "部门ID")
    private Long id;
    /**
     * 部门名称
     */
    @Column(comment = "部门名称", type = "varchar(50)", notNull = true)
    private String deptName;
    /**
     * 上级部门ID（可空，支持多级部门树）
     */
    @Column(comment = "上级部门ID", type = "bigint")
    private Long parentId;
    /**
     * 部门负责人用户ID（可空；用于负责人默认查看本部门及以下数据）
     */
    @Column(comment = "部门负责人用户ID", type = "bigint")
    private Long leaderId;
    /**
     * 排序
     */
    @Column(comment = "排序", type = "int", defaultValue = "0")
    private Integer sort;
    /**
     * 状态（1启用/0停用）
     */
    @Column(comment = "状态（1启用/0停用）", type = "tinyint", notNull = true, defaultValue = "1")
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
