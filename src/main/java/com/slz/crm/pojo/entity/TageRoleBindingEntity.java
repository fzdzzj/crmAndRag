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
 * 标签角色绑定实体
 */
@Data
@AutoTable
@Table(value = "tage_role_binding", comment = "标签角色绑定表")
@TableName("tage_role_binding")
@TableIndex(name = "uk_tage_role", fields = {"tageId", "roleId"}, type = IndexTypeEnum.UNIQUE)
public class TageRoleBindingEntity {
    /**
     * 绑定ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "绑定ID")
    private Long id;
    /**
     * 标签ID
     */
    @Column(comment = "标签ID", type = "bigint", notNull = true)
    private Long tageId;
    /**
     * 角色ID
     */
    @Column(comment = "角色ID", type = "bigint", notNull = true)
    private Long roleId;
    /**
     * 创建时间
     */
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
    /**
     * 创建人ID
     */
    @Column(comment = "创建人ID", type = "bigint", notNull = true)
    private Long creatorId;
}
