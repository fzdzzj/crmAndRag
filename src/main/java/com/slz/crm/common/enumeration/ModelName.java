package com.slz.crm.common.enumeration;

/** 模块名称常量类 用于标识不同的业务模块，主要用于附件管理等需要区分模块的场景 */
public final class ModelName {

  /** 审批附件模块名称 */
  public static final String APPROVAL_ATTACHMENT = "approval_attachment";

  /** 商业活动模块名称 */
  public static final String BUSINESS_ACTIVITY = "business_activity";

  /** 项目文件模块名称 */
  public static final String PROJECT_FILE = "project_file";

  /** 销售阶段推进审批模块名称 */
  public static final String SALES_STAGE_APPROVAL = "sales_stage_approval";

  /** 联络任务模块名称 */
  public static final String CONTACT_TASK = "contact_task";

  /** 协助申请记录模块名称（用于协助交付物附件归属） */
  public static final String ASSIST_REQUEST = "assist_request";

  private ModelName() {
    // 私有构造函数，防止实例化
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }
}
