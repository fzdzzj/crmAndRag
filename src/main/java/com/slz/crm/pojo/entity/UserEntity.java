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
 * 系统用户实体类
 */
@Data
@AutoTable
@Table(value = "sys_user", comment = "系统用户表")
@TableName("sys_user")
@TableIndex(name = "uk_email", fields = {"email"}, type = IndexTypeEnum.UNIQUE)
@TableIndex(name = "uk_phone", fields = {"phone"}, type = IndexTypeEnum.UNIQUE)
public class UserEntity {
    /**
     * 用户ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "用户ID")
    private Long id;
    /**
     * 系统用户密码
     */
    @Column(comment = "加密密码（存储加密后的密码）", type = "varchar(100)", notNull = true)
    private String password;
    /**
     * 用户真实名字
     */
    @Column(comment = "真实姓名", type = "varchar(50)", notNull = true)
    private String realName;
    /**
     * 用户电话
     */
    @Column(comment = "联系电话", type = "varchar(20)")
    private String phone;
    /**
     * 用户邮箱
     */
    @Column(comment = "邮箱", type = "varchar(100)")
    private String email;
    /**
     * 用户部门ID
     */
    @Column(comment = "所属部门ID", type = "bigint")
    private Long deptId;
    /**
     * 用户角色ID
     */
    @Column(comment = "角色ID（控制权限）", type = "bigint", notNull = true)
    private Long roleId;
    /**
     * 用户账号状态（1-正常，0-冻结，2-离职）
     */
    @Column(comment = "状态（1-正常，0-冻结，2-离职，3-眼不见心不烦）", type = "tinyint", notNull = true, defaultValue = "1")
    private Integer status;
    /**
     * 创建人ID
     */
    @Column(comment = "创建者ID", type = "bigint")
    private Long creatorId;
    /**
     * 创建时间
     */
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    @Column(comment = "最后更新时间", type = "datetime")
    private LocalDateTime updateTime;
}
