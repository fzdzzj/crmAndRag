package com.slz.crm.pojo.dto;

import lombok.Data;

/** 主管分派公海客户请求 DTO（add-customer-public-sea-pool 任务 2.4）。 公海客户 → 指定销售人员（异步向量同步，Qdrant 权限锚与所有者一致）。 */
@Data
public class CustomerPoolAssignDTO {

  /** 被分派的客户公司 ID（需处于公海 pool_status=1） */
  private Long companyId;

  /** 目标销售人员 ID（校验其私海保有量配额后成为新负责人） */
  private Long targetUserId;

  /** 分派原因或备注（可选） */
  private String reason;
}
