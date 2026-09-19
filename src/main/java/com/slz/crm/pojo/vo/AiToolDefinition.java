package com.slz.crm.pojo.vo;

import com.slz.crm.common.enumeration.PermissionOperates;
import lombok.Builder;
import lombok.Data;

/** 工具定义描述（用于 System Prompt） */
@Data
@Builder
public class AiToolDefinition {
  private String name;
  private String description;
  private PermissionOperates requiredPermission;
}
