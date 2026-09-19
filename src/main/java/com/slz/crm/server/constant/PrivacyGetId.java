package com.slz.crm.server.constant;

import java.util.Arrays;
import java.util.List;

public class PrivacyGetId {

  /** 创建者ID */
  public static final String creatorId = "getCreatorId";

  /** 负责人ID */
  public static final String ownerId = "getOwnerId";

  /** 操作人ID */
  public static final String operatorId = "getOperatorId";

  /** 分享人ID */
  public static final String shareFrom = "getShareFrom";

  /** 审批人ID */
  public static final String approverId = "getApproverId";

  /** 用户ID */
  public static final String userId = "getUserId";

  /** 分配人ID */
  public static final String assignerId = "getAssignerId";

  /** 执行人ID */
  public static final String assigneeId = "getAssigneeId";

  /** 申请人ID */
  public static final String applicantId = "getApplicantId";

  public static final List<String> USER_ID_RELATED_FIELDS =
      Arrays.asList(
          "getCreatorId", // 创建者ID
          "getOwnerId", // 负责人ID
          "getOperatorId", // 操作人ID
          "getShareFrom", // 分享人ID
          "getApproverId", // 审批人ID
          "getUserId", // 用户ID
          "getAssignerId", // 分配人ID
          "getAssigneeId", // 执行人ID
          "getApplicantId" // 申请人ID
          );
}
