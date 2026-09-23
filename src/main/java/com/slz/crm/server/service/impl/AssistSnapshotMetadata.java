package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照共用的元数据装配（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>纯静态、零依赖：阶段名映射与附件元数据最小集，供商机快照装配与来源记录段装配共用，保证两处口径唯一。
 */
final class AssistSnapshotMetadata {

  private AssistSnapshotMetadata() {}

  /** 商机阶段号 → 中文阶段名；未知阶段与空值各自保留原语义。 */
  static String stageName(Integer stage) {
    String result = null;
    if (stage != null) {
      result =
          switch (stage) {
            case 0 -> "种子";
            case 1 -> "潜在商机";
            case 2 -> "确认商机";
            case 3 -> "储备项目";
            case 4 -> "立项签约";
            case 5 -> "关闭";
            default -> "未知阶段";
          };
    }
    return result;
  }

  /** 快照必须同时记录附件 ID、所属模型和所属记录：历史下载时三者共同用于确认附件确实属于该次协助。 */
  static List<Map<String, Object>> attachmentMetadata(List<ApprovalAttachmentVO> attachments) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (ApprovalAttachmentVO attachment :
        attachments == null ? Collections.<ApprovalAttachmentVO>emptyList() : attachments) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("attachmentId", attachment.getId());
      item.put("modelName", attachment.getModelName());
      item.put("recordId", attachment.getAndId());
      item.put("fileName", attachment.getFileName());
      item.put("fileSize", attachment.getFileSize());
      item.put("fileType", attachment.getFileType());
      item.put("uploaderName", attachment.getUploaderName());
      result.add(item);
    }
    return result;
  }
}
