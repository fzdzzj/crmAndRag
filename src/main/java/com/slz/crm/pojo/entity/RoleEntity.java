package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@AutoTable
@Table(value = "sys_role", comment = "角色表")
@TableName("sys_role")
@TableIndex(
    name = "uk_role_name",
    fields = {"roleName"},
    type = IndexTypeEnum.UNIQUE)
public class RoleEntity {
  /** 角色ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "角色ID")
  private Long id;

  /** 角色名称 */
  @Column(comment = "角色名称（如：销售经理、管理员）", type = "varchar(50)", notNull = true)
  private String roleName;

  /** 角色简介 */
  @Column(comment = "角色描述（权限说明）", type = "varchar(200)")
  private String roleDesc;

  /** 创建时间 */
  @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;

  /** 是否删除 */
  @Column(comment = "是否删除", type = "bit", notNull = true, defaultValue = "b'0'")
  private Boolean isDeleted;
}
