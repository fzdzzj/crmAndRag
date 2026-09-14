package com.slz.crm.common.enumeration;

import lombok.Getter;

/**
 * CRM系统权限操作枚举类
 * 统一管理所有模块的核心操作权限
 *
 * 权限设计原则：
 * 1. 批量操作和单个操作使用同一权限
 * 2. 多种查询方式合并为统一的查询权限
 * 3. 查看操作合并为统一的查看权限
 */
@Getter
public enum PermissionOperates {

    // ==================== 客户管理模块 (100-199) ====================

    /** 联系人操作 - 通过EXCEL添加数据 */
    CUSTOMER_EXCEL_ADD(101L, "通过EXCEL添加数据"),
    /** 联系人操作 - 更新联系人（包含单个和批量） */
    CUSTOMER_UPDATE_CONTACT(102L, "更新联系人"),
    /** 联系人操作 - 新增联系人 */
    CUSTOMER_ADD_CONTACT(103L, "新增联系人"),
    /** 联系人操作 - 删除联系人（包含单个和批量） */
    CUSTOMER_DELETE_CONTACT(104L, "删除联系人"),
    /** 联系人操作 - 恢复联系人 */
    CUSTOMER_RECOVER_CONTACT(105L, "恢复联系人"),
    /** 联系人操作 - 查询联系人（包含分页、分组、自定义查询） */
    CUSTOMER_QUERY_CONTACT(106L, "查询联系人"),
    /** 联系人操作 - 导出联系人 */
    CUSTOMER_EXPORT_CONTACT(107L, "导出联系人"),

