package com.slz.crm.server.constant;

public class MessageConstant {

    // ============================== 公共模块错误提示 ==============================
    public static final String FILE_IS_EMPTY = "FILE_IS_EMPTY:文件格式错误";
    public static final String IPHONE_IS_ERROR = "IPHONE_IS_ERROR:手机号格式错误";
    public static final String FILE_IS_NULL = "FILE_IS_NULL:文件不能为空";
    public static final String CONTACT_IS_NULL = "CONTACT_IS_NULL:负责人为空";
    public static final String COMPANY_IS_NULL = "COMPANY_IS_NULL:公司为空";
    public static final String DATA_IS_NULL = "DATA_IS_NULL:数据为空";
    public static final String GET_FIELD_MAPPING_FAIL = "获取字段映射失败：%s.%s";
    public static final String UPDATE_ERR = "UPDATE_ERR:更新失败";
    public static final String FILE_CREATE_ERROR = "FILE_CREATE_ERROR:文件创建失败";
    public static final String FILE_APPROVAL_ID_ERROR = "FILE_APPROVAL_ID_ERROR:文件审批ID错误";
    public static final String CHUNK_UPLOAD_PARAM_ERROR = "CHUNK_UPLOAD_PARAM_ERROR:分片上传参数不完整";
    public static final String CHUNK_UPLOAD_NOT_COMPLETE = "CHUNK_UPLOAD_NOT_COMPLETE:分片未上传完成，请先完成所有分片上传再提交审批";
    public static final String NORMAL_UPLOAD_FILE_DATA_ERROR = "NORMAL_UPLOAD_FILE_DATA_ERROR:普通上传方式需要提供fileData";
    public static final String CHUNK_FILE_ID_ERROR = "CHUNK_FILE_ID_ERROR:文件标识和分片索引不能为空";
    public static final String CHUNK_TOTAL_CHUNKS_ERROR = "CHUNK_TOTAL_CHUNKS_ERROR:文件标识和总分片数不能为空";
    public static final String ID_ERROR = "ID_ERROR:ID错误";
    //TODO 记得删
    public static final String SERVER_ERROR = "SERVER_ERROR:服务器异常(后端有bug，快去修！！！)";

    public static final String PARAM_ERROR = "PARAM_ERROR:参数错误";
    public static final String EXCEL_IS_ERROR = "EXCEL_IS_ERROR:第【%s】行格式错误或必要信息缺失";
    public static final String EXCEL_COMPANY_IS_ERROR = "EXCEL_COMPANY_IS_ERROR:第【%s】行公司不存在";
    public static final String EXCEL_CONTACT_IS_ERROR = "EXCEL_CONTACT_IS_ERROR:第【%s】行负责人不存在";
    public static final String ID_IS_NULL = "ID_IS_NULL：【%s】不存在";
    public static final String ID_LIST_IS_NULL = "ID_LIST_IS_NULL:id列表不能为空";
    public static final String EMAIL_IS_EXIST = "EMAIL_IS_EXIST:邮箱已存在";
    public static final String EMAIL_FORMAT_ERR = "EMAIL_FORMAT_ERR:邮箱格式错误";
    public static final String GROUP_FIELD_IS_ERROR = "GROUP_FIELD_IS_ERROR:分组字段错误";

    // ============================== 客户管理模块错误提示 ==============================
    public static final String COMPANY_IS_NOT_EXIST = "COMPANY_IS_NOT_EXIST:公司不存在";
    public static final String COMPANY_DATA_IS_NULL = "COMPANY_DATA_IS_NULL:公司数据不能为空";
    public static final String PAGE_DATA_IS_NULL = "PAGE_DATA_IS_NULL:分页数据不能为空";


    // ============================== 客户联系人管理模块错误提示 ==============================
    public static final String CONTACT_IS_EXIST = "CONTACT_IS_EXIST:联系人已存在";
    public static final String CONTACT_IS_NOT_EXIST = "CONTACT_IS_NOT_EXIST:联系人不存在";
    public static final String CONTACT_DATA_IS_NULL = "CONTACT_DATA_IS_NULL：联系人数据不完整";
    public static final String COMPANY_HAS_CONTACTS = "公司存在关联的联系人，无法彻底删除";

