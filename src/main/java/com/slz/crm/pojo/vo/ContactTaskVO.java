package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/** 联络任务VO */
@Data
public class ContactTaskVO {
  /** 任务ID */
  private Long id;

  /** 任务标题 */
  private String taskTitle;

  /** 关联客户公司ID */
  private Long companyId;

  /** 关联客户公司名称 */
  private String companyName;

  /** 关联联系人ID */
  private Long contactId;

  /** 关联联系人姓名 */
  private String contactName;

  /** 关联销售机会ID */
  private Long opportunityId;

  /** 关联销售机会标题 */
  private String opportunityTitle;

  /** 任务类型 */
  private String taskType;

  /** 任务内容 */
  private String taskContent;

  /** 开始时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime startTime;

  /** 结束时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime endTime;

  /** 优先级（低/中/高/紧急） */
  private String priority;

  /** 状态（未开始/进行中/已完成/已取消） */
  private String status;

  /** 任务执行人ID */
  private Long assigneeId;

  /** 任务执行人姓名 */
  private String assigneeName;

  /** 指派人ID（分配任务的人） */
  private Long assignerId;

  /** 指派人姓名（分配任务的人） */
  private String assignerName;

  /** 创建人ID */
  private Long creatorId;

  /** 创建人姓名 */
  private String creatorName;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  /** 最后更新时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime updateTime;

  /** 相关的业务活动列表 */
  private List<BusinessActivityVO> businessActivities;

  /** 协助人列表（仅申请人/协助人可见） */
  private List<AssistVO> assistUsers;

  /**
   * 从Entity创建VO，需要传入公司名称、联系人名称、销售机会标题、执行人姓名、指派人姓名和创建人姓名
   *
   * @param entity 联络任务实体
   * @param companyName 公司名称
   * @param contactName 联系人姓名
   * @param opportunityTitle 销售机会标题
   * @param assigneeName 执行人姓名
   * @param assignerName 指派人姓名
   * @param creatorName 创建人姓名
   * @return ContactTaskVO
   */
  public static ContactTaskVO fromEntity(
      ContactTaskEntity entity,
      String companyName,
      String contactName,
      String opportunityTitle,
      String assigneeName,
      String assignerName,
      String creatorName) {
    ContactTaskVO vo = null;
    if (entity != null) {
      vo =
          buildContactTaskVO(
              entity,
              companyName,
              contactName,
              opportunityTitle,
              assigneeName,
              assignerName,
              creatorName);
    }
    return vo;
  }

  /** 由实体逐字段装配 VO：基础字段 + 时间转换 + 优先级/状态文案 */
  private static ContactTaskVO buildContactTaskVO(
      ContactTaskEntity entity,
      String companyName,
      String contactName,
      String opportunityTitle,
      String assigneeName,
      String assignerName,
      String creatorName) {
    ContactTaskVO vo = new ContactTaskVO();
    vo.setId(entity.getId());
    vo.setTaskTitle(entity.getTaskTitle());
    vo.setCompanyId(entity.getCompanyId());
    vo.setCompanyName(companyName);
    vo.setContactId(entity.getContactId());
    vo.setContactName(contactName);
    vo.setOpportunityId(entity.getOpportunityId());
    vo.setOpportunityTitle(opportunityTitle);
    vo.setTaskType(entity.getTaskType());
    vo.setTaskContent(entity.getTaskContent());
    vo.setStartTime(toLocalDateTime(entity.getStartTime()));
    vo.setEndTime(toLocalDateTime(entity.getEndTime()));
    vo.setPriority(priorityText(entity.getPriority()));
    vo.setStatus(statusText(entity.getStatus()));
    vo.setAssigneeId(entity.getAssigneeId());
    vo.setAssigneeName(assigneeName);
    vo.setAssignerId(entity.getAssignerId());
    vo.setAssignerName(assignerName);
    vo.setCreatorId(entity.getCreatorId());
    vo.setCreatorName(creatorName);
    vo.setCreateTime(toLocalDateTime(entity.getCreateTime()));
    vo.setUpdateTime(toLocalDateTime(entity.getUpdateTime()));
    return vo;
  }

  /** Date → LocalDateTime（系统时区），null 安全 */
  private static java.time.LocalDateTime toLocalDateTime(java.util.Date date) {
    return date != null
        ? date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        : null;
  }

  /** 优先级数值转文案（0-2 低 / 3-5 中 / 6-8 高 / 9 紧急） */
  private static String priorityText(Integer priority) {
    return switch (priority) {
      case 0, 1, 2 -> "低";
      case 3, 4, 5 -> "中";
      case 6, 7, 8 -> "高";
      case 9 -> "紧急";
      default -> "未设置";
    };
  }

  /** 任务状态数值转文案 */
  private static String statusText(Integer status) {
    return switch (status) {
      case 0 -> "未开始";
      case 1 -> "进行中";
      case 2 -> "已完成";
      case 3 -> "已取消";
      default -> "未知状态";
    };
  }
}
