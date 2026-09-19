package com.slz.crm.pojo.dto;

import java.util.List;
import lombok.Data;

@Data
public class GetUserDTO {
  /** 系统用户ID */
  private List<Long> id;

  /** 用户真实名字 */
  private String realName;

  /** 用户电话 */
  private String phone;

  /** 用户邮箱 */
  private String email;

  /** 用户角色ID */
  private List<Long> roleId;

  /** 角色名 */
  private String roleName;

  /** 用户账号状态（1-正常，0-冻结，2-离职） */
  private List<Integer> status;

  /** 创建人ID */
  private Long creatorId;

  /** 创建人名称 */
  private String creatorName;

  private Integer pageNum;
  private Integer pageSize;

  public Integer getPageNum() {
    if (pageNum == null) {
      return 1;
    }
    return pageNum;
  }

  public Integer getPageSize() {
    if (pageSize == null) {
      return 10;
    }
    return pageSize;
  }
}
