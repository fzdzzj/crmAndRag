package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.ContractService;
import com.slz.crm.server.service.DataConvertService;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ContractServiceImpl extends ServiceImpl<ContractMapper, ContractEntity>
    implements ContractService {

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;
  @Autowired private ForeignKeyDeleteUtil foreignKeyDeleteUtil;
  @Autowired private ContractOrderItemService contractOrderItemService;
  @Autowired private DataConvertService dataConvertService;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ContractVO create(ContractDTO dto) {

    ContractEntity entity = new ContractEntity();
    BeanUtils.copyProperties(dto, entity);
    Long currentId = BaseUnit.getCurrentId();
    entity.setCreatorId(currentId);

    if (entity.getContractStatus() == null) {
      entity.setContractStatus(0);
    }

    if (entity.getContractNo() == null) {
      entity.setContractNo(UUID.randomUUID().toString());
    }

    // 根据商机ID查询公司客户id（统一从商机获取公司ID，忽略前端传入的companyId）
    if (entity.getOpportunityId() == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS, "商机ID不能为空");
    }

    SalesOpportunityEntity salesOpportunityEntity =
        salesOpportunityMapper.selectOne(
            new LambdaQueryWrapper<SalesOpportunityEntity>()
                .eq(SalesOpportunityEntity::getId, entity.getOpportunityId()));

    if (salesOpportunityEntity == null) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_NOT_EXISTS, "商机不存在，商机ID：" + entity.getOpportunityId());
    }

    // 业务规则验证：已关闭的商机（阶段为5）不能创建合同
    if (salesOpportunityEntity.getStage() == 5) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_MUST_BE_CLOSED, "已关闭的商机不能创建合同，商机ID：" + entity.getOpportunityId());
    }

    // 业务规则验证：商机已被删除则不能创建合同
    if (salesOpportunityEntity.getIsDeleted()) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_NOT_EXISTS, "商机已被删除，不能创建合同，商机ID：" + entity.getOpportunityId());
    }

    // 统一从商机中获取公司ID
    entity.setCompanyId(salesOpportunityEntity.getCompanyId());

    ContractVO result;
    if (!save(entity)) {
      result = null;
    } else {
      String companyName = dataConvertService.getCompanyName(entity.getCompanyId());
      String opportunityName = dataConvertService.getOpportunityName(entity.getOpportunityId());
      String ownerName = dataConvertService.getUserName(entity.getOwnerId());
      String creatorName = dataConvertService.getUserName(entity.getCreatorId());
      result = ContractVO.fromEntity(entity, companyName, opportunityName, ownerName, creatorName);
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ContractVO createWithOrders(ContractDTO contract, List<OrderDTO> orders) {
    if (contract == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    ContractVO created = create(contract);
    if (created == null || created.getId() == null) {
      throw new IllegalStateException("创建合同失败");
    }

    if (orders != null && !orders.isEmpty()) {
      orders.forEach(order -> order.setContractId(created.getId()));
      List<OrderVO> createdOrders = contractOrderItemService.createBatch(orders);
      if (createdOrders == null || createdOrders.size() != orders.size()) {
        throw new IllegalStateException("创建订单失败");
      }
    }

    return created;
  }

  @Override
  @Privacy
  public ContractANDOrderVO getDetailById(Long id) {
    // 参数校验
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 1. 获取合同基本信息
    ContractVO contractVO = this.getContractById(id);

    // 2. 获取关联订单信息
    List<OrderVO> ordersVO = contractOrderItemService.getOrderListByContractId(id);

    // 3. 组装返回结果
    ContractANDOrderVO vo = new ContractANDOrderVO();
    vo.setContract(contractVO);
    vo.setOrders(ordersVO);

    return vo;
  }

  @Override
  public ContractVO getContractById(Long id) {
    ContractEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.CONTRACT_NOT_EXISTS);
    }

    String companyName = dataConvertService.getCompanyName(entity.getCompanyId());
    String opportunityName = dataConvertService.getOpportunityName(entity.getOpportunityId());
    String ownerName = dataConvertService.getUserName(entity.getOwnerId());
    String creatorName = dataConvertService.getUserName(entity.getCreatorId());
    return ContractVO.fromEntity(entity, companyName, opportunityName, ownerName, creatorName);
  }

  @Override
  @Privacy
  public Page<ContractVO> contractQuery(Integer pageNum, Integer pageSize, ContractDTO dto) {
    Page<ContractEntity> page = new Page<>(pageNum, pageSize);

    LambdaQueryWrapper<ContractEntity> queryWrapper = new LambdaQueryWrapper<>();
    // 自定义查询条件
    if (dto != null) {
      if (dto.getContractName() != null && !dto.getContractName().isEmpty()) {
        queryWrapper.like(ContractEntity::getContractName, dto.getContractName());
      }
      if (dto.getContractNo() != null && !dto.getContractNo().isEmpty()) {
        queryWrapper.eq(ContractEntity::getContractNo, dto.getContractNo());
      }
      if (dto.getCompanyId() != null) {
        queryWrapper.eq(ContractEntity::getCompanyId, dto.getCompanyId());
      }
      if (dto.getContractStatus() != null) {
        queryWrapper.eq(ContractEntity::getContractStatus, dto.getContractStatus());
      }
      if (dto.getOwnerId() != null) {
        queryWrapper.eq(ContractEntity::getOwnerId, dto.getOwnerId());
      }
    }

    // 默认按创建时间倒序
    queryWrapper.orderByDesc(ContractEntity::getCreateTime);

    Page<ContractEntity> entityPage = baseMapper.selectPage(page, queryWrapper);

    // 批量收集ID
    List<ContractEntity> entityList = entityPage.getRecords();
    Set<Long> allUserIds = new HashSet<>();
    Set<Long> companyIds = new HashSet<>();
    Set<Long> opportunityIds = new HashSet<>();
    for (ContractEntity entity : entityList) {
      if (entity.getCreatorId() != null) allUserIds.add(entity.getCreatorId());
      if (entity.getOwnerId() != null) allUserIds.add(entity.getOwnerId());
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
    }
    Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);
    Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
    Map<Long, String> opportunityNameMap = dataConvertService.getOpportunityNames(opportunityIds);

    // 转换为VO
    Page<ContractVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage);

    List<ContractVO> voList =
        entityList.stream()
            .map(
                entity -> {
                  String companyName = companyNameMap.get(entity.getCompanyId());
                  String opportunityName = opportunityNameMap.get(entity.getOpportunityId());
                  String ownerName = userNameMap.get(entity.getOwnerId());
                  String creatorName = userNameMap.get(entity.getCreatorId());
                  return ContractVO.fromEntity(
                      entity, companyName, opportunityName, ownerName, creatorName);
                })
            .toList();

    voPage.setRecords(voList);
    return voPage;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "contractName", key = "#dto.id")
  public boolean update(ContractDTO dto) {
    if (dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    ContractEntity entity = getById(dto.getId());
    if (entity == null) {
      throw new BaseException(
          ErrorCode.CONTRACT_NOT_EXISTS, ErrorCode.CONTRACT_NOT_EXISTS.getMessage());
    }

    BeanUtils.copyProperties(dto, entity);
    //        entity.setUpdateTime(BaseUnit.getCurrentTime());

    return updateById(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "contractName", allEntries = true)
  public int batchDelete(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 收集不符合状态的合同ID
    List<String> invalidIds = new ArrayList<>();
    List<ContractEntity> entities = listByIds(ids);
    for (ContractEntity entity : entities) {
      if (entity.getContractStatus() != 4) {
        invalidIds.add(entity.getId().toString());
      }
    }

    // 有不符合的，抛拼接后的提示
    if (!invalidIds.isEmpty()) {
      String msg =
          ErrorCode.CONTRACT_STATUS_ERROR.getMessage()
              + "，不符合的合同ID："
              + String.join(",", invalidIds);
      throw new BaseException(ErrorCode.CONTRACT_STATUS_ERROR, msg);
    }

    foreignKeyDeleteUtil.deleteCascade(ContractEntity.class, ids, 0);
    return baseMapper.deleteBatchIds(ids);
  }

  @Override
  public Page<ContractVO> getAllContract(Integer pageNum, Integer pageSize) {

    Page<ContractEntity> page = new Page<>(pageNum, pageSize);
    Page<ContractEntity> pageResult =
        baseMapper.selectPage(
            page,
            new LambdaQueryWrapper<ContractEntity>().orderByDesc(ContractEntity::getCreateTime));

    // 批量收集ID
    List<ContractEntity> entityList = pageResult.getRecords();
    Set<Long> allUserIds = new HashSet<>();
    Set<Long> companyIds = new HashSet<>();
    Set<Long> opportunityIds = new HashSet<>();
    for (ContractEntity entity : entityList) {
      if (entity.getCreatorId() != null) allUserIds.add(entity.getCreatorId());
      if (entity.getOwnerId() != null) allUserIds.add(entity.getOwnerId());
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
    }
    Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);
    Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
    Map<Long, String> opportunityNameMap = dataConvertService.getOpportunityNames(opportunityIds);

    List<ContractVO> contractVOs =
        entityList.stream()
            .map(
                entity -> {
                  String companyName = companyNameMap.get(entity.getCompanyId());
                  String opportunityName = opportunityNameMap.get(entity.getOpportunityId());
                  String ownerName = userNameMap.get(entity.getOwnerId());
                  String creatorName = userNameMap.get(entity.getCreatorId());
                  return ContractVO.fromEntity(
                      entity, companyName, opportunityName, ownerName, creatorName);
                })
            .toList();

    Page<ContractVO> pageVO = new Page<>(pageNum, pageSize, pageResult.getTotal());
    BeanUtils.copyProperties(pageResult, pageVO);
    pageVO.setRecords(contractVOs);
    return pageVO;
  }
}
