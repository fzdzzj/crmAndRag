package com.slz.crm.pojo.vo;

import lombok.Data;

/**
 * 协助记录关联的只读业务对象索引。
 *
 * <p>前端只提交 assistId，关联商机、公司和联系人均由后端反查，避免替换业务 ID 越权。
 */
@Data
public class AssistRelatedRecordVO {
  private Long assistId;
  private String modelName;
  private Long recordId;
  private Long opportunityId;
  private String opportunityName;
  private Long companyId;
  private String companyName;
  private Long contactId;
  private String contactName;
}
