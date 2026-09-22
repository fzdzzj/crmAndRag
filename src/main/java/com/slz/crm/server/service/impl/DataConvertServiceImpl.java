package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataConvertService;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** 统一数据转换服务实现 提供ID转名称的通用方法，支持批量转换以优化性能 */
@Service
@Slf4j
public class DataConvertServiceImpl implements DataConvertService {

  private final UserMapper userMapper;
  private final CustomerCompanyMapper customerCompanyMapper;
  private final CustomerContactMapper customerContactMapper;
  private final SalesOpportunityMapper salesOpportunityMapper;
  private final ContractMapper contractMapper;
  private final SysDeptMapper sysDeptMapper;

  public DataConvertServiceImpl(
      UserMapper userMapper,
      CustomerCompanyMapper customerCompanyMapper,
      CustomerContactMapper customerContactMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      ContractMapper contractMapper,
      SysDeptMapper sysDeptMapper) {
    this.userMapper = userMapper;
    this.customerCompanyMapper = customerCompanyMapper;
    this.customerContactMapper = customerContactMapper;
    this.salesOpportunityMapper = salesOpportunityMapper;
    this.contractMapper = contractMapper;
    this.sysDeptMapper = sysDeptMapper;
  }

  // ===== 单个转换（委托给批量方法，走缓存） =====

  @Override
  @Cacheable(
      value = "userName",
      key = "#userId != null ? #userId : 'null'",
      unless = "#result == null")
  public String getUserName(Long userId) {
    String name = null;
    if (userId != null) {
      UserEntity user = userMapper.selectById(userId);
      if (user != null) {
        name = user.getRealName();
      }
    }
    return name;
  }

  @Override
  @Cacheable(
      value = "deptName",
      key = "#deptId != null ? #deptId : 'null'",
      unless = "#result == null")
  public String getDeptName(Long deptId) {
    String name = null;
    if (deptId != null) {
      SysDeptEntity dept = sysDeptMapper.selectById(deptId);
      if (dept != null) {
        name = dept.getDeptName();
      }
    }
    return name;
  }

  @Override
  @Cacheable(
      value = "companyName",
      key = "#companyId != null ? #companyId : 'null'",
      unless = "#result == null")
  public String getCompanyName(Long companyId) {
    String name = null;
    if (companyId != null) {
      CustomerCompanyEntity company = customerCompanyMapper.selectById(companyId);
      if (company != null) {
        name = company.getCompanyName();
      }
    }
    return name;
  }

  @Override
  @Cacheable(
      value = "contactName",
      key = "#contactId != null ? #contactId : 'null'",
      unless = "#result == null")
  public String getContactName(Long contactId) {
    String name = null;
    if (contactId != null) {
      CustomerContactEntity contact = customerContactMapper.selectById(contactId);
      if (contact != null) {
        name = contact.getName();
      }
    }
    return name;
  }

  @Override
  @Cacheable(
      value = "opportunityName",
      key = "#opportunityId != null ? #opportunityId : 'null'",
      unless = "#result == null")
  public String getOpportunityName(Long opportunityId) {
    String name = null;
    if (opportunityId != null) {
      SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(opportunityId);
      if (opportunity != null) {
        name = opportunity.getOpportunityName();
      }
    }
    return name;
  }

  @Override
  @Cacheable(
      value = "contractName",
      key = "#contractId != null ? #contractId : 'null'",
      unless = "#result == null")
  public String getContractName(Long contractId) {
    String name = null;
    if (contractId != null) {
      ContractEntity contract = contractMapper.selectById(contractId);
      if (contract != null) {
        name = contract.getContractName();
      }
    }
    return name;
  }

  // ===== 批量转换（IN 查询） =====

