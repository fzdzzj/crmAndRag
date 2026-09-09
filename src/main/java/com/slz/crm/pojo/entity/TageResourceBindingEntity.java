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
 * 标签资源绑定实体
 */
@Data
@AutoTable
@Table(value = "tage_resource_binding", comment = "标签资源绑定表")
@TableName("tage_resource_binding")
@TableIndex(name = "uk_tage_resource", fields = {"tageId", "resourceType", "resourceId"}, type = IndexTypeEnum.UNIQUE)
public class TageResourceBindingEntity {
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
     * 资源类型(表名,如customer_company,sales_opportunity等)
     */
    @Column(comment = "资源类型(表名)", type = "varchar(50)", notNull = true)
    private String resourceType;
    /**
     * 资源ID(对应类型的记录ID)
     */
    @Column(comment = "资源ID(对应类型的记录ID)", type = "bigint", notNull = true)
    private Long resourceId;
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
