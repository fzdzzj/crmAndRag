package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.UserEntity;
import java.time.LocalDateTime;
import lombok.Data;

/** 系统用户VO */
@Data
public class UserVO implements Privacy {
  private Long id;
  private String realName;
  private String phone;
  private String email;
  private Long deptId;

  /** 部门名称 */
  private String deptName;

  private Long roleId;
  private String roleName;

  /** 用户账号状态（1-正常��0-冻结，2-离职） */
  private Integer status;

  /** 状态描述 */
  private String statusDesc;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime updateTime;

  @Override
  public Boolean email() {
    this.email = "***";
    return true;
  }

  @Override
  public Boolean phone() {
    this.phone = "***";
    return true;
  }

  /**
   * 从Entity创建VO
   *
   * @param entity 用户实体
   * @return UserVO
   */
  public static UserVO fromEntity(UserEntity entity) {
    UserVO vo = null;
    if (entity != null) {
      vo = new UserVO();
      vo.setId(entity.getId());
      vo.setRealName(entity.getRealName());
      vo.setPhone(entity.getPhone());
      vo.setEmail(entity.getEmail());
      vo.setDeptId(entity.getDeptId());
      vo.setRoleId(entity.getRoleId());
      vo.setStatus(entity.getStatus());
      // 设置状态描述
      String statusDesc =
          switch (entity.getStatus()) {
            case 1 -> "正常";
            case 0 -> "冻结";
            case 2 -> "离职";
            default -> "未知状态";
          };
      vo.setStatusDesc(statusDesc);
      vo.setCreateTime(entity.getCreateTime());
      vo.setUpdateTime(entity.getUpdateTime());
    }
    return vo;
  }
}
