package com.slz.crm.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统错误码枚举
 * <p>
 * 错误码格式：模块码(2位) + 具体错误码(3位)
 * 第一位模块码：1-认证授权, 2-客户管理, 3-销售管理, 4-任务管理, 5-参数验证, 9-系统/业务
 * 第二位子模块码：0-通用, 1-特定业务
 * 后三位具体错误码：000-999 (000表示该模块的通用错误)
 * <p>
 * 模块分配：
 * 10xxx - 认证授权模块 (10000为该模块通用错误)
 * 12xxx - 权限管理模块 (12000为该模块通用错误)
 * 13xxx - 用户管理模块
 * 20xxx - 客户公司模块 (20000为该模块通用错误)
 * 21xxx - 联系人模块 (21000为该模块通用错误)
 * 30xxx - 销售机会模块
 * 31xxx - 合同订单模块 (31000为该模块通用错误)
 * 40xxx - 任务管理模块 (40000为该模块通用错误)
 * 50xxx - 参数验证模块
 * 90xxx - 系统级错误 (90000为该模块通用错误)
 * 91xxx - 数据处理错误
 * 92xxx - 业务逻辑错误 (92000为该模块通用错误)
 * 99xxx - 未知错误
 *
 * @author CRM Team
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ========== 认证授权错误大类 ==========
    /**
     * 认证授权错误
     */
    AUTH_ERROR(10000, "认证授权错误", "error.auth.category"),

    // ========== 认证授权错误 (10001-10999) ==========
    /**
     * Token为空或格式错误
     */
    TOKEN_ERROR(10001, "Token不能为空或格式错误", "error.token.empty"),

    /**
     * Token已过期
     */
    TOKEN_EXPIRED(10002, "Token已过期", "error.token.expired"),

    /**
     * Token无效
     */
    TOKEN_INVALID(10003, "Token无效", "error.token.invalid"),

    /**
     * JWT解析失败
     */
    TOKEN_PARSE_FAILED(10004, "JWT解析失败", "error.token.parse.failed"),

    // ========== 权限管理错误大类 ==========
    /**
     * 权限管理错误
     */
    PERMISSION_ERROR(12000, "权限管理错误", "error.permission.category"),

    // ========== 权限管理错误 (12001-12999) ==========
    /**
     * 用户未登录
     */
    USER_NOT_LOGIN(12001, "用户未登录", "error.user.not.login"),

    /**
     * 权限不足
     */
    PERMISSION_DENIED(12002, "权限不足", "error.permission.denied"),

    /**
     * 用户状态异常(锁定/禁用/冻结/离职)
     */
    USER_STATUS_EXCEPTION(12003, "用户状态异常", "error.user.status.exception"),

    /**
     * 用户已被锁定
     */
    USER_IS_LOCK(12004, "用户已被锁定", "error.user.is.lock"),

    /**
     * 用户已被禁用
     */
    USER_IS_DISABLE(12005, "用户已被禁用", "error.user.is.disable"),

    /**
     * 用户已被冻结
     */
    USER_IS_FROZEN(12006, "用户已被冻结", "error.user.is.frozen"),

    /**
     * 用户已离职
     */
    USER_IS_QUIT(12007, "用户已离职", "error.user.is.quit"),

    // ========== 用户管理错误 (13001-13999) ==========
    /**
     * 角色不存在
     */
    ROLE_NOT_EXISTS(13001, "角色不存在", "error.role.not.exists"),

    /**
     * 终极管理员只能存在一位
     */
    ADMIN_EXISTS_ONLY_ONE(13002, "终极管理员只能存在一位", "error.admin.exists.only.one"),

    // ========== 客户公司管理错误大类 ==========
    /**
     * 客户公司管理错误
     */
    COMPANY_ERROR(20000, "客户公司管理错误", "error.company.category"),

    // ========== 客户公司管理错误 (20001-20999) ==========
    /**
     * 公司不存在
     */
    COMPANY_NOT_EXISTS(20001, "公司不存在", "error.company.not.exists"),

    /**
     * 公司数据为空
     */
    COMPANY_DATA_EMPTY(20002, "公司数据为空", "error.company.data.empty"),

    // ========== 联系人管理错误大类 ==========
    /**
     * 联系人管理错误
     */
    CONTACT_ERROR(21000, "联系人管理错误", "error.contact.category"),

    // ========== 联系人管理错误 (21001-21999) ==========
    /**
     * 联系人不存在
     */
    CONTACT_NOT_EXISTS(21001, "联系人不存在", "error.contact.not.exists"),

    /**
     * 联系人已存在
     */
    CONTACT_ALREADY_EXISTS(21002, "联系人已存在", "error.contact.already.exists"),

    // ========== 销售机会错误 (30001-30999) ==========
    /**
     * 商机不存在
     */
    OPPORTUNITY_NOT_EXISTS(30001, "商机不存在", "error.opportunity.not.exists"),

    /**
     * 商机已存在
     */
    OPPORTUNITY_ALREADY_EXISTS(30002, "商机已存在", "error.opportunity.already.exists"),

    /**
     * 商机必须已关闭才能删除
     */
    OPPORTUNITY_MUST_BE_CLOSED(30003, "商机必须已关闭才能删除", "error.opportunity.must.be.closed"),

    /**
     * 销售机会已经空
     */
    SALES_OPPORTUNITY_ALREADY_NULL(30004, "销售机会已经空", "error.sales.opportunity.already.null"),

    /**
     * 销售阶段审批ID不存在
     */
    SALES_STAGE_APPROVAL_NOT_EXISTS(30005, "销售阶段审批ID不存在", "error.sales.stage.approval.not.exists"),

    /**
     * 审批文件不能为空
     */
    APPROVAL_FILE_EMPTY(30006, "审批文件不能为空", "error.approval.file.empty"),

    // ========== 合同订单错误大类 ==========
    /**
     * 合同订单错误
     */
    CONTRACT_ERROR(31000, "合同订单错误", "error.contract.category"),

    // ========== 合同订单错误 (31001-31999) ==========
    /**
     * 合同不存在
     */
    CONTRACT_NOT_EXISTS(31001, "合同不存在", "error.contract.not.exists"),

    /**
     * 订单不存在
     */
    ORDER_NOT_EXISTS(31002, "订单不存在", "error.order.not.exists"),

    /**
     * 合同状态错误
     */
    CONTRACT_STATUS_ERROR(31003, "合同状态错误", "error.contract.status.error"),

    /**
     * 金额格式错误
     */
    AMOUNT_FORMAT_ERROR(31004, "金额格式错误", "error.amount.format.error"),

    // ========== 任务管理错误大类 ==========
    /**
     * 任务管理错误
     */
    TASK_ERROR(40000, "任务管理错误", "error.task.category"),

    // ========== 任务管理错误 (40001-40999) ==========
    /**
     * 任务不存在
     */
    TASK_NOT_EXISTS(40001, "任务不存在", "error.task.not.exists"),

    /**
     * 任务负责人不存在
     */
    TASK_OWNER_NOT_EXISTS(40002, "任务负责人不存在", "error.task.owner.not.exists"),

    /**
     * 任务截止日期不合法
     */
    TASK_DEADLINE_INVALID(40003, "任务截止日期不合法", "error.task.deadline.invalid"),

    // ========== 参数验证错误 (50001-50999) ==========
    /**
     * 参数为空
     */
    PARAM_EMPTY(50001, "参数为空", "error.param.empty"),

    /**
     * 参数格式错误
     */
    PARAM_FORMAT_ERROR(50002, "参数格式错误", "error.param.format.error"),

    /**
     * 文件格式错误
     */
    FILE_FORMAT_ERROR(50003, "文件格式错误", "error.file.format.error"),

    /**
     * 邮箱格式错误
     */
    EMAIL_FORMAT_ERROR(50004, "邮箱格式错误", "error.email.format.error"),

    /**
     * 手机号格式错误
     */
    PHONE_FORMAT_ERROR(50005, "手机号格式错误", "error.phone.format.error"),

    /**
     * 参数长度超限
     */
    PARAM_LENGTH_EXCEEDED(50006, "参数长度超限", "error.param.length.exceeded"),

    /**
     * 参数超出范围
     */
    PARAM_OUT_OF_RANGE(50007, "参数超出范围", "error.param.out.of.range"),

    /**
     * 必填参数缺失
     */
    PARAM_REQUIRED(50008, "必填参数缺失", "error.param.required"),

    // ========== 系统级错误大类 ==========
    /**
     * 系统级错误
     */
    SYSTEM_ERROR(90000, "系统级错误", "error.system.category"),

    // ========== 系统级错误 (90001-90999) ==========
    /**
     * 系统繁忙
     */
    SYSTEM_BUSY(90001, "系统繁忙，请稍后再试", "error.system.busy"),

    /**
     * 数据库错误
     */
    DATABASE_ERROR(90002, "数据库错误", "error.database.error"),

    /**
     * 网络错误
     */
    NETWORK_ERROR(90003, "网络错误", "error.network.error"),

    /**
     * 服务器内部错误
     */
    INTERNAL_SERVER_ERROR(90004, "服务器内部错误", "error.internal.server.error"),

    /**
     * 服务不可用
     */
    SERVICE_UNAVAILABLE(90005, "服务不可用", "error.service.unavailable"),

    /**
     * 请求频率超限
     */
    RATE_LIMIT_EXCEEDED(90006, "请求频率超限", "error.rate.limit.exceeded"),

    /**
     * 第三方服务错误
     */
    THIRD_PARTY_SERVICE_ERROR(90007, "第三方服务错误", "error.third.party.service.error"),

    // ========== 数据处理错误 (91001-91999) ==========
    /**
     * 数据为空
     */
    DATA_NULL(91001, "数据为空", "error.data.null"),

    /**
     * 分页数据为空
     */
    PAGE_DATA_NULL(91002, "分页数据为空", "error.page.data.null"),

    /**
     * 数据删除失败
     */
    DATA_DELETE_FAILED(91003, "数据删除失败", "error.data.delete.failed"),

    // ========== 业务逻辑错误大类 ==========
    /**
     * 业务逻辑错误
     */
    BUSINESS_ERROR(92000, "业务逻辑错误", "error.business.category"),

    // ========== 业务逻辑错误 (92001-92999) ==========
    /**
     * 更新失败
     */
    UPDATE_FAILED(92001, "更新失败", "error.update.failed"),

    /**
     * 文件创建失败
     */
    FILE_CREATE_FAILED(92002, "文件创建失败", "error.file.create.failed"),

    /**
     * 文件删除失败
     */
    FILE_DELETE_FAILED(92003, "文件删除失败", "error.file.delete.failed"),

    /**
     * 文件读取失败
     */
    FILE_READ_FAILED(92004, "文件读取失败", "error.file.read.failed"),

    /**
     * Excel格式错误
     */
    EXCEL_FORMAT_ERROR(92005, "第【%s】行格式错误或必要信息缺失", "error.excel.format.error"),

    /**
     * Excel公司不存在
     */
    EXCEL_COMPANY_NOT_EXISTS(92006, "第【%s】行公司不存在", "error.excel.company.not.exists"),

    /**
     * Excel联系人不存在
     */
    EXCEL_CONTACT_NOT_EXISTS(92007, "第【%s】行联系人不存在", "error.excel.contact.not.exists"),

    /**
     * ID不存在
     */
    ID_NOT_EXISTS(92008, "【%s】不存在", "error.id.not.exists"),

    /**
     * 邮箱已存在
     */
    EMAIL_EXISTS(92009, "邮箱已存在", "error.email.exists"),

    /**
     * 客户和联系人不能为空
     */
    COMPANY_OR_CONTACT_NULL(92010, "客户和联系人不能为空", "error.company.or.contact.null"),

    /**
     * 客户和联系人不匹配
     */
    COMPANY_OR_CONTACT_NOT_MATCH(92011, "客户和联系人不匹配", "error.company.or.contact.not.match"),

    /**
     * 审批文件为空
     */
    APPROVAL_ATTACHMENT_NULL(92012, "审批文件不能为空", "error.approval.attachment.null"),

    /**
     * 商业活动不存在
     */
    BUSINESS_ACTIVITY_NOT_EXISTS(92013, "商业活动不存在", "error.business.activity.not.exists"),

    /**
     * 商业活动联系人不存在
     */
    BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS(92014, "商业活动联系人不存在", "error.business.activity.contact.not.exists"),

    /**
     * 商业活动用户不存在
     */
    BUSINESS_ACTIVITY_USER_NOT_EXISTS(92015, "商业活动用户不存在", "error.business.activity.user.not.exists"),

    /**
     * 公司存在关联的联系人，无法彻底删除
     */
    COMPANY_HAS_CONTACTS(92016, "公司存在关联的联系人，无法彻底删除", "error.company.has.contacts"),

    /**
     * 合同必须处于废弃状态才能删除
     */
    CONTRACT_MUST_BE_ABANDONED(92017, "合同必须处于废弃状态才能删除", "error.contract.must.be.abandoned"),

    /**
     * 付款记录不存在
     */
    PAYMENT_NOT_EXISTS(92018, "付款记录不存在", "error.payment.not.exists"),

    /**
     * 付款数据不能为空
     */
    PAYMENT_DATA_NULL(92019, "付款数据不能为空", "error.payment.data.null"),

    /**
     * 任务负责人不存在
     */
    TASK_ASSIGNEE_NOT_EXISTS(92020, "任务负责人不存在", "error.task.assignee.not.exists"),

    /**
     * 任务截止日期不合法
     */
    TASK_DEADLINE_ILLEGAL(92021, "任务截止日期不合法", "error.task.deadline.illegal"),

    /**
     * 任务状态错误
     */
    TASK_STATUS_ERROR(92022, "任务状态错误", "error.task.status.error"),

    /**
     * 任务优先级错误
     */
    TASK_PRIORITY_ERROR(92023, "任务优先级错误", "error.task.priority.error"),

    /**
     * 隐私数据错误
     */
    PRIVACY_ERROR(92024, "隐私数据错误", "error.privacy.error"),

    /**
     * 密码或邮箱错误
     */
    PASSWORD_OR_EMAIL_ERROR(92025, "密码或邮箱错误", "error.password.or.email.error"),

    /**
     * 用户不存在权限
     */
    USER_NOT_EXIST_PERMISSION(92026, "用户不存在权限", "error.user.not.exist.permission"),

    /**
     * 功能未启用
     */
    NOT_ENABLED(92027, "功能未启用", "error.not.enabled"),

    // ========== AI 助手错误 (93001-93999) ==========
    /**
     * 确认已超时
     */
    AI_ACTION_TIMEOUT(93001, "确认已超时，请重新发起", "error.ai.action.timeout"),

    /**
     * 操作已被处理
     */
    AI_ACTION_ALREADY_HANDLED(93002, "操作已被处理", "error.ai.action.already.handled"),

    /**
     * AI 操作执行失败
     */
    AI_ACTION_EXECUTE_FAILED(93003, "执行失败", "error.ai.action.execute.failed"),

    /**
     * AI 操作参数不完整
     */
    AI_ACTION_PARAM_MISSING(93004, "参数不完整，请先补齐参数", "error.ai.action.param.missing");

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误信息
     */
    private final String message;

    /**
     * 国际化消息键
     */
    private final String messageKey;

    /**
     * 根据错误码查找枚举
     *
     * @param code 错误码
     * @return ErrorCode枚举，未找到返回null
     */
    public static ErrorCode getByCode(Integer code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.getCode().equals(code)) {
                return errorCode;
            }
        }
        return null;
    }

    /**
     * 根据消息键查找枚举
     *
     * @param messageKey 消息键
     * @return ErrorCode枚举，未找到返回null
     */
    public static ErrorCode getByMessageKey(String messageKey) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.getMessageKey().equals(messageKey)) {
                return errorCode;
            }
        }
        return null;
    }
}
