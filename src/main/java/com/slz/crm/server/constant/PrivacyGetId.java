package com.slz.crm.server.constant;

import java.util.Arrays;
import java.util.List;

public class PrivacyGetId {

  /** 创建者ID */
  public static final String CREATOR_ID = "getCreatorId";

  /** 负责人ID */
  public static final String OWNER_ID = "getOwnerId";

  /** 操作人ID */
  public static final String OPERATOR_ID = "getOperatorId";

  /** 分享人ID */
  public static final String SHARE_FROM = "getShareFrom";

  /** 审批人ID */
  public static final String APPROVER_ID = "getApproverId";

  /** 用户ID */
  public static final String USER_ID = "getUserId";

  /** 分配人ID */
  public static final String ASSIGNER_ID = "getAssignerId";

  /** 执行人ID */
  public static final String ASSIGNEE_ID = "getAssigneeId";

  /** 申请人ID */
  public static final String APPLICANT_ID = "getApplicantId";

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
