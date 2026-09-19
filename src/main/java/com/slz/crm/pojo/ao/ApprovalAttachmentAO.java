package com.slz.crm.pojo.ao;

import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import lombok.Data;

@Data
public class ApprovalAttachmentAO {
  private ApprovalAttachmentEntity attachment;
  private byte[] fileBytes;
}
