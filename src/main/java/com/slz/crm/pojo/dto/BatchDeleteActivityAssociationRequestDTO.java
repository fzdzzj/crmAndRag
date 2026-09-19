package com.slz.crm.pojo.dto;

import java.util.List;
import lombok.Data;

/** 批量删除活动关联请求DTO */
@Data
public class BatchDeleteActivityAssociationRequestDTO {

  /** 联系人ID列表（与用户ID至少提供一个） */
  private List<Long> contactIds;

  /** 用户ID列表（与联系人ID至少提供一个） */
  private List<Long> userIds;
}