    // ============================== 销售管理模块错误提示 ==============================
    public static final String OPPORTUNITY_IS_NOT_EXIST = "OPPORTUNITY_IS_NOT_EXIST:商机不存在";
    public static final String OPPORTUNITY_DATA_IS_NULL = "OPPORTUNITY_DATA_IS_NULL:商机数据不能为空";
    public static final String OPPORTUNITY_IS_EXIST = "OPPORTUNITY_IS_EXIST:商机已存在";
    public static final String FILE_DELETE_ERROR = "FILE_DELETE_ERROR：文件删除失败";
    public static final String CHUNK_DIR_DELETE_ERROR = "CHUNK_DIR_DELETE_ERROR：无法删除分片目录";
    public static final String FILE_READ_ERROR = "FILE_READ_ERROR：文件读取失败";
    public static final String CHUNK_FILE_DELETE_ERROR = "CHUNK_FILE_DELETE_ERROR：分片文件删除失败 :";
    public static final String CHUNK_DIR_NOT_EXIST = "CHUNK_DIR_NOT_EXIST：分片目录不存在";
    public static final String CHUNK_FILE_NOT_EXIST = "CHUNK_FILE_NOT_EXIST：第【%s】分片文件不存在";
    public static final String COMPANY_OR_CONTACT_IS_NULL = "COMPANY_OR_CONTACT_IS_NULL:客户和联系人不能为空";
    public static final String COMPANY_OR_CONTACT_IS_NOT_MATCH = "COMPANY_OR_CONTACT_IS_NOT_MATCH:客户和联系人不匹配";
    public static final String SALES_OPPORTUNITY_ALREADY_NULL = "SALES_OPPORTUNITY_ALREADY_NULL:销售机会不存在";
    public static final String SALES_OPPORTUNITY_ALREADY_EXIST = "SALES_OPPORTUNITY_ALREADY_EXIST:销售机会已存在";
    public static final String APPROVAL_ATTACHMENT_IS_NULL = "APPROVAL_ATTACHMENT_IS_NULL:审批文件不能为空";
    public static final String SALES_STAGE_APPROVAL_ID_IS_NULL = "SALES_STAGE_APPROVAL_ID_IS_NULL:销售阶段审批ID不存在";
    public static final String BUSINESS_ACTIVITY_USER_DUPLICATE = "BUSINESS_ACTIVITY_USER_DUPLICATE:商业活动用户关联已存在";
    public static final String BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST = "BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST:商业活动联系人已存在";
    public static final String BUSINESS_ACTIVITY_USER_NOT_EXIST = "BUSINESS_ACTIVITY_USER_NOT_EXIST:商业活动用户不存在,删除失败";
    public static final String BUSINESS_ACTIVITY_CONTACT_NOT_EXIST = "BUSINESS_ACTIVITY_CONTACT_NOT_EXIST:商业活动联系人不存在,删除失败";

    // ============================== 财务管理模块错误提示 ==============================
    public static final String CONTRACT_IS_NOT_EXIST = "CONTRACT_IS_NOT_EXIST:合同不存在";
    public static final String CONTRACT_DATA_IS_NULL = "CONTRACT_DATA_IS_NULL:合同数据不能为空";
    public static final String CONTRACT_STATUS_ERROR = "CONTRACT_STATUS_ERROR:合同状态错误";
    public static final String CONTRACT_ALREADY_DELETED = "CONTRACT_ALREADY_DELETED:合同已删除";
    public static final String ORDER_IS_NOT_EXIST = "ORDER_IS_NOT_EXIST:订单不存在";
    public static final String ORDER_LIST_IS_NULL = "ORDER_LIST_IS_NULL:订单列表不能为空";
    public static final String ORDER_DATA_IS_NULL = "ORDER_DATA_IS_NULL:订单数据不能为空";
    public static final String CONTRACT_MUST_BE_ABANDONED = "CONTRACT_MUST_BE_ABANDONED:合同必须处于废弃状态才能删除";
    public static final String AMOUNT_FORMAT_ERROR = "AMOUNT_FORMAT_ERROR:金额格式错误";
    public static final String PAYMENT_IS_NOT_EXIST = "PAYMENT_IS_NOT_EXIST:付款记录不存在";
    public static final String PAYMENT_DATA_IS_NULL = "PAYMENT_DATA_IS_NULL:付款数据不能为空";
    public static final String CONTRACT_NOT_IN_DISCARD_STATUS = "CONTRACT_NOT_IN_DISCARD_STATUS:合同必须处于废弃状态才能删除";
    public static final String OPPORTUNITY_MUST_BE_CLOSED = "OPPORTUNITY_MUST_BE_CLOSED:销售机会必须处于关闭状态才能删除";
    public static final String BUSINESS_ACTIVITY_IS_NOT_EXIST = "BUSINESS_ACTIVITY_IS_NOT_EXIST:商业活动不存在";
    public static final String BUSINESS_ACTIVITY_CONTACT_IS_NOT_EXIST = "BUSINESS_ACTIVITY_CONTACT_IS_NOT_EXIST:商业活动联系人不存在";
    public static final String BUSINESS_ACTIVITY_USER_IS_NOT_EXIST = "BUSINESS_ACTIVITY_USER_IS_NOT_EXIST:商业活动用户不存在";


