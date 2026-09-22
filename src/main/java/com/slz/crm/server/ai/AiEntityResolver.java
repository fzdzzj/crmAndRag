package com.slz.crm.server.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.ContractService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.SalesOpportunityService;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AI 草稿实体解析器：名称→ID 的确定性解析（下沉到校验层，不依赖 LLM 链式调用） 双通道入参：已知 ID 校验存在性；仅提供名称则模糊查询——唯一命中解析、多命中返回候选、零命中提示
 */
@Slf4j
@Component
public class AiEntityResolver {

  /** 查询页大小兜底（未配置或非法配置时使用） */
  private static final int DEFAULT_PAGE_SIZE = 1000;

  /** 补救候选列表上限（避免一次返回过多候选项） */
  private static final int FALLBACK_LIMIT = 50;

  @Autowired private ContractService contractService;

  @Autowired private SalesOpportunityService salesOpportunityService;

  @Autowired private CustomerCompanyService customerCompanyService;

  @Autowired private AiProperties aiProperties;

  public enum Status {
    /** 唯一命中或 ID 校验通过 */
    RESOLVED,
    /** ID 与名称均未提供 */
    MISSING,
    /** 名称查询零命中 / ID 不存在 */
    NOT_FOUND,
    /** 名称查询多条命中 */
    AMBIGUOUS,
    /** 名称查询零命中后补救：查询用户名下全部作为候选 */
    FALLBACK
  }

  /** 解析结果 */
  @Data
  @AllArgsConstructor
  public static class Resolution {
    private Status status;
    private Long resolvedId;
    private List<String> candidates;

    static Resolution resolved(Long id) {
      return new Resolution(Status.RESOLVED, id, List.of());
    }

    static Resolution missing() {
      return new Resolution(Status.MISSING, null, List.of());
    }

    static Resolution notFound() {
      return new Resolution(Status.NOT_FOUND, null, List.of());
    }

    static Resolution ambiguous(List<String> candidates) {
      return new Resolution(Status.AMBIGUOUS, null, candidates);
    }

    static Resolution fallback(List<String> candidates) {
      return new Resolution(Status.FALLBACK, null, candidates);
    }
  }

  /** 解析合同：有 contractId 校验存在性；仅有 contractName 按名称模糊查询 */
  public Resolution resolveContract(Long contractId, String contractName) {
    Resolution result;
    if (contractId != null) {
      result = contractExists(contractId) ? Resolution.resolved(contractId) : Resolution.notFound();
    } else if (contractName == null || contractName.isBlank()) {
      result = Resolution.missing();
    } else {
      ContractDTO query = new ContractDTO();
      query.setContractName(contractName.trim());
      Page<ContractVO> page = contractService.contractQuery(1, pageSize(), query);
      List<ContractVO> records = page.getRecords();

      if (records == null || records.isEmpty()) {
        // 补救：名称查询零命中时，查询全部合同作为候选供用户选择
        result = fallbackContracts();
      } else if (records.size() == 1) {
        result = Resolution.resolved(records.get(0).getId());
      } else {
        List<String> candidates =
            records.stream().map(c -> c.getContractNo() + " " + c.getContractName()).toList();
        result = Resolution.ambiguous(candidates);
      }
    }
    return result;
  }

  /** 解析商机：有 opportunityId 校验存在性；仅有 opportunityName 按名称/公司/联系人模糊查询 */
  public Resolution resolveOpportunity(
      Long opportunityId, String opportunityName, String companyName, String contactName) {
    boolean noName =
        (opportunityName == null || opportunityName.isBlank())
            && (companyName == null || companyName.isBlank())
            && (contactName == null || contactName.isBlank());
    Resolution result;
    if (opportunityId != null) {
      result =
          opportunityExists(opportunityId)
              ? Resolution.resolved(opportunityId)
              : Resolution.notFound();
    } else if (noName) {
      result = Resolution.missing();
    } else {
      SalesOpportunityQueryDTO query = new SalesOpportunityQueryDTO();
      query.setOpportunityName(trimToNull(opportunityName));
      query.setCompanyName(trimToNull(companyName));
      query.setContactName(trimToNull(contactName));
      Page<SalesOpportunityVO> page =
          salesOpportunityService.getSalesOpportunityByQuery(query, 1, pageSize());
      List<SalesOpportunityVO> records = page.getRecords();

      if (records == null || records.isEmpty()) {
        // 补救：名称查询零命中时，查询全部商机作为候选供用户选择
        result = fallbackOpportunities();
      } else if (records.size() == 1) {
        result = Resolution.resolved(records.get(0).getId());
      } else {
        List<String> oppCandidates =
            records.stream()
                .map(o -> o.getOpportunityName() + "（" + o.getCompanyName() + "）")
                .toList();
        result = Resolution.ambiguous(oppCandidates);
      }
    }
    return result;
  }

