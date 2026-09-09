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
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一数据转换服务实现
 * 提供ID转名称的通用方法，支持批量转换以优化性能
 */
@Service
@Slf4j
public class DataConvertServiceImpl implements DataConvertService {

    private final UserMapper userMapper;
    private final CustomerCompanyMapper customerCompanyMapper;
    private final CustomerContactMapper customerContactMapper;
    private final SalesOpportunityMapper salesOpportunityMapper;
    private final ContractMapper contractMapper;
    private final SysDeptMapper sysDeptMapper;

    public DataConvertServiceImpl(UserMapper userMapper,
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
    @Cacheable(value = "userName", key = "#userId != null ? #userId : 'null'", unless = "#result == null")
    public String getUserName(Long userId) {
        if (userId == null) return null;
        UserEntity user = userMapper.selectById(userId);
        return user != null ? user.getRealName() : null;
    }

    @Override
    @Cacheable(value = "deptName", key = "#deptId != null ? #deptId : 'null'", unless = "#result == null")
    public String getDeptName(Long deptId) {
        if (deptId == null) return null;
        SysDeptEntity dept = sysDeptMapper.selectById(deptId);
        return dept != null ? dept.getDeptName() : null;
    }

    @Override
    @Cacheable(value = "companyName", key = "#companyId != null ? #companyId : 'null'", unless = "#result == null")
    public String getCompanyName(Long companyId) {
        if (companyId == null) return null;
        CustomerCompanyEntity company = customerCompanyMapper.selectById(companyId);
        return company != null ? company.getCompanyName() : null;
    }

    @Override
    @Cacheable(value = "contactName", key = "#contactId != null ? #contactId : 'null'", unless = "#result == null")
    public String getContactName(Long contactId) {
        if (contactId == null) return null;
        CustomerContactEntity contact = customerContactMapper.selectById(contactId);
        return contact != null ? contact.getName() : null;
    }

    @Override
    @Cacheable(value = "opportunityName", key = "#opportunityId != null ? #opportunityId : 'null'", unless = "#result == null")
    public String getOpportunityName(Long opportunityId) {
        if (opportunityId == null) return null;
        SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(opportunityId);
        return opportunity != null ? opportunity.getOpportunityName() : null;
    }

    @Override
    @Cacheable(value = "contractName", key = "#contractId != null ? #contractId : 'null'", unless = "#result == null")
    public String getContractName(Long contractId) {
        if (contractId == null) return null;
        ContractEntity contract = contractMapper.selectById(contractId);
        return contract != null ? contract.getContractName() : null;
    }

    // ===== 批量转换（IN 查询） =====

    @Override
    @Cacheable(value = "userName", key = "#userIds.hashCode()", unless = "#result == null || #result.isEmpty()")
    public Map<Long, String> getUserNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        List<Long> distinctIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Collections.emptyMap();
        return userMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getRealName, (a, b) -> a));
    }

    @Override
    @Cacheable(value = "deptName", key = "#deptIds.hashCode()", unless = "#result == null || #result.isEmpty()")
    public Map<Long, String> getDeptNames(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) return Collections.emptyMap();
        List<Long> distinctIds = deptIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Collections.emptyMap();
        return sysDeptMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(SysDeptEntity::getId, SysDeptEntity::getDeptName, (a, b) -> a));
    }

    @Override
    @Cacheable(value = "companyName", key = "#companyIds.hashCode()", unless = "#result == null || #result.isEmpty()")
    public Map<Long, String> getCompanyNames(Collection<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) return Collections.emptyMap();
        List<Long> distinctIds = companyIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Collections.emptyMap();
        return customerCompanyMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(CustomerCompanyEntity::getId,
                        CustomerCompanyEntity::getCompanyName, (a, b) -> a));
    }

    @Override
    @Cacheable(value = "contactName", key = "#contactIds.hashCode()", unless = "#result == null || #result.isEmpty()")
    public Map<Long, String> getContactNames(Collection<Long> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) return Collections.emptyMap();
        List<Long> distinctIds = contactIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Collections.emptyMap();
        return customerContactMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(CustomerContactEntity::getId,
                        CustomerContactEntity::getName, (a, b) -> a));
    }

    @Override
    @Cacheable(value = "opportunityName", key = "#opportunityIds.hashCode()", unless = "#result == null || #result.isEmpty()")
    public Map<Long, String> getOpportunityNames(Collection<Long> opportunityIds) {
        if (opportunityIds == null || opportunityIds.isEmpty()) return Collections.emptyMap();
        List<Long> distinctIds = opportunityIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Collections.emptyMap();
        return salesOpportunityMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(SalesOpportunityEntity::getId,
                        SalesOpportunityEntity::getOpportunityName, (a, b) -> a));
    }

    @Override
    @Cacheable(value = "contractName", key = "#contractIds.hashCode()", unless = "#result == null || #result.isEmpty()")
    public Map<Long, String> getContractNames(Collection<Long> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) return Collections.emptyMap();
        List<Long> distinctIds = contractIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Collections.emptyMap();
        return contractMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(ContractEntity::getId,
                        ContractEntity::getContractName, (a, b) -> a));
    }

    // ===== 通用辅助 =====

    @Override
    public <T> List<Long> collectIds(List<T> entities, Function<T, Long> idExtractor) {
        if (entities == null || entities.isEmpty()) return Collections.emptyList();
        return entities.stream()
                .map(idExtractor)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
