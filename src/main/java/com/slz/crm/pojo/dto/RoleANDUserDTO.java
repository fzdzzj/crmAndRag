package com.slz.crm.pojo.dto;

import com.slz.crm.pojo.vo.RoleVO;
import lombok.Data;

/** 角色与用户关联数据传输对象 */
@Data
public class RoleANDUserDTO {

  /** * 角色信息 */
  private RoleVO role;

  /** * 用户信息 */
  private UserDTO user;
}
