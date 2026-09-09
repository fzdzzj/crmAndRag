package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

@Data
@AutoTable
@Table(value = "permissions", comment = "权限表")
@TableName("permissions")
public class PermissionsEntity {
    /**
     * 权限ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "权限ID，唯一标识")
    private Long id;
    /**
     * 权限名字
     */
    @Column(comment = "权限名字", type = "varchar(50)", notNull = true)
    private String permissionsName;
    /**
     * 权限描述
     */
    @Column(comment = "权限描述", type = "varchar(50)")
    private String permissionsDesc;
}