    /** 客户公司操作 - 新增客户公司（单个和批量） */
    CUSTOMER_ADD_COMPANY(111L, "新增客户公司"),
    /** 客户公司操作 - 更新客户公司 */
    CUSTOMER_UPDATE_CUSTOMER_COMPANY(113L, "更新客户公司"),
    /** 客户公司操作 - 删除客户公司（包含单个、批量、逻辑删除） */
    CUSTOMER_DELETE_CUSTOMER_COMPANY(114L, "删除客户公司"),
    /** 客户公司操作 - 恢复客户公司 */
    CUSTOMER_RECOVER_CUSTOMER_COMPANY(115L, "恢复客户公司"),
    /** 客户公司操作 - 查询客户公司（包含模糊、自定义、分组查询） */
    CUSTOMER_QUERY_COMPANY(116L, "查询客户公司"),
    /** 客户公司操作 - 查看客户公司详情 */
    CUSTOMER_VIEW_COMPANY(117L, "查看客户公司"),
    /** 客户公司操作 - 查看客户公司 - 仅查看自己的 */
    CUSTOMER_VIEW_COMPANY_ONLY_MY(1171L, "查看客户公司 - 仅查看自己的", DataScopeLevel.SELF),
    /** 客户公司操作 - 查看客户公司 - 查看标签的 */
    CUSTOMER_VIEW_COMPANY_TAGE(1172L, "查看客户公司 - 查看标签的", DataScopeLevel.TAGE),
    /** 客户公司操作 - 查看客户公司 - 查看全部的 */
    CUSTOMER_VIEW_COMPANY_ALL(1173L, "查看客户公司 - 查看全部的", DataScopeLevel.ALL),
    /** 客户公司操作 - 查看客户公司 - 本部门 */
    CUSTOMER_VIEW_COMPANY_DEPT(1174L, "查看客户公司 - 本部门", DataScopeLevel.DEPT),
    /** 客户公司操作 - 查看客户公司 - 本部门及以下 */
    CUSTOMER_VIEW_COMPANY_DEPT_AND_SUB(1175L, "查看客户公司 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),
    /** 客户公司操作 - 导出客户公司 */
    CUSTOMER_EXPORT_CUSTOMER_COMPANY(118L, "导出客户公司"),
    /** 客户公司操作 - 合并客户公司 */
    CUSTOMER_MERGE_COMPANY(119L, "合并客户公司"),

    // ==================== 销售管理模块 (200-299) ====================

    /** 销售机会管理 - 创建销售机会 */
    SALES_CREATE_SALE_OPPORTUNITY(201L, "创建销售机会"),
    /** 销售机会管理 - 修改销售机会 */
    SALES_UPDATE_SALE_OPPORTUNITY(202L, "修改销售机会"),
    /** 销售机会管理 - 删除销售机会 */
    SALES_DELETE_SALE_OPPORTUNITY(203L, "删除销售机会"),
    /** 销售机会管理 - 查看销售机会 */
    SALES_VIEW_SALE_OPPORTUNITY(204L, "查看销售机会"),
    /** 销售机会管理 - 查看销售机会 - 仅查看自己的 */
    SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY(2041L, "查看销售机会 - 仅查看自己的", DataScopeLevel.SELF),
    /** 销售机会管理 - 查看销售机会 - 查看标签的 */
    SALES_VIEW_SALE_OPPORTUNITY_TAGE(2042L, "查看销售机会 - 查看标签的", DataScopeLevel.TAGE),
    /** 销售机会管理 - 查看销售机会 - 查看全部的 */
    SALES_VIEW_SALE_OPPORTUNITY_ALL(2043L, "查看销售机会 - 查看全部的", DataScopeLevel.ALL),
    /** 销售机会管理 - 查看销售机会 - 本部门 */
    SALES_VIEW_SALE_OPPORTUNITY_DEPT(2044L, "查看销售机会 - 本部门", DataScopeLevel.DEPT),
    /** 销售机会管理 - 查看销售机会 - 本部门及以下 */
    SALES_VIEW_SALE_OPPORTUNITY_DEPT_AND_SUB(2045L, "查看销售机会 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    /** 合同与订单管理 - 追加订单 */
    SALES_APPEND_ORDER(206L, "追加订单"),
    /** 合同与订单管理 - 更新订单（包含删除） */
    SALES_UPDATE_ORDER(210L, "更新订单"),
    /** 合同与订单管理 - 查看订单 */
    SALES_VIEW_ORDER(211L, "查看订单"),
    /** 合同与订单管理 - 查看订单 - 仅查看自己的 */
    SALES_VIEW_ORDER_ONLY_MY(2111L, "查看订单 - 仅查看自己的", DataScopeLevel.SELF),
    /** 合同与订单管理 - 查看订单 - 查看标签的 */
    SALES_VIEW_ORDER_TAGE(2112L, "查看订单 - 查看标签的", DataScopeLevel.TAGE),
    /** 合同与订单管理 - 查看订单 - 查看全部的 */
    SALES_VIEW_ORDER_ALL(2113L, "查看订单 - 查看全部的", DataScopeLevel.ALL),
    /** 合同与订单管理 - 查看订单 - 本部门 */
    SALES_VIEW_ORDER_DEPT(2114L, "查看订单 - 本部门", DataScopeLevel.DEPT),
    /** 合同与订单管理 - 查看订单 - 本部门及以下 */
    SALES_VIEW_ORDER_DEPT_AND_SUB(2115L, "查看订单 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    /** 合同管理 - 创建合同 */
    SALES_CREATE_CONTRACT(212L, "创建合同"),
    /** 合同管理 - 更新合同（包含删除） */
    SALES_UPDATE_CONTRACT(214L, "更新合同"),
    /** 合同管理 - 查看合同 */
    SALES_VIEW_CONTRACT(215L, "查看合同"),
    /** 合同管理 - 查看合同 - 仅查看自己的 */
    SALES_VIEW_CONTRACT_ONLY_MY(2151L, "查看合同 - 仅查看自己的", DataScopeLevel.SELF),
    /** 合同管理 - 查看合同 - 查看标签的 */
    SALES_VIEW_CONTRACT_TAGE(2152L, "查看合同 - 查看标签的", DataScopeLevel.TAGE),
    /** 合同管理 - 查看合同 - 查看全部的 */
    SALES_VIEW_CONTRACT_ALL(2153L, "查看合同 - 查看全部的", DataScopeLevel.ALL),
    /** 合同管理 - 查看合同 - 本部门 */
    SALES_VIEW_CONTRACT_DEPT(2154L, "查看合同 - 本部门", DataScopeLevel.DEPT),
    /** 合同管理 - 查看合同 - 本部门及以下 */
    SALES_VIEW_CONTRACT_DEPT_AND_SUB(2155L, "查看合同 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    /** 项目阶段管理 - 推进销售机会阶段 */
    SALES_PROGRESS_SALE_OPPORTUNITY_STAGE(217L, "推进销售机会阶段"),
    /** 项目阶段管理 - 审批推进阶段 */
    SALES_APPROVE_STAGE_ADVANCE(218L, "审批推进阶段"),
    /** 项目阶段管理 - 删除推进请求 */
    SALES_DELETE_STAGE_ADVANCE(219L, "删除推进请求"),
    /** 项目阶段管理 - 查看销售机会阶段 */
    SALES_VIEW_SALE_OPPORTUNITY_STAGE(220L, "查看销售机会阶段"),
    /** 项目阶段管理 - 查看销售机会阶段 - 仅查看自己的 */
    SALES_VIEW_SALE_OPPORTUNITY_STAGE_ONLY_MY(2201L, "查看销售机会阶段 - 仅查看自己的", DataScopeLevel.SELF),
    /** 项目阶段管理 - 查看销售机会阶段 - 查看标签的 */
    SALES_VIEW_SALE_OPPORTUNITY_STAGE_TAGE(2202L, "查看销售机会阶段 - 查看标签的", DataScopeLevel.TAGE),
    /** 项目阶段管理 - 查看销售机会阶段 - 查看全部的 */
    SALES_VIEW_SALE_OPPORTUNITY_STAGE_ALL(2203L, "查看销售机会阶段 - 查看全部的", DataScopeLevel.ALL),
    /** 项目阶段管理 - 查看销售机会阶段 - 本部门 */
    SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT(2204L, "查看销售机会阶段 - 本部门", DataScopeLevel.DEPT),
    /** 项目阶段管理 - 查看销售机会阶段 - 本部门及以下 */
    SALES_VIEW_SALE_OPPORTUNITY_STAGE_DEPT_AND_SUB(2205L, "查看销售机会阶段 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    /** 商业活动管理 - 创建商业活动 */
    SALES_CREATE_BUSINESS_ACTIVITY(222L, "创建商业活动"),
    /** 商业活动管理 - 更新商业活动 */
    SALES_UPDATE_BUSINESS_ACTIVITY(223L, "更新商业活动"),
    /** 商业活动管理 - 删除商业活动 */
    SALES_DELETE_BUSINESS_ACTIVITY(224L, "删除商业活动"),
    /** 商业活动管理 - 查看商业活动 */
    SALES_VIEW_BUSINESS_ACTIVITY(225L, "查看商业活动"),
    /** 商业活动管理 - 查看商业活动 - 仅查看自己的 */
    SALES_VIEW_BUSINESS_ACTIVITY_ONLY_MY(2251L, "查看商业活动 - 仅查看自己的", DataScopeLevel.SELF),
    /** 商业活动管理 - 查看商业活动 - 查看标签的 */
    SALES_VIEW_BUSINESS_ACTIVITY_TAGE(2252L, "查看商业活动 - 查看标签的", DataScopeLevel.TAGE),
    /** 商业活动管理 - 查看商业活动 - 查看全部的 */
    SALES_VIEW_BUSINESS_ACTIVITY_ALL(2253L, "查看商业活动 - 查看全部的", DataScopeLevel.ALL),
    /** 商业活动管理 - 查看商业活动 - 本部门 */
    SALES_VIEW_BUSINESS_ACTIVITY_DEPT(2254L, "查看商业活动 - 本部门", DataScopeLevel.DEPT),
    /** 商业活动管理 - 查看商业活动 - 本部门及以下 */
    SALES_VIEW_BUSINESS_ACTIVITY_DEPT_AND_SUB(2255L, "查看商业活动 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    /** 项目文件管理 - 上传项目文件 */
    SALES_UPLOAD_PROJECT_FILE(226L, "上传项目文件"),
    /** 项目文件管理 - 删除项目文件 */
    SALES_DELETE_PROJECT_FILE(228L, "删除项目文件"),
    /** 项目文件管理 - 查看项目文件 */
    SALES_VIEW_PROJECT_FILE(229L, "查看项目文件"),
    /** 项目文件管理 - 查看项目文件 - 仅查看自己的 */
    SALES_VIEW_PROJECT_FILE_ONLY_MY(2291L, "查看项目文件 - 仅查看自己的"),
    /** 项目文件管理 - 查看项目文件 - 查看标签的 */
    SALES_VIEW_PROJECT_FILE_TAGE(2292L, "查看项目文件 - 查看标签的"),
    /** 项目文件管理 - 查看项目文件 - 查看全部的 */
    SALES_VIEW_PROJECT_FILE_ALL(2293L, "查看项目文件 - 查看全部的"),
    /** 项目文件管理 - 查看项目文件 - 本部门 */
    SALES_VIEW_PROJECT_FILE_DEPT(2294L, "查看项目文件 - 本部门", DataScopeLevel.DEPT),
    /** 项目文件管理 - 查看项目文件 - 本部门及以下 */
    SALES_VIEW_PROJECT_FILE_DEPT_AND_SUB(2295L, "查看项目文件 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    // ==================== 财务管理模块 (300-399) ====================

    /** 回款管理 - 录入回款 */
    FINANCE_RECORD_PAYMENT(301L, "录入回款"),
    /** 回款管理 - 编辑回款 */
    FINANCE_EDIT_PAYMENT(302L, "编辑回款"),
    /** 回款管理 - 删除回款 */
    FINANCE_DELETE_PAYMENT(303L, "删除回款"),
    /** 回款管理 - 查看回款 */
    FINANCE_VIEW_PAYMENT(304L, "查看回款"),
    /** 回款管理 - 查看回款 - 仅查看自己的 */
    FINANCE_VIEW_PAYMENT_ONLY_MY(3041L, "查看回款 - 仅查看自己的", DataScopeLevel.SELF),
    /** 回款管理 - 查看回款 - 查看标签的 */
    FINANCE_VIEW_PAYMENT_TAGE(3042L, "查看回款 - 查看标签的", DataScopeLevel.TAGE),
    /** 回款管理 - 查看回款 - 查看全部的 */
    FINANCE_VIEW_PAYMENT_ALL(3043L, "查看回款 - 查看全部的", DataScopeLevel.ALL),
    /** 回款管理 - 查看回款 - 本部门 */
    FINANCE_VIEW_PAYMENT_DEPT(3044L, "查看回款 - 本部门", DataScopeLevel.DEPT),
    /** 回款管理 - 查看回款 - 本部门及以下 */
    FINANCE_VIEW_PAYMENT_DEPT_AND_SUB(3045L, "查看回款 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    /** 开票管理 - 记录开票 */
    FINANCE_RECORD_INVOICE(306L, "记录开票"),
    /** 开票管理 - 编辑开票 */
    FINANCE_EDIT_INVOICE(307L, "编辑开票"),
    /** 开票管理 - 删除开票 */
    FINANCE_DELETE_INVOICE(308L, "删除开票"),
    /** 开票管理 - 查看开票 */
    FINANCE_VIEW_INVOICE(309L, "查看开票"),

    // ==================== 联络任务模块 (400-499) ====================

    /** 任务管理 - 创建任务 */
    TASK_CREATE_TASK(401L, "创建任务"),
    /** 任务管理 - 更新任务（包含单个和批量） */
    TASK_UPDATE_TASK(402L, "更新任务"),
    /** 任务管理 - 删除任务（包含单个和批量） */
    TASK_DELETE_TASK(403L, "删除任务"),
    /** 任务管理 - 查看任务 */
    TASK_VIEW_TASK(404L, "查看任务"),
    /** 任务管理 - 查看任务 - 仅查看自己的 */
    TASK_VIEW_TASK_ONLY_MY(4041L, "查看任务 - 仅查看自己的", DataScopeLevel.SELF),
    /** 任务管理 - 查看任务 - 查看标签的 */
    TASK_VIEW_TASK_TAGE(4042L, "查看任务 - 查看标签的", DataScopeLevel.TAGE),
    /** 任务管理 - 查看任务 - 查看全部的 */
    TASK_VIEW_TASK_ALL(4043L, "查看任务 - 查看全部的", DataScopeLevel.ALL),
    /** 任务管理 - 查看任务 - 本部门 */
    TASK_VIEW_TASK_DEPT(4044L, "查看任务 - 本部门", DataScopeLevel.DEPT),
    /** 任务管理 - 查看任务 - 本部门及以下 */
    TASK_VIEW_TASK_DEPT_AND_SUB(4045L, "查看任务 - 本部门及以下", DataScopeLevel.DEPT_AND_CHILD),

    // ==================== 统计报表模块 (500-599) ====================

    /** 报表管理 - 查看报表 */
    REPORT_VIEW_REPORT(501L, "查看报表"),
    /** 报表管理 - 生成报表 */
    REPORT_GENERATE_REPORT(502L, "生成报表"),
    /** 报表管理 - 导出报表 */
    REPORT_EXPORT_REPORT(503L, "导出报表"),
    /** 报表管理 - 管理报表模板 */
    REPORT_MANAGE_TEMPLATE(504L, "管理报表模板"),

    // ==================== 权限管理模块 (600-699) ====================

    /** 用户管理 - 创建用户 */
    SYSTEM_CREATE_USER(601L, "创建用户"),
    /** 用户管理 - 查看用户 */
    SYSTEM_VIEW_USER(602L, "查看用户"),
    /** 用户管理 - 修改用户 */
    SYSTEM_UPDATE_USER(603L, "修改用户"),

    /** 角色管理 - 查看角色 */
    SYSTEM_VIEW_ROLE(604L, "查看角色"),
    /** 角色管理 - 管理角色 */
    SYSTEM_MANAGE_ROLE(605L, "管理角色"),
    /** 角色管理 - 分配权限 */
    SYSTEM_ASSIGN_PERMISSION(606L, "分配权限"),

    /** 组织架构管理 - 管理集团与部门主数据 */
    SYSTEM_MANAGE_ORG(607L, "组织架构管理"),

    // ==================== 隐私信息查看权限 (700-799) ====================

    /** 隐私信息查看 - 查看电话 */
    PRIVACY_PHONE_VIEW(701L, "查看电话"),
    /** 隐私信息查看 - 查看邮箱 */
    PRIVACY_EMAIL_VIEW(702L, "查看邮箱"),
    /** 隐私信息查看 - 查看金额 */
    PRIVACY_TOTAL_VIEW(703L, "查看金额"),

    // ==================== AI 模块权限 (800-899) ====================

    /** AI 模块 - 查看被指派的协助 / 协助详情与关联业务 */
    AI_ASSIST_VIEW(800L, "查看协助"),
    /** AI 模块 - 发起/追加/重新申请协助，上传来源附件 */
    AI_ASSIST_APPLY(801L, "提交协助申请"),
    /** AI 模块 - 协助人提交处理意见、删除附件 */
    AI_ASSIST_HANDLE(802L, "处理协助"),
    /** AI 模块 - 管理 AI 会话（建会话/会话列表/消息历史/上传图片/归档） */
    AI_CHAT_SESSION(803L, "管理AI会话"),
    /** AI 模块 - SSE 流式对话 */
    AI_CHAT_STREAM(804L, "AI流式对话"),
    /** AI 模块 - 取消生成中的对话 */
    AI_CHAT_CANCEL(805L, "取消AI生成"),
    /** AI 模块 - 查看待确认操作状态 */
    AI_ACTION_VIEW(806L, "查看待确认操作"),
    /** AI 模块 - 确认执行/取消/编辑待确认操作（含创建客户/开票等敏感动作） */
    AI_ACTION_CONFIRM(807L, "确认执行待确认操作");

    private final Long id;
    private final String description;
    private final DataScopeLevel dataScopeLevel;

    PermissionOperates(Long id, String description) {
        this.id = id;
        this.description = description;
        this.dataScopeLevel = DataScopeLevel.NONE;
    }

    PermissionOperates(Long id, String description, DataScopeLevel dataScopeLevel) {
        this.id = id;
        this.description = description;
        this.dataScopeLevel = dataScopeLevel;
    }

    /**
     * 判断当前权限是否为数据范围权限
     *
     * @return 如果是数据范围权限返回 true
     */
    public boolean isDataScopePermission() {
        return this.dataScopeLevel != DataScopeLevel.NONE;
    }

    /**
     * 获取当前子权限对应的主权限ID
     * 仅对数据范围权限（子权限）有效，主权限返回null
     *
     * 子权限命名规则：主权限枚举名 + _ONLY_MY / _TAGE / _ALL / _DEPT / _DEPT_AND_SUB
     * 通过截取后缀找到对应的主权限枚举
     *
     * @return 主权限ID，如果是主权限则返回null
     */
    public Long getParentPermissionId() {
        if (!isDataScopePermission()) {
            return null;
        }

        String name = this.name();
        String baseName;
        if (name.endsWith("_ONLY_MY")) {
            baseName = name.substring(0, name.length() - "_ONLY_MY".length());
        } else if (name.endsWith("_TAGE")) {
            baseName = name.substring(0, name.length() - "_TAGE".length());
        } else if (name.endsWith("_ALL")) {
            baseName = name.substring(0, name.length() - "_ALL".length());
        } else if (name.endsWith("_DEPT_AND_SUB")) {
            baseName = name.substring(0, name.length() - "_DEPT_AND_SUB".length());
        } else if (name.endsWith("_DEPT")) {
            baseName = name.substring(0, name.length() - "_DEPT".length());
        } else {
            return null;
        }

        try {
            PermissionOperates parent = PermissionOperates.valueOf(baseName);
            return parent.getId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 根据ID获取权限枚举
     * @param id 权限ID
     * @return 权限枚举，如果不存在则返回null
     */
    public static PermissionOperates fromId(Long id) {
        if (id == null) {
            return null;
        }
        for (PermissionOperates permission : values()) {
            if (permission.getId().equals(id)) {
                return permission;
            }
        }
        return null;
    }
}
