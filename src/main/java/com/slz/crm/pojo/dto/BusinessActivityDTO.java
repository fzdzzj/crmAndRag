package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 商业活动数据传输对象
 *
 * @author evi
 */
@Data
public class BusinessActivityDTO {
  /** 活动ID（仅更新路径必填；创建路径由数据库生成，请求体本就不带，故用 Update 分组避免误拦创建） */
  @NotNull(message = "活动ID不能为空", groups = ValidationGroups.Update.class)
  private Long id;

  /** 活动标题 */
  private String activityTitle;

  /** 活动内容 */
  private String activityContent;

  /** 活动时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime activityTime;

  /** 活动类型 */
  private String activityType;

  /** 活动持续时间（分钟） */
  private Integer activityDuration;

  /** 关联销售机会ID */
  private Long opportunityId;

  /** 创建时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime createTime;

  /** 备注 */
  private String remark;

  /** 创建人ID */
  private Long creatorId;

  /** 参与人员ID列表（级联校验：元素 userId 必填） */
  @Valid private List<BusinessActivityUserDTO> userIdList;

  /** 参与联系人ID列表（级联校验：元素 contactId 必填） */
  @Valid private List<BusinessActivityContactDTO> contactIdList;

  /** 是否添加用户 */
  @JsonProperty("isAddUser")
  private boolean isAddUser;

  /** 是否添加联系人 */
  @JsonProperty("isAddContact")
  private boolean isAddContact;

  /** 联络任务ID（弱关联，用于更新任务状态） */
  private Long taskId;

  /** 联络任务的下一个状态（0未开始/1进行中/2已完成/3已取消） 如果传入了此字段且有关联的taskId，则将任务状态更新为此值 */
  private Integer nextTaskStatus;

  /** 协助申请明细（每位协助人各自的目的/要求）。 */
  private List<com.slz.crm.pojo.dto.AssistApplyItem> assistApplyList;
}
