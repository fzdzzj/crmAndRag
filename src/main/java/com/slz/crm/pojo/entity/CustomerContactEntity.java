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
 * 客户联系人实体类
 */
@Data
@AutoTable
@Table(value = "customer_contact", comment = "客户联系人表")
@TableName("customer_contact")
@TableIndex(name = "idx_mobile", fields = {"mobile"}, type = IndexTypeEnum.UNIQUE)
@TableIndex(name = "idx_company", fields = {"companyId"})
public class CustomerContactEntity {

    /**
     * 客户联系人 ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "联系人唯一标识 ID")
    private Long id;
    /**
     * 客户 ID
     */
    @Column(comment = "所属公司 ID（多对一关联客户公司）", type = "bigint", notNull = true)
    private Long companyId;
    /**
     * 联系人姓名
     */
    @Column(comment = "联系人姓名", type = "varchar(50)", notNull = true)
    private String name;
    /**
     * 联系人职务
     */
    @Column(comment = "职位（在公司中的职务）", type = "varchar(50)")
    private String position;
    /**
     * 联系人固定电话
     */
    @Column(comment = "固定电话", type = "varchar(20)")
    private String phone;
    /**
     * 联系人手机号
     */
    @Column(comment = "手机号码（核心联系方式）", type = "varchar(20)", notNull = true)
    private String mobile;
    /**
     * 联系人邮箱
     */
    @Column(comment = "电子邮箱", type = "varchar(100)")
    private String email;
    /**
     * 联系人性别 (1-男，2-女)
     */
    @Column(comment = "性别（1-男，2-女）", type = "tinyint")
    private Integer gender;
    /**
     * 部门
     */
    @Column(comment = "部门", type = "varchar(50)")
    private String dept;
    /**
     * 客户关系等级
     */
    @Column(comment = "客户关系等级（1-9，9 为最紧密）", type = "tinyint")
    private Integer relationLevel;
    /**
     * 创建人 ID
     */
    @Column(comment = "创建人 ID", type = "bigint", notNull = true)
    private Long creatorId;
    /**
     * 是否删除
     */
    @Column(comment = "是否删除（0-正常，1-回收站）", type = "tinyint", notNull = true, defaultValue = "0")
    private Boolean isDeleted;
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
