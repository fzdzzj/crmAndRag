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

@Data
@AutoTable
@Table(value = "role_permissions", comment = "角色权限表")
@TableName("role_permissions")
@TableIndex(name = "uk_role_permission", fields = {"permissionsId", "roleId"}, type = IndexTypeEnum.UNIQUE)
public class RolePermissionsEntity {
    /**
     * 角色权限ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "角色权限ID")
    private Long id;
    /**
     * 权限ID
     */
    @Column(comment = "权限ID", type = "bigint", notNull = true)
    private Long permissionsId;
    /**
     * 角色ID
     */
    @Column(comment = "角色ID", type = "bigint", notNull = true)
    private Long roleId;
    /**
     * 创建人ID
     */
    @Column(comment = "创建者ID", type = "bigint")
    private Long creatorId;
    /**
     * 是否删除
     */
    @Column(comment = "是否删除", type = "bit", notNull = true, defaultValue = "b'0'")
    private Boolean isDeleted;
}
