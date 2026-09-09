package com.slz.crm.server.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.dto.DataStatisticsDTO;
import com.slz.crm.pojo.dto.InvoiceInfoDTO;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.dto.PaymentRecordDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.entity.CompanyGroupEntity;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.service.CompanyGroupService;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.ContractService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.DataStatisticsService;
import com.slz.crm.server.service.InvoiceInfoService;
import com.slz.crm.server.service.PaymentRecordService;
import com.slz.crm.server.service.SalesOpportunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public Object executeGetChartData(Map<String, Object> args, ToolContext toolContext) throws Exception {
        DataStatisticsDTO dto = new DataStatisticsDTO();
        dto.setDataType(com.slz.crm.common.enumeration.ChartDataType.valueOf((String) args.get("dataType")));
        String timeRange = (String) args.get("timeRange");
        if (timeRange != null && !"CUSTOM_RANGE".equals(timeRange)) {
            dto.setTimeRange(com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate.valueOf(timeRange));
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

    public Object executeGetOpportunityStageDistribution(Map<String, Object> args, ToolContext toolContext) {
        return dataStatisticsService.getOpportunityStageDistribution();
    }

    public Object executeQueryOrder(Map<String, Object> args, ToolContext toolContext) {
        OrderDTO dto = new OrderDTO();
        if (args.get("contractId") != null) {
            dto.setContractId(((Number) args.get("contractId")).longValue());
        }
        dto.setProductName((String) args.get("productName"));
        Page<OrderVO> page = contractOrderItemService.orderQuery(
                toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
        return trimPageResult(page.getRecords());
    }

    public Object executeGetOrderDetail(Map<String, Object> args, ToolContext toolContext) {
        return contractOrderItemService.getDetailById(((Number) args.get("orderId")).longValue());
    }

    public Object executeQueryContract(Map<String, Object> args, ToolContext toolContext) {
        ContractDTO dto = new ContractDTO();
        dto.setContractName((String) args.get("contractName"));
        Page<ContractVO> page = contractService.contractQuery(
                toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
        return trimPageResult(page.getRecords());
    }

    public Object executeQueryCustomerCompany(Map<String, Object> args, ToolContext toolContext) {
        Page<CustomerCompanyVO> page = customerCompanyService.findCompany(
                (String) args.get("companyName"), toInt(args.get("pageNum"), 1),
                toInt(args.get("pageSize"), 10));
        return trimPageResult(page.getRecords());
    }

    public Object executeQueryOpportunity(Map<String, Object> args, ToolContext toolContext) {
        SalesOpportunityQueryDTO query = new SalesOpportunityQueryDTO();
        query.setOpportunityName((String) args.get("opportunityName"));
        query.setCompanyName((String) args.get("companyName"));
        query.setContactName((String) args.get("contactName"));
        Page<SalesOpportunityVO> page = salesOpportunityService.getSalesOpportunityByQuery(
                query, toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10));

        List<Map<String, Object>> result = new ArrayList<>();
        for (SalesOpportunityVO vo : trimPageResult(page.getRecords())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", vo.getId());
            item.put("opportunityName", vo.getOpportunityName());
            item.put("companyName", vo.getCompanyName());
            item.put("stage", vo.getStage());
            item.put("amount", vo.getAmount());
            result.add(item);
        }
        return result;
    }

    public Object executeQueryContact(Map<String, Object> args, ToolContext toolContext) {
        CustomerContactDTO dto = new CustomerContactDTO();
        if (args.get("companyId") != null) {
            dto.setCompanyId(((Number) args.get("companyId")).longValue());
        }
        dto.setName((String) args.get("name"));
        Page<CustomerContactVO> page = customerContactService.search(
                dto, toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10));
        return trimPageResult(page.getRecords());
    }

    public Object executeQueryCompanyGroup(Map<String, Object> args, ToolContext toolContext) {
        Integer status = args.get("status") == null ? null : toInt(args.get("status"), 1);
        List<CompanyGroupEntity> groups = companyGroupService.list(
                (String) args.get("groupName"), status);

        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanyGroupEntity group : groups) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", group.getId());
            item.put("groupName", group.getGroupName());
            item.put("status", group.getStatus());
            result.add(item);
        }
        return result;
    }

    public Object executeQueryPayment(Map<String, Object> args, ToolContext toolContext) {
        PaymentRecordDTO dto = new PaymentRecordDTO();
        if (args.get("contractId") != null) {
            dto.setContractId(((Number) args.get("contractId")).longValue());
        }
        Page<PaymentRecordVO> page = paymentRecordService.queryPage(
                toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
        return trimPageResult(page.getRecords());
    }

    public Object executeQueryInvoice(Map<String, Object> args, ToolContext toolContext) {
        InvoiceInfoDTO dto = new InvoiceInfoDTO();
        if (args.get("contractId") != null) {
            dto.setContractId(((Number) args.get("contractId")).longValue());
        }
        dto.setInvoiceNo((String) args.get("invoiceNo"));
        Page<InvoiceInfoVO> page = invoiceInfoService.queryPage(
                toInt(args.get("pageNum"), 1), toInt(args.get("pageSize"), 10), dto);
        return trimPageResult(page.getRecords());
    }

    private <T> List<T> trimPageResult(List<T> records) {
        if (records == null || records.size() <= 10) {
            return records;
        }
        return records.subList(0, 10);
    }

    private Integer toInt(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