    // ============================== 任务管理模块错误提示 ==============================
    public static final String TASK_IS_NOT_EXIST = "TASK_IS_NOT_EXIST:任务不存在";
    public static final String TASK_DATA_IS_NULL = "TASK_DATA_IS_NULL:任务数据不能为空";
    public static final String TASK_ASSIGNEE_IS_NOT_EXIST = "TASK_ASSIGNEE_IS_NOT_EXIST:任务负责人不存在";
    public static final String TASK_DEADLINE_ILLEGAL = "TASK_DEADLINE_ILLEGAL:任务截止日期不合法";
    public static final String TASK_STATUS_ERROR = "TASK_STATUS_ERROR:任务状态错误";
    public static final String TASK_PRIORITY_ERROR = "TASK_PRIORITY_ERROR:任务优先级错误";

    // ============================== 权限管理模块错误提示 ==============================
    public static final String PRIVACY_ERR = "PRIVACY_ERR:隐私数据错误";
    public static final String PASS_OR_EMAIL_ERR = "PASS_OR_EMAIL_ERR:密码或邮箱错误";
    public static final String MASTER_IS_ONE = "MASTER_IS_ONE:终极管理员只能存在一位";
    public static final String ROLE_IS_NOT_EXIST = "ROLE_IS_NOT_EXIST:角色不存在";
    public static final String TOKEN_IS_ERROR = "TOKEN_IS_ERROR:token错误";
    public static final String TOKEN_IS_EXPIRE = "TOKEN_IS_EXPIRE:登录状态过期";
    public static final String TOKEN_IS_NULL = "TOKEN_IS_NULL:token不能为空";
    public static final String USER_IS_NOT_EXIST = "USER_IS_NOT_EXIST:用户不存在";
    public static final String USER_IS_LOCK = "USER_IS_LOCK:用户已被锁定";
    public static final String USER_IS_DISABLE = "USER_IS_DISABLE:用户已被禁用";
    public static final String USER_IS_FROZEN = "USER_IS_FREEZE:用户已被冻结";
    public static final String USER_IS_QUIT = "USER_IS_QUIT:用户已离职";
    public static final String USER_STATUS_ABNORMAL = "USER_STATUS_ABNORMAL:用户状态异常";
    public static final String PERM_CONFIG_ERROR = "PERM_CONFIG_ERROR:权限配置错误";
    public static final String USER_IS_NOT_EXIST_PREM = "USER_IS_NOT_EXIST_PREM:用户不存在权限";
    public static final String PERMISSION_IS_NOT_EXIST = "PERMISSION_IS_NOT_EXIST:权限不存在";
    public static final String ROLE_PERMISSION_IS_NULL = "ROLE_PERMISSION_IS_NULL:角色ID、权限ID列表、操作类型不能为空";


    // ============================== 系统管理模块错误提示 ==============================
    public static final String SYSTEM_BUSY = "SYSTEM_BUSY:系统繁忙，请稍后再试";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED:权限不足";
    public static final String PARAMETER_ERROR = "PARAMETER_ERROR:参数错误";
    public static final String DB_ERROR = "DB_ERROR:数据库错误";
    public static final String NETWORK_ERROR = "NETWORK_ERROR:网络错误";
    public static final String DATA_DELETE_FAILED = "DATA_DELETE_FAILED:数据删除失败";

}
