package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import java.time.LocalDateTime;
import lombok.Data;

/** 商业活动用户关联视图对象 */
@Data
public class BusinessActivityUserVO {
  /** 关联ID */
  private Long id;

  /** 商业活动ID */
  private Long activityId;

  /** 活动名称 */
  private String activityName;

  /** 用户ID */
  private Long userId;

  /** 用户角色 */
  private String userRole;

  /** 创建人ID */
  private Long creatorId;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  /** 创建人姓名 */
  private String creatorName;

  /** 用户姓名 */
  private String userName;

  /**
   * 从Entity创建VO，需要传入活动名称、用户名称和创建人名称
   *
   * @param entity 商业活动用户关联实体
   * @param activityName 活动名称
   * @param userName 用户名称
   * @param creatorName 创建人名称
   * @return BusinessActivityUserVO
   */
  public static BusinessActivityUserVO fromEntity(
      BusinessActivityUserEntity entity, String activityName, String userName, String creatorName) {
    if (entity == null) {
      return null;
    }

    BusinessActivityUserVO vo = new BusinessActivityUserVO();
    vo.setId(entity.getId());
    vo.setActivityId(entity.getActivityId());
    vo.setActivityName(activityName);
    vo.setUserId(entity.getUserId());
    vo.setUserRole(entity.getUserRole());
    vo.setUserName(userName);
    vo.setCreatorId(entity.getCreatorId());
    vo.setCreatorName(creatorName);
    vo.setCreateTime(entity.getCreateTime());

    return vo;
  }
}
