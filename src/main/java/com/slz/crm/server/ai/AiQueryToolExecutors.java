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
    dto.setContractId(toLong(args.get("contractId")));
    dto.setProductName((String) args.get("productName"));
    Page<OrderVO> page =
        contractOrderItemService.orderQuery(
            toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
    return trimPageResult(page.getRecords());
  }

  public Object executeGetOrderDetail(Map<String, Object> args, ToolContext toolContext) {
    return contractOrderItemService.getDetailById(toLong(args.get("orderId")));
  }

  public Object executeQueryContract(Map<String, Object> args, ToolContext toolContext) {
    ContractDTO dto = new ContractDTO();
    dto.setContractName((String) args.get("contractName"));
    Page<ContractVO> page =
        contractService.contractQuery(
            toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
    return trimPageResult(page.getRecords());
  }

  public Object executeQueryCustomerCompany(Map<String, Object> args, ToolContext toolContext) {
    Page<CustomerCompanyVO> page =
        customerCompanyService.findCompany(
            (String) args.get("companyName"),
            toInt(args.get("pageNum"), 1),
            toInt(args.get("pageSize"), 10));
    return trimPageResult(page.getRecords());
  }

  public Object executeQueryOpportunity(Map<String, Object> args, ToolContext toolContext) {
    SalesOpportunityQueryDTO query = new SalesOpportunityQueryDTO();
    query.setOpportunityName((String) args.get("opportunityName"));
    query.setCompanyName((String) args.get("companyName"));
    query.setContactName((String) args.get("contactName"));
    Page<SalesOpportunityVO> page =
        salesOpportunityService.getSalesOpportunityByQuery(
            query, toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10));
    return AiExtendedQueryToolExecutors.toOpportunityRows(trimPageResult(page.getRecords()));
  }

  public Object executeQueryContact(Map<String, Object> args, ToolContext toolContext) {
    CustomerContactDTO dto = new CustomerContactDTO();
    dto.setCompanyId(toLong(args.get("companyId")));
    dto.setName((String) args.get("name"));
    Page<CustomerContactVO> page =
        customerContactService.search(
            dto, toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10));
    return trimPageResult(page.getRecords());
  }

  public Object executeQueryCompanyGroup(Map<String, Object> args, ToolContext toolContext) {
    Integer status = args.get("status") == null ? null : toInt(args.get("status"), 1);
    List<CompanyGroupEntity> groups =
        companyGroupService.list((String) args.get("groupName"), status);
    return AiExtendedQueryToolExecutors.toCompanyGroupRows(groups);
  }

  public Object executeQueryPayment(Map<String, Object> args, ToolContext toolContext) {
    PaymentRecordDTO dto = new PaymentRecordDTO();
    dto.setContractId(toLong(args.get("contractId")));
    Page<PaymentRecordVO> page =
        paymentRecordService.queryPage(
            toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
    return trimPageResult(page.getRecords());
  }

  public Object executeQueryInvoice(Map<String, Object> args, ToolContext toolContext) {
    InvoiceInfoDTO dto = new InvoiceInfoDTO();
    dto.setContractId(toLong(args.get("contractId")));
    dto.setInvoiceNo((String) args.get("invoiceNo"));
    Page<InvoiceInfoVO> page =
        invoiceInfoService.queryPage(
            toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
    return trimPageResult(page.getRecords());
  }

  /** extend-ai-query-tools 任务 2.1：按条件分页查询联络任务。 */
  public Object executeQueryContactTask(Map<String, Object> args, ToolContext toolContext) {
    ContactTaskDTO dto = new ContactTaskDTO();
    dto.setAssigneeId(toLong(args.get("assigneeId")));
    dto.setCompanyId(toLong(args.get("companyId")));
    dto.setContactId(toLong(args.get("contactId")));
    dto.setOpportunityId(toLong(args.get("opportunityId")));
    Page<ContactTaskVO> page =
        contactTaskService.query(
            toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
    return trimPageResult(page.getRecords());
  }

  /** extend-ai-query-tools 任务 2.2：按任务ID分页查询任务评论。 */
  public Object executeQueryTaskComment(Map<String, Object> args, ToolContext toolContext) {
    Long taskId = toLong(args.get("taskId"));
    if (taskId == null) {
      throw new IllegalArgumentException("taskId 不能为空");
    }
    Page<TaskCommentVO> page =
        taskCommentService.getByTaskId(
            taskId, toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10));
    return trimPageResult(page.getRecords());
  }

  /** extend-ai-query-tools 任务 2.3：查询业务活动（支持按ID查询详情或条件分页查询）。 */
  public Object executeQueryBusinessActivity(Map<String, Object> args, ToolContext toolContext) {
    Long rawId = toLong(args.get("activityId") != null ? args.get("activityId") : args.get("id"));
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
              toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
      result = trimPageResult(page.getRecords());
    }
    return result;
  }

  /** extend-ai-query-tools 任务 2.4：按合同ID查询合同详情（含订单信息）。 */
  public Object executeGetContractDetail(Map<String, Object> args, ToolContext toolContext) {
    Long contractId = toLong(args.get("contractId"));
    if (contractId == null) {
      throw new IllegalArgumentException("contractId 不能为空");
    }
    return contractService.getDetailById(contractId);
  }

  /** extend-ai-query-tools 任务 2.5：按商机ID查询商机详情。 */
  public Object executeGetOpportunityDetail(Map<String, Object> args, ToolContext toolContext) {
    Long opportunityId = toLong(args.get("opportunityId"));
    if (opportunityId == null) {
      throw new IllegalArgumentException("opportunityId 不能为空");
    }
    return salesOpportunityService.getOpportunityDetailById(opportunityId);
  }

  /** extend-ai-query-tools 任务 2.6：按条件分页查询商机阶段推进审批列表。 */
  public Object executeQueryStageApproval(Map<String, Object> args, ToolContext toolContext) {
    SalesStageApprovalDTO dto = new SalesStageApprovalDTO();
    dto.setOpportunityId(toLong(args.get("opportunityId")));
    Object statusObj = args.get("approvalStatus");
    if (statusObj instanceof List<?> list) {
      dto.setApprovalStatus(list.stream().map(v -> ((Number) v).intValue()).toList());
    } else if (statusObj instanceof Number num) {
      dto.setApprovalStatus(List.of(num.intValue()));
    }
    Page<SalesStageApprovalEntity> page =
        new Page<>(toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10));
    Page<SalesStageApprovalVO> resultPage = salesStageApprovalService.getPage(page, dto);
    return trimPageResult(resultPage.getRecords());
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
    Long companyId = toLong(args.get("companyId"));
    if (companyId == null) {
      throw new IllegalArgumentException("companyId 不能为空");
    }
    return customerInsightService.diagnose(companyId);
  }

  private <T> List<T> trimPageResult(List<T> records) {
    List<T> result = records;
    if (records != null && records.size() > 10) {
      result = records.subList(0, 10);
    }
    return result;
  }

  private Integer toInt(Object value, int defaultValue) {
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  private Long toLong(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }
}
