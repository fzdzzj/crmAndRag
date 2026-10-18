package com.slz.crm.server.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.BusinessActivityQueryDTO;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.dto.DataStatisticsDTO;
import com.slz.crm.pojo.dto.InvoiceInfoDTO;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.dto.PaymentRecordDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.entity.CompanyGroupEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.pojo.vo.TaskCommentVO;
import com.slz.crm.server.ai.insight.CustomerInsightService;
import com.slz.crm.server.service.BusinessActivityService;
import com.slz.crm.server.service.CompanyGroupService;
import com.slz.crm.server.service.ContactTaskService;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.ContractService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.DataStatisticsService;
import com.slz.crm.server.service.InvoiceInfoService;
import com.slz.crm.server.service.PaymentRecordService;
import com.slz.crm.server.service.SalesOpportunityService;
import com.slz.crm.server.service.SalesStageApprovalService;
import com.slz.crm.server.service.TaskCommentService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiQueryToolExecutors {

  private static final DateTimeFormatter DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final DataStatisticsService dataStatisticsService;
  private final ContractOrderItemService contractOrderItemService;
  private final ContractService contractService;
  private final CustomerCompanyService customerCompanyService;
  private final CompanyGroupService companyGroupService;
  private final CustomerContactService customerContactService;
  private final PaymentRecordService paymentRecordService;
  private final InvoiceInfoService invoiceInfoService;
  private final SalesOpportunityService salesOpportunityService;
  private final ContactTaskService contactTaskService;
  private final TaskCommentService taskCommentService;
  private final BusinessActivityService businessActivityService;
  private final SalesStageApprovalService salesStageApprovalService;
  private final AiExtendedQueryToolExecutors extendedQueryToolExecutors;
  private final CustomerInsightService customerInsightService;

  public Object executeGetChartData(Map<String, Object> args, ToolContext toolContext)
      throws Exception {
    DataStatisticsDTO dto = new DataStatisticsDTO();
    dto.setDataType(
        com.slz.crm.common.enumeration.ChartDataType.valueOf((String) args.get("dataType")));
    String timeRange = (String) args.get("timeRange");
    if (timeRange != null && !"CUSTOM_RANGE".equals(timeRange)) {
      dto.setTimeRange(
          com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate.valueOf(timeRange));
    }
    if (args.get("startTime") != null) {
      dto.setStartTime(LocalDateTime.parse((String) args.get("startTime"), DATE_TIME_FORMAT));
    }
    if (args.get("endTime") != null) {
      dto.setEndTime(LocalDateTime.parse((String) args.get("endTime"), DATE_TIME_FORMAT));
    }
    return dataStatisticsService.getChartData(dto);
  }

  public Object executeGetStatisticsSummary(Map<String, Object> args, ToolContext toolContext) {
    return dataStatisticsService.getStatisticsSummary(
        LocalDateTime.parse((String) args.get("startTime"), DATE_TIME_FORMAT),
        LocalDateTime.parse((String) args.get("endTime"), DATE_TIME_FORMAT));
  }

  public Object executeGetOpportunityStageDistribution(
      Map<String, Object> args, ToolContext toolContext) {
    return dataStatisticsService.getOpportunityStageDistribution();
  }

  public Object executeQueryOrder(Map<String, Object> args, ToolContext toolContext) {
    OrderDTO dto = new OrderDTO();
    dto.setContractId(AiQueryToolSupport.toLong(args.get("contractId")));
    dto.setProductName((String) args.get("productName"));
    Page<OrderVO> page =
        contractOrderItemService.orderQuery(
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10),
            dto);
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  public Object executeGetOrderDetail(Map<String, Object> args, ToolContext toolContext) {
    AiQueryToolSupport.requireLoggedInRole();
    return contractOrderItemService.getDetailById(AiQueryToolSupport.toLong(args.get("orderId")));
  }

  /**
   * 按条件分页查询合同（结构化查询工具）：方法本身只做身份守卫；非超管的数据范围收敛依赖 QueryWrapperAspect 在 Mapper 层注入（{@code contract}
   * 为受管表），不在此重复实现过滤。
   */
  public Object executeQueryContract(Map<String, Object> args, ToolContext toolContext) {
    AiQueryToolSupport.requireLoggedInRole();
    ContractDTO dto = new ContractDTO();
    dto.setContractName((String) args.get("contractName"));
    Page<ContractVO> page =
        contractService.contractQuery(
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10),
            dto);
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  public Object executeQueryCustomerCompany(Map<String, Object> args, ToolContext toolContext) {
    Page<CustomerCompanyVO> page =
        customerCompanyService.findCompany(
            (String) args.get("companyName"),
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10));
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  public Object executeQueryOpportunity(Map<String, Object> args, ToolContext toolContext) {
    SalesOpportunityQueryDTO query = new SalesOpportunityQueryDTO();
    query.setOpportunityName((String) args.get("opportunityName"));
    query.setCompanyName((String) args.get("companyName"));
    query.setContactName((String) args.get("contactName"));
    Page<SalesOpportunityVO> page =
        salesOpportunityService.getSalesOpportunityByQuery(
            query,
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10));
    return AiExtendedQueryToolExecutors.toOpportunityRows(
        AiQueryToolSupport.trimPageResult(page.getRecords()));
  }

  public Object executeQueryContact(Map<String, Object> args, ToolContext toolContext) {
    CustomerContactDTO dto = new CustomerContactDTO();
    dto.setCompanyId(AiQueryToolSupport.toLong(args.get("companyId")));
    dto.setName((String) args.get("name"));
    Page<CustomerContactVO> page =
        customerContactService.search(
            dto,
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10));
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  public Object executeQueryCompanyGroup(Map<String, Object> args, ToolContext toolContext) {
    Integer status =
        args.get("status") == null ? null : AiQueryToolSupport.toInt(args.get("status"), 1);
    List<CompanyGroupEntity> groups =
        companyGroupService.list((String) args.get("groupName"), status);
    return AiExtendedQueryToolExecutors.toCompanyGroupRows(groups);
  }

  /**
   * 按条件分页查询回款记录（结构化查询工具）：身份守卫同 {@link #executeQueryContract}， 数据范围收敛依赖 QueryWrapperAspect 在 Mapper
   * 层注入（{@code payment_record} 为受管表）。
   */
  public Object executeQueryPayment(Map<String, Object> args, ToolContext toolContext) {
    AiQueryToolSupport.requireLoggedInRole();
    PaymentRecordDTO dto = new PaymentRecordDTO();
    dto.setContractId(AiQueryToolSupport.toLong(args.get("contractId")));
    Page<PaymentRecordVO> page =
        paymentRecordService.queryPage(
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10),
            dto);
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  /** 按条件分页查询发票记录（结构化查询工具）：身份守卫同 {@link #executeQueryContract}。 */
  public Object executeQueryInvoice(Map<String, Object> args, ToolContext toolContext) {
    AiQueryToolSupport.requireLoggedInRole();
    InvoiceInfoDTO dto = new InvoiceInfoDTO();
    dto.setContractId(AiQueryToolSupport.toLong(args.get("contractId")));
    dto.setInvoiceNo((String) args.get("invoiceNo"));
    Page<InvoiceInfoVO> page =
        invoiceInfoService.queryPage(
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10),
            dto);
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  /**
   * extend-ai-query-tools 任务 2.1：按条件分页查询联络任务（结构化查询工具）：身份守卫同 {@link #executeQueryContract}，数据范围收敛依赖
   * QueryWrapperAspect（{@code contact_task} 为受管表）。
   */
  public Object executeQueryContactTask(Map<String, Object> args, ToolContext toolContext) {
    AiQueryToolSupport.requireLoggedInRole();
    ContactTaskDTO dto = new ContactTaskDTO();
    dto.setAssigneeId(AiQueryToolSupport.toLong(args.get("assigneeId")));
    dto.setCompanyId(AiQueryToolSupport.toLong(args.get("companyId")));
    dto.setContactId(AiQueryToolSupport.toLong(args.get("contactId")));
    dto.setOpportunityId(AiQueryToolSupport.toLong(args.get("opportunityId")));
    Page<ContactTaskVO> page =
        contactTaskService.query(
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10),
            dto);
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  /** extend-ai-query-tools 任务 2.2：按任务ID分页查询任务评论（任务读权限断言在 TaskCommentServiceImpl 内）。 */
  public Object executeQueryTaskComment(Map<String, Object> args, ToolContext toolContext) {
    AiQueryToolSupport.requireLoggedInRole();
    Long taskId = AiQueryToolSupport.toLong(args.get("taskId"));
    if (taskId == null) {
      throw new IllegalArgumentException("taskId 不能为空");
    }
    Page<TaskCommentVO> page =
        taskCommentService.getByTaskId(
            taskId,
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10));
    return AiQueryToolSupport.trimPageResult(page.getRecords());
  }

  /** extend-ai-query-tools 任务 2.3：查询业务活动（支持按ID查询详情或条件分页查询）。 */
  public Object executeQueryBusinessActivity(Map<String, Object> args, ToolContext toolContext) {
    Long rawId =
        AiQueryToolSupport.toLong(
            args.get("activityId") != null ? args.get("activityId") : args.get("id"));
    Object result;
    if (rawId != null) {
      result = businessActivityService.getDetailById(rawId);
    } else {
      BusinessActivityQueryDTO dto = new BusinessActivityQueryDTO();
      dto.setActivityTitle((String) args.get("activityTitle"));
      dto.setCompanyName((String) args.get("companyName"));
      dto.setOpportunityName((String) args.get("opportunityName"));
      dto.setActivityType((String) args.get("activityType"));
      Page<BusinessActivityVO> page =
          businessActivityService.businessActivityQuery(
              AiQueryToolSupport.toInt(args.get("pageNum"), 1),
              AiQueryToolSupport.toInt(args.get("pageSize"), 10),
              dto);
      result = AiQueryToolSupport.trimPageResult(page.getRecords());
    }
    return result;
  }

  /** extend-ai-query-tools 任务 2.4：按合同ID查询合同详情（含订单信息；合同读权限断言在 ContractServiceImpl 内）。 */
  public Object executeGetContractDetail(Map<String, Object> args, ToolContext toolContext) {
    AiQueryToolSupport.requireLoggedInRole();
    Long contractId = AiQueryToolSupport.toLong(args.get("contractId"));
    if (contractId == null) {
      throw new IllegalArgumentException("contractId 不能为空");
    }
    return contractService.getDetailById(contractId);
  }

  /** extend-ai-query-tools 任务 2.5：按商机ID查询商机详情。 */
  public Object executeGetOpportunityDetail(Map<String, Object> args, ToolContext toolContext) {
    Long opportunityId = AiQueryToolSupport.toLong(args.get("opportunityId"));
    if (opportunityId == null) {
      throw new IllegalArgumentException("opportunityId 不能为空");
    }
    return salesOpportunityService.getOpportunityDetailById(opportunityId);
  }

  /** extend-ai-query-tools 任务 2.6：按条件分页查询商机阶段推进审批列表。 */
  public Object executeQueryStageApproval(Map<String, Object> args, ToolContext toolContext) {
    SalesStageApprovalDTO dto = new SalesStageApprovalDTO();
    dto.setOpportunityId(AiQueryToolSupport.toLong(args.get("opportunityId")));
    dto.setApprovalStatus(AiQueryToolSupport.approvalStatusList(args.get("approvalStatus")));
    Page<SalesStageApprovalEntity> page =
        new Page<>(
            AiQueryToolSupport.toInt(args.get("pageNum"), 1),
            AiQueryToolSupport.toInt(args.get("pageSize"), 10));
    Page<SalesStageApprovalVO> resultPage = salesStageApprovalService.getPage(page, dto);
    return AiQueryToolSupport.trimPageResult(resultPage.getRecords());
  }

  /** extend-ai-query-tools-c 任务 2.1：按条件分页查询系统内部用户；执行细节委派 {@link AiExtendedQueryToolExecutors}。 */
  public Object executeQueryUser(Map<String, Object> args, ToolContext toolContext) {
    return extendedQueryToolExecutors.executeQueryUser(args, toolContext);
  }

  /** extend-ai-query-tools-c 任务 2.2：查询系统组织架构部门列表；执行细节委派 {@link AiExtendedQueryToolExecutors}。 */
  public Object executeQueryDept(Map<String, Object> args, ToolContext toolContext) {
    return extendedQueryToolExecutors.executeQueryDept(args, toolContext);
  }

  /**
   * extend-ai-query-tools-c 任务 2.3：按客户公司ID查询客户公司档案详情；执行细节委派 {@link AiExtendedQueryToolExecutors}。
   */
  public Object executeGetCustomerCompanyDetail(Map<String, Object> args, ToolContext toolContext) {
    return extendedQueryToolExecutors.executeGetCustomerCompanyDetail(args, toolContext);
  }

  /** extend-ai-query-tools-c 任务 2.4：查询合同与商机统计报表；执行细节委派 {@link AiExtendedQueryToolExecutors}。 */
  public Object executeQueryReport(Map<String, Object> args, ToolContext toolContext) {
    return extendedQueryToolExecutors.executeQueryReport(args, toolContext);
  }

  /**
   * add-ai-customer-360-insight 任务 3.1：获取指定客户的 360° AI 商业健康度诊断（只读；权限由 {@link
   * com.slz.crm.server.ai.insight.CustomerInsightService} 内部 DataScope 守卫收口）。
   */
  public Object executeGetCustomerInsight(Map<String, Object> args, ToolContext toolContext) {
    Long companyId = AiQueryToolSupport.toLong(args.get("companyId"));
    if (companyId == null) {
      throw new IllegalArgumentException("companyId 不能为空");
    }
    return customerInsightService.diagnose(companyId);
  }
}