  private boolean contractExists(Long contractId) {
    boolean result;
    try {
      contractService.getContractById(contractId);
      result = true;
    } catch (BaseException e) {
      result = false;
    }
    return result;
  }

  /** 补救：查询全部合同作为候选（供前端下拉框搜索选择） */
  private Resolution fallbackContracts() {
    Page<ContractVO> allPage =
        contractService.contractQuery(1, fallbackPageSize(), new ContractDTO());
    List<ContractVO> allRecords = allPage.getRecords();
    Resolution result;
    if (allRecords == null || allRecords.isEmpty()) {
      result = Resolution.notFound();
    } else {
      List<String> candidates =
          allRecords.stream().map(c -> c.getContractNo() + " " + c.getContractName()).toList();
      result = Resolution.fallback(candidates);
    }
    return result;
  }

  private boolean opportunityExists(Long opportunityId) {
    boolean result;
    try {
      salesOpportunityService.getOpportunityDetailById(opportunityId);
      result = true;
    } catch (BaseException e) {
      result = false;
    }
    return result;
  }

  private String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** 解析客户公司：有 companyId 校验存在性；仅有 companyName 按名称模糊查询（与 resolveContract 同模式） */
  public Resolution resolveCustomerCompany(Long companyId, String companyName) {
    Resolution result;
    if (companyId != null) {
      result =
          customerCompanyExists(companyId) ? Resolution.resolved(companyId) : Resolution.notFound();
    } else if (companyName == null || companyName.isBlank()) {
      result = Resolution.missing();
    } else {
      Page<CustomerCompanyVO> page =
          customerCompanyService.findCompany(companyName.trim(), 1, pageSize());
      List<CustomerCompanyVO> records = page.getRecords();

      if (records == null || records.isEmpty()) {
        // 补救：查询全部客户公司作为候选
        result = fallbackCustomerCompanies();
      } else if (records.size() == 1) {
        result = Resolution.resolved(records.get(0).getId());
      } else {
        List<String> candidates = records.stream().map(c -> c.getCompanyName()).toList();
        result = Resolution.ambiguous(candidates);
      }
    }
    return result;
  }

  private boolean customerCompanyExists(Long companyId) {
    boolean result;
    try {
      result = customerCompanyService.getCompanyByCondition(companyId) != null;
    } catch (BaseException e) {
      result = false;
    }
    return result;
  }

  /** 补救：查询全部客户公司作为候选（供前端下拉框搜索选择） */
  private Resolution fallbackCustomerCompanies() {
    Page<CustomerCompanyVO> allPage = customerCompanyService.findCompany("", 1, fallbackPageSize());
    List<CustomerCompanyVO> allRecords = allPage.getRecords();
    Resolution result;
    if (allRecords == null || allRecords.isEmpty()) {
      result = Resolution.notFound();
    } else {
      List<String> candidates = allRecords.stream().map(CustomerCompanyVO::getCompanyName).toList();
      result = Resolution.fallback(candidates);
    }
    return result;
  }

  /** 补救：查询全部商机作为候选（供前端下拉框搜索选择） */
  private Resolution fallbackOpportunities() {
    Page<SalesOpportunityVO> allPage =
        salesOpportunityService.getSalesOpportunityByQuery(
            new SalesOpportunityQueryDTO(), 1, fallbackPageSize());
    List<SalesOpportunityVO> allRecords = allPage.getRecords();
    Resolution result;
    if (allRecords == null || allRecords.isEmpty()) {
      result = Resolution.notFound();
    } else {
      List<String> candidates =
          allRecords.stream()
              .map(o -> o.getOpportunityName() + "（" + o.getCompanyName() + "）")
              .toList();
      result = Resolution.fallback(candidates);
    }
    return result;
  }

  private int pageSize() {
    Integer configured = aiProperties.getEntityQueryPageSize();
    return configured == null || configured <= 0 ? DEFAULT_PAGE_SIZE : configured;
  }

  private int fallbackPageSize() {
    return Math.min(pageSize(), FALLBACK_LIMIT);
  }
}
