package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/** 商业活动视图对象 */
@Data
public class BusinessActivityVO implements Privacy {
  /** 商业活动ID */
  private Long id;

  /** 商业活动标题 */
  private String activityTitle;

  /** 商业活动内容 */
  private String activityContent;

  /** 活动时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime activityTime;

  /** 类型 */
  private String activityType;

  /** 时长 */
  private Integer activityDuration;

  /** 机会ID */
  private Long opportunityId;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  /** 备注 */
  private String remark;

  /** 协助人列表（仅申请人/协助人可见） */
  private List<AssistVO> assistUsers;

  /** 创建者ID */
  private Long creatorId;

  /** 创建者名字 */
  private String creatorName;

  /** 销售机会名称 */
  private String opportunityName;

  /** 联络任务ID（弱关联） */
  private Long taskId;

  /** 关联的联系人列表 */
  private List<ActivityContactVO> contacts;

  /** 关联的用户列表 */
  private List<ActivityUserVO> users;

  /** 活动联系人信息 */
  @Data
  public static class ActivityContactVO {
    /** 联系人ID */
    private Long contactId;

    /** 联系人姓名 */
    private String contactName;

    /** 在活动中的角色 */
    private String contactRole;
  }

  /** 活动用户信息 */
  @Data
  public static class ActivityUserVO {
    /** 用户ID */
    private Long userId;

    /** 用户姓名 */
    private String userName;

    /** 在活动中的角色 */
    private String userRole;
  }

  /**
   * 从Entity创建VO，需要传入创建者名称和销售机会名称
   *
   * @param entity 商业活动实体
   * @param creatorName 创建者姓名
   * @param opportunityName 销售机会名称
   * @return BusinessActivityVO
   */
  public static BusinessActivityVO fromEntity(
      BusinessActivityEntity entity, String creatorName, String opportunityName) {
    if (entity == null) {
      return null;
    }

    BusinessActivityVO vo = new BusinessActivityVO();
    vo.setId(entity.getId());
    vo.setActivityTitle(entity.getActivityTitle());
    vo.setActivityContent(entity.getActivityContent());
    vo.setActivityTime(entity.getActivityTime());
    vo.setActivityType(entity.getActivityType());
    vo.setActivityDuration(entity.getActivityDuration());
    vo.setOpportunityId(entity.getOpportunityId());
    vo.setCreateTime(entity.getCreateTime());
    vo.setRemark(entity.getRemark());
    vo.setCreatorId(entity.getCreatorId());
    vo.setCreatorName(creatorName);
    vo.setOpportunityName(opportunityName);
    vo.setTaskId(entity.getTaskId());

    return vo;
  }
}
