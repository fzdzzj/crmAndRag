package com.slz.crm.server.ai;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.ao.RoleAO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * AI 工具注册表：只负责工具清单编排；执行细节与审计由 AiQueryToolExecutors / AiDraftToolExecutors / AiToolCallbackFactory
 * 负责。
 */
@Component
@RequiredArgsConstructor
public class AiToolRegistry {

  private static final String AMBIGUITY_GUIDANCE =
      "。查询结果为空时放宽条件重试一次，仍为空则向用户询问更多线索；多条匹配且用户意图不明确时，列出候选项让用户确认，不要自行选择第一条";

  private final AiToolCallbackFactory callbackFactory;
  private final AiQueryToolExecutors queryToolExecutors;
  private final AiDraftToolExecutors draftToolExecutors;

  public List<ToolCallback> getPermittedToolCallbacks(RoleAO user) {
    List<ToolCallback> callbacks = new ArrayList<>();

    add(
        callbacks,
        "getChartData",
        "获取销售统计图表数据。dataType: CONTRACT_NUM=签约合同数, PERFORMANCE=业绩, CUSTOMER_SOURCE=客户来源分布, PAYMENT_AMOUNT=回款金额, PAYMENT_COUNT=回款笔数, PAYMENT_STATUS=回款状态分布。timeRange: THIS_WEEK/THIS_MONTH/THIS_YEAR/CUSTOM_RANGE。自定义范围需传 startTime/endTime（格式 yyyy-MM-dd HH:mm:ss）",
        "{\"type\":\"object\",\"properties\":{\"dataType\":{\"type\":\"string\",\"enum\":[\"CONTRACT_NUM\",\"PERFORMANCE\",\"CUSTOMER_SOURCE\",\"PAYMENT_AMOUNT\",\"PAYMENT_COUNT\",\"PAYMENT_STATUS\"]},\"timeRange\":{\"type\":\"string\",\"enum\":[\"THIS_WEEK\",\"THIS_MONTH\",\"THIS_YEAR\",\"CUSTOM_RANGE\"]},\"startTime\":{\"type\":\"string\"},\"endTime\":{\"type\":\"string\"}},\"required\":[\"dataType\"]}",
        null,
        queryToolExecutors::executeGetChartData,
        user);
    add(
        callbacks,
        "getStatisticsSummary",
        "获取指定时间范围的综合统计数据汇总（合同数、订单数、回款金额等）。startTime/endTime 必填（格式 yyyy-MM-dd HH:mm:ss）",
        "{\"type\":\"object\",\"properties\":{\"startTime\":{\"type\":\"string\"},\"endTime\":{\"type\":\"string\"}},\"required\":[\"startTime\",\"endTime\"]}",
        null,
        queryToolExecutors::executeGetStatisticsSummary,
        user);
    add(
        callbacks,
        "getOpportunityStageDistribution",
        "获取商机阶段分布数据（各阶段的商机数量）",
        "{\"type\":\"object\",\"properties\":{}}",
        PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY,
        queryToolExecutors::executeGetOpportunityStageDistribution,
        user);
    add(
        callbacks,
        "queryOrder",
        "按条件分页查询订单。可选条件：contractId（合同ID）、productName（产品名称）。返回订单列表（含金额、数量、产品名）。若用户仅提供合同名称，先调用 queryContract 按名称查出 contractId 再查询"
            + AMBIGUITY_GUIDANCE,
        "{\"type\":\"object\",\"properties\":{\"contractId\":{\"type\":\"integer\"},\"productName\":{\"type\":\"string\"},\"pageNum\":{\"type\":\"integer\"},\"pageSize\":{\"type\":\"integer\"}}}",
        PermissionOperates.SALES_VIEW_ORDER,
        queryToolExecutors::executeQueryOrder,
        user);
    add(
        callbacks,
        "getOrderDetail",
        "按订单ID查询订单详情（含合同信息）。若用户不记得订单ID，先用 queryOrder 按产品/合同定位订单再取ID查详情",
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"integer\"}},\"required\":[\"orderId\"]}",
        PermissionOperates.SALES_VIEW_ORDER,
        queryToolExecutors::executeGetOrderDetail,
        user);
    add(
        callbacks,
        "queryContract",
        "按条件分页查询合同。可选条件：contractName（合同名称模糊搜索）。返回合同列表（含合同编号、金额、状态）。如果按名称查询没有结果或用户不满意，可以不传 contractName 查询当前用户名下全部合同作为补救"
            + AMBIGUITY_GUIDANCE,
        "{\"type\":\"object\",\"properties\":{\"contractName\":{\"type\":\"string\"},\"pageNum\":{\"type\":\"integer\"},\"pageSize\":{\"type\":\"integer\"}}}",
        PermissionOperates.SALES_VIEW_CONTRACT,
        queryToolExecutors::executeQueryContract,
        user);
    add(
        callbacks,
        "queryCustomerCompany",
        "按名称模糊查询客户公司。返回公司列表（含公司名、行业、联系人）" + AMBIGUITY_GUIDANCE,
        "{\"type\":\"object\",\"properties\":{\"companyName\":{\"type\":\"string\"},\"pageNum\":{\"type\":\"integer\"},\"pageSize\":{\"type\":\"integer\"}},\"required\":[\"companyName\"]}",
        PermissionOperates.CUSTOMER_QUERY_COMPANY,
        queryToolExecutors::executeQueryCustomerCompany,
        user);
    add(
        callbacks,
        "queryOpportunity",
        "按名称模糊查询商机。可选条件：opportunityName（商机名称）、companyName（公司名称）、contactName（联系人）。返回商机ID、名称、公司、阶段、金额。创建合同草稿前必须先调用本工具确认商机并拿到 opportunityId。如果按名称查询没有结果或用户不满意，可以不传名称参数查询当前用户名下全部商机作为补救"
            + AMBIGUITY_GUIDANCE,
        "{\"type\":\"object\",\"properties\":{\"opportunityName\":{\"type\":\"string\"},\"companyName\":{\"type\":\"string\"},\"contactName\":{\"type\":\"string\"},\"pageNum\":{\"type\":\"integer\"},\"pageSize\":{\"type\":\"integer\"}}}",
        PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY,
        queryToolExecutors::executeQueryOpportunity,
        user);
    add(
        callbacks,
        "createOrderDraft",
        "追加订单草稿。为已有合同添加更多订单明细（产品名称、数量、单价、金额）。一次可添加多个订单。合同双通道：已知合同ID传 contractId，只知道名称传 contractName（后端自动解析，多个匹配会追问候选）。若用户仅提供合同名称，也可先调用 queryContract 查出 contractId。草稿需要用户确认后才真实创建。参数不全时会返回缺失字段，请据此向用户追问。追问合并时必传 pendingId",
        "{\"type\":\"object\",\"properties\":{\"pendingId\":{\"type\":\"string\",\"description\":\"追问合并时必传\"},\"orders\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"contractId\":{\"type\":\"integer\",\"description\":\"合同ID，与 contractName 二选一\"},\"contractName\":{\"type\":\"string\",\"description\":\"合同名称，与 contractId 二选一\"},\"productName\":{\"type\":\"string\"},\"quantity\":{\"type\":\"number\"},\"unitPrice\":{\"type\":\"number\"},\"amount\":{\"type\":\"number\"},\"remark\":{\"type\":\"string\"}}}}},\"required\":[\"orders\"]}",
        null,
        draftToolExecutors::executeCreateOrderDraft,
        user);
    add(
        callbacks,
        "createContractDraft",
        "创建合同草稿。合同包含基本信息（合同名称、关联商机、合同金额）和订单明细（产品名称、数量、单价、金额）。一次可创建一份合同附带多个订单。商机双通道：已知商机ID传 opportunityId，只知道名称传 opportunityName（可配合 opportunityCompanyName 公司名、opportunityContactName 联系人，后端自动解析，多个匹配会追问候选）；不确定时先调用 queryOpportunity 查出 opportunityId。草稿需要用户确认后才真实创建。参数不全时会返回缺失字段，请据此向用户追问。追问合并时必传 pendingId",
        "{\"type\":\"object\",\"properties\":{\"pendingId\":{\"type\":\"string\",\"description\":\"追问合并时必传\"},\"contractName\":{\"type\":\"string\"},\"opportunityId\":{\"type\":\"integer\",\"description\":\"商机ID，与 opportunityName 二选一\"},\"opportunityName\":{\"type\":\"string\",\"description\":\"商机名称，与 opportunityId 二选一\"},\"opportunityCompanyName\":{\"type\":\"string\"},\"opportunityContactName\":{\"type\":\"string\"},\"totalAmount\":{\"type\":\"number\"},\"orders\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"productName\":{\"type\":\"string\"},\"quantity\":{\"type\":\"number\"},\"unitPrice\":{\"type\":\"number\"},\"amount\":{\"type\":\"number\"},\"remark\":{\"type\":\"string\"}}}}},\"required\":[\"contractName\",\"totalAmount\",\"orders\"]}",
        null,
        draftToolExecutors::executeCreateContractDraft,
        user);
    add(
        callbacks,
        "queryContact",
        "按条件分页查询联系人。可选条件：companyId（客户ID）、name（联系人姓名）。返回联系人列表（含姓名、职位、电话）。新增联系人前建议先调用本工具确认是否已存在同名联系人；若用户只提供了客户名称，先调用 queryCustomerCompany 查出 companyId"
            + AMBIGUITY_GUIDANCE,
        "{\"type\":\"object\",\"properties\":{\"companyId\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"pageNum\":{\"type\":\"integer\"},\"pageSize\":{\"type\":\"integer\"}}}",
        PermissionOperates.CUSTOMER_QUERY_CONTACT,
        queryToolExecutors::executeQueryContact,
        user);
    add(
        callbacks,
        "createCustomerDraft",
        "新增客户公司草稿。字段：companyName（必填）、industry（行业）、customerType（客户类型）、belongGroup（所属集团，选填文本）、dept（部门）、address（地址）、phone（电话）、grade（等级）。草稿需要用户确认后才真实创建。参数不全时会返回缺失字段，请据此向用户追问；用户未提及的可选字段不要追问过多次。追问合并时必传 pendingId",
        "{\"type\":\"object\",\"properties\":{\"pendingId\":{\"type\":\"string\",\"description\":\"追问合并时必传\"},\"companyName\":{\"type\":\"string\"},\"industry\":{\"type\":\"string\"},\"customerType\":{\"type\":\"string\"},\"belongGroup\":{\"type\":\"string\",\"description\":\"所属集团，选填文本\"},\"dept\":{\"type\":\"string\"},\"address\":{\"type\":\"string\"},\"phone\":{\"type\":\"string\"},\"grade\":{\"type\":\"integer\"}},\"required\":[\"companyName\"]}",
        null,
        draftToolExecutors::executeCreateCustomerDraft,
        user);
    add(
        callbacks,
        "createContactDraft",
        "新增联系人草稿。联系人挂在客户公司下。客户双通道：已知客户ID传 companyId，只知道名称传 companyName（后端自动解析，多个匹配会追问候选）；不确定时先调用 queryCustomerCompany 查出 companyId。字段：name（必填）、position（职位）、phone、mobile、email。草稿需要用户确认后才真实创建。参数不全时会返回缺失字段，请据此向用户追问。追问合并时必传 pendingId",
        "{\"type\":\"object\",\"properties\":{\"pendingId\":{\"type\":\"string\",\"description\":\"追问合并时必传\"},\"companyId\":{\"type\":\"integer\",\"description\":\"客户ID，与 companyName 二选一\"},\"companyName\":{\"type\":\"string\",\"description\":\"客户名称，与 companyId 二选一\"},\"name\":{\"type\":\"string\"},\"position\":{\"type\":\"string\"},\"phone\":{\"type\":\"string\"},\"mobile\":{\"type\":\"string\"},\"email\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
        null,
        draftToolExecutors::executeCreateContactDraft,
        user);
    add(
        callbacks,
        "createOpportunityDraft",
        "新增商机草稿。商机挂在客户公司下，是创建合同的前置（合同必须关联商机）。客户双通道：已知客户ID传 companyId，只知道名称传 companyName（后端自动解析，多个匹配会追问候选）；不确定时先调用 queryCustomerCompany 查出 companyId。字段：opportunityName（必填）、amount（金额）、expectedCloseDate（预期成交日期，格式 yyyy-MM-dd HH:mm:ss）、source（来源）、description（描述）。草稿需要用户确认后才真实创建。参数不全时会返回缺失字段，请据此向用户追问。追问合并时必传 pendingId",
        "{\"type\":\"object\",\"properties\":{\"pendingId\":{\"type\":\"string\",\"description\":\"追问合并时必传\"},\"companyId\":{\"type\":\"integer\",\"description\":\"客户ID，与 companyName 二选一\"},\"companyName\":{\"type\":\"string\",\"description\":\"客户名称，与 companyId 二选一\"},\"opportunityName\":{\"type\":\"string\"},\"amount\":{\"type\":\"number\"},\"expectedCloseDate\":{\"type\":\"string\",\"description\":\"yyyy-MM-dd HH:mm:ss\"},\"source\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}},\"required\":[\"opportunityName\"]}",
        null,
        draftToolExecutors::executeCreateOpportunityDraft,
        user);
    add(
        callbacks,
        "queryPayment",
        "按条件分页查询回款记录。可选条件：contractId（合同ID）。返回回款列表（含金额、日期、方式、状态）。若用户仅提供合同名称，先调用 queryContract 查出 contractId"
            + AMBIGUITY_GUIDANCE,
        "{\"type\":\"object\",\"properties\":{\"contractId\":{\"type\":\"integer\"},\"pageNum\":{\"type\":\"integer\"},\"pageSize\":{\"type\":\"integer\"}}}",
        PermissionOperates.FINANCE_VIEW_PAYMENT,
        queryToolExecutors::executeQueryPayment,
        user);
    add(
        callbacks,
        "queryInvoice",
        "按条件分页查询发票记录。可选条件：contractId（合同ID）、invoiceNo（发票号码）。返回发票列表（含号码、金额、日期、类型）。若用户仅提供合同名称，先调用 queryContract 查出 contractId"
            + AMBIGUITY_GUIDANCE,
        "{\"type\":\"object\",\"properties\":{\"contractId\":{\"type\":\"integer\"},\"invoiceNo\":{\"type\":\"string\"},\"pageNum\":{\"type\":\"integer\"},\"pageSize\":{\"type\":\"integer\"}}}",
        PermissionOperates.FINANCE_VIEW_INVOICE,
        queryToolExecutors::executeQueryInvoice,
        user);
    add(
        callbacks,
        "createPaymentDraft",
        "新增回款草稿。回款挂在合同下。合同双通道：已知合同ID传 contractId，只知道名称传 contractName（后端自动解析，多个匹配会追问候选）；不确定时先调用 queryContract 查出 contractId。字段：paymentAmount（必填）、paymentDate（格式 yyyy-MM-dd HH:mm:ss，选填缺省当天）、paymentMethod（回款方式）、remark。草稿需要用户确认后才真实创建。参数不全时会返回缺失字段，请据此向用户追问。追问合并时必传 pendingId",
        "{\"type\":\"object\",\"properties\":{\"pendingId\":{\"type\":\"string\",\"description\":\"追问合并时必传\"},\"contractId\":{\"type\":\"integer\",\"description\":\"合同ID，与 contractName 二选一\"},\"contractName\":{\"type\":\"string\",\"description\":\"合同名称，与 contractId 二选一\"},\"orderItemId\":{\"type\":\"integer\"},\"paymentAmount\":{\"type\":\"number\"},\"paymentDate\":{\"type\":\"string\",\"description\":\"yyyy-MM-dd HH:mm:ss\"},\"paymentMethod\":{\"type\":\"string\"},\"remark\":{\"type\":\"string\"}},\"required\":[\"paymentAmount\"]}",
        null,
        draftToolExecutors::executeCreatePaymentDraft,
        user);
    add(
        callbacks,
        "createInvoiceDraft",
        "新增发票草稿。发票挂在合同下。合同双通道：已知合同ID传 contractId，只知道名称传 contractName（后端自动解析，多个匹配会追问候选）；不确定时先调用 queryContract 查出 contractId。字段：invoiceNo（必填）、invoiceAmount（必填）、invoiceDate（格式 yyyy-MM-dd HH:mm:ss，选填缺省当天）、invoiceType（发票类型）、remark。草稿需要用户确认后才真实创建。参数不全时会返回缺失字段，请据此向用户追问。追问合并时必传 pendingId",
        "{\"type\":\"object\",\"properties\":{\"pendingId\":{\"type\":\"string\",\"description\":\"追问合并时必传\"},\"contractId\":{\"type\":\"integer\",\"description\":\"合同ID，与 contractName 二选一\"},\"contractName\":{\"type\":\"string\",\"description\":\"合同名称，与 contractId 二选一\"},\"paymentId\":{\"type\":\"integer\"},\"invoiceNo\":{\"type\":\"string\"},\"invoiceAmount\":{\"type\":\"number\"},\"invoiceDate\":{\"type\":\"string\",\"description\":\"yyyy-MM-dd HH:mm:ss\"},\"invoiceType\":{\"type\":\"string\"},\"remark\":{\"type\":\"string\"}},\"required\":[\"invoiceNo\",\"invoiceAmount\"]}",
        null,
        draftToolExecutors::executeCreateInvoiceDraft,
        user);
    add(
        callbacks,
        "queryCompanyGroup",
        "查询集团主数据列表（客户归属集团的参考）。可选条件：groupName（集团名称模糊关键字）、status（1-启用，0-停用）。返回集团 id/groupName/status",
        "{\"type\":\"object\",\"properties\":{\"groupName\":{\"type\":\"string\"},\"status\":{\"type\":\"integer\",\"enum\":[0,1]}}}",
        PermissionOperates.CUSTOMER_QUERY_COMPANY,
        queryToolExecutors::executeQueryCompanyGroup,
        user);

    return callbacks;
  }

  private void add(
      List<ToolCallback> callbacks,
      String name,
      String description,
      String inputSchema,
      PermissionOperates permission,
      AiToolExecutor executor,
      RoleAO user) {
    ToolCallback callback =
        callbackFactory.build(name, description, inputSchema, permission, executor, user);
    if (callback != null) {
      callbacks.add(callback);
    }
  }
}