  @Override
  @Cacheable(
      value = "userName",
      key = "#userIds.hashCode()",
      unless = "#result == null || #result.isEmpty()")
  public Map<Long, String> getUserNames(Collection<Long> userIds) {
    Map<Long, String> result = Collections.emptyMap();
    if (userIds != null && !userIds.isEmpty()) {
      List<Long> distinctIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
      if (!distinctIds.isEmpty()) {
        result =
            userMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getRealName, (a, b) -> a));
      }
    }
    return result;
  }

  @Override
  @Cacheable(
      value = "deptName",
      key = "#deptIds.hashCode()",
      unless = "#result == null || #result.isEmpty()")
  public Map<Long, String> getDeptNames(Collection<Long> deptIds) {
    Map<Long, String> result = Collections.emptyMap();
    if (deptIds != null && !deptIds.isEmpty()) {
      List<Long> distinctIds = deptIds.stream().filter(Objects::nonNull).distinct().toList();
      if (!distinctIds.isEmpty()) {
        result =
            sysDeptMapper.selectBatchIds(distinctIds).stream()
                .collect(
                    Collectors.toMap(
                        SysDeptEntity::getId, SysDeptEntity::getDeptName, (a, b) -> a));
      }
    }
    return result;
  }

  @Override
  @Cacheable(
      value = "companyName",
      key = "#companyIds.hashCode()",
      unless = "#result == null || #result.isEmpty()")
  public Map<Long, String> getCompanyNames(Collection<Long> companyIds) {
    Map<Long, String> result = Collections.emptyMap();
    if (companyIds != null && !companyIds.isEmpty()) {
      List<Long> distinctIds = companyIds.stream().filter(Objects::nonNull).distinct().toList();
      if (!distinctIds.isEmpty()) {
        result =
            customerCompanyMapper.selectBatchIds(distinctIds).stream()
                .collect(
                    Collectors.toMap(
                        CustomerCompanyEntity::getId,
                        CustomerCompanyEntity::getCompanyName,
                        (a, b) -> a));
      }
    }
    return result;
  }

  @Override
  @Cacheable(
      value = "contactName",
      key = "#contactIds.hashCode()",
      unless = "#result == null || #result.isEmpty()")
  public Map<Long, String> getContactNames(Collection<Long> contactIds) {
    Map<Long, String> result = Collections.emptyMap();
    if (contactIds != null && !contactIds.isEmpty()) {
      List<Long> distinctIds = contactIds.stream().filter(Objects::nonNull).distinct().toList();
      if (!distinctIds.isEmpty()) {
        result =
            customerContactMapper.selectBatchIds(distinctIds).stream()
                .collect(
                    Collectors.toMap(
                        CustomerContactEntity::getId, CustomerContactEntity::getName, (a, b) -> a));
      }
    }
    return result;
  }

  @Override
  @Cacheable(
      value = "opportunityName",
      key = "#opportunityIds.hashCode()",
      unless = "#result == null || #result.isEmpty()")
  public Map<Long, String> getOpportunityNames(Collection<Long> opportunityIds) {
    Map<Long, String> result = Collections.emptyMap();
    if (opportunityIds != null && !opportunityIds.isEmpty()) {
      List<Long> distinctIds = opportunityIds.stream().filter(Objects::nonNull).distinct().toList();
      if (!distinctIds.isEmpty()) {
        result =
            salesOpportunityMapper.selectBatchIds(distinctIds).stream()
                .collect(
                    Collectors.toMap(
                        SalesOpportunityEntity::getId,
                        SalesOpportunityEntity::getOpportunityName,
                        (a, b) -> a));
      }
    }
    return result;
  }

  @Override
  @Cacheable(
      value = "contractName",
      key = "#contractIds.hashCode()",
      unless = "#result == null || #result.isEmpty()")
  public Map<Long, String> getContractNames(Collection<Long> contractIds) {
    Map<Long, String> result = Collections.emptyMap();
    if (contractIds != null && !contractIds.isEmpty()) {
      List<Long> distinctIds = contractIds.stream().filter(Objects::nonNull).distinct().toList();
      if (!distinctIds.isEmpty()) {
        result =
            contractMapper.selectBatchIds(distinctIds).stream()
                .collect(
                    Collectors.toMap(
                        ContractEntity::getId, ContractEntity::getContractName, (a, b) -> a));
      }
    }
    return result;
  }

  // ===== 通用辅助 =====

  @Override
  public <T> List<Long> collectIds(List<T> entities, Function<T, Long> idExtractor) {
    List<Long> result = Collections.emptyList();
    if (entities != null && !entities.isEmpty()) {
      result = entities.stream().map(idExtractor).filter(Objects::nonNull).distinct().toList();
    }
    return result;
  }
}
