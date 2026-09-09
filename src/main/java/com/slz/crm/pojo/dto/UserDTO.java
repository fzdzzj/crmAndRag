package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 系统用户DTO
 */
@Data
public class UserDTO {
    /** * 用户ID */
    private Long id;
    /** * 用户密码 */
    private String password;
    /** * 用户真实姓名 */
    private String realName;
    /** * 用户手机号码 */
    private String phone;
    /** * 用户邮箱地址 */
    private String email;
    /** * 所属部门ID */
    private Long deptId;
    /** * 用户角色ID */
    private Long roleId;
    /** * 用户状态 0：禁用 1：启用 */
    private Integer status;
}
