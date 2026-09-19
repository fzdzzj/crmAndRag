package com.slz.crm.pojo.dto;

import lombok.Data;

/** 用户交接请求DTO */
@Data
public class UserHandoverDTO {

  /** 离职用户ID（必填） */
  private Long fromUserId;

  /** 接收用户ID（必填） */
  private Long toUserId;

  /** 交接备注（可选） */
  private String remark;
}
