package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.service.ContractOrderItemService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractOrderItemServiceImpl
    extends ServiceImpl<ContractOrderItemMapper, ContractOrderItemEntity>
    implements ContractOrderItemService {

  @Autowired private ContractOrderItemMapper contractOrderItemMapper;

  @Autowired private ForeignKeyDeleteUtil foreignKeyDeleteUtil;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<OrderVO> createBatch(List<OrderDTO> orders) {
    if (orders == null || orders.isEmpty()) {
      return null;
    }

    List<ContractOrderItemEntity> list = new ArrayList<>();

    orders.forEach(
        order -> {
          ContractOrderItemEntity contractOrderItemEntity = new ContractOrderItemEntity();

          // 转为Entity
          BeanUtils.copyProperties(order, contractOrderItemEntity);
          // 计算金额
          contractOrderItemEntity.setAmount(order.getQuantity().multiply(order.getUnitPrice()));
          list.add(contractOrderItemEntity);
        });

    if (baseMapper.insertBatch(list) <= 0) {
      return List.of();
    }

    return list.stream().filter(entity -> entity.getId() != null).map(OrderVO::fromEntity).toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int updateOrders(List<OrderDTO> orders) {
    // 检查合同是否存在
    if (orders == null || orders.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 添加新订单
    List<ContractOrderItemEntity> items = new ArrayList<>();
    for (OrderDTO order : orders) {
      ContractOrderItemEntity item = new ContractOrderItemEntity();
      BeanUtils.copyProperties(order, item);
      items.add(item);
    }
    updateBatchById(items);

    return orders.size();
  }

  // 新增方法实现
  @Transactional(rollbackFor = Exception.class)
  @Override
  public int batchDelete(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    foreignKeyDeleteUtil.deleteCascade(ContractOrderItemEntity.class, ids, 0);
    // 执行批量删除
    return contractOrderItemMapper.deleteBatchIds(ids);
  }

  @Override
  @Privacy
  public Page<OrderVO> orderQuery(Integer pageNum, Integer pageSize, OrderDTO dto) {
    // 参数校验
    if (pageNum == null || pageNum <= 0) {
      pageNum = 1;
    }
    if (pageSize == null || pageSize <= 0) {
      pageSize = 10;
    }

    Page<ContractOrderItemEntity> page = new Page<>(pageNum, pageSize);

    // 构建查询条件
    LambdaQueryWrapper<ContractOrderItemEntity> queryWrapper = buildQueryWrapper(dto);

    // 执行分页查询
    Page<ContractOrderItemEntity> entityPage = page(page, queryWrapper);

    // 转换为VO
    return convertToVOPage(entityPage);
  }

  @Override
  @Privacy
  public OrderVO getDetailById(Long id) {
    // 参数校验
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    ContractOrderItemEntity contractOrderItemEntity = getById(id);
    if (contractOrderItemEntity == null) {
      throw new BaseException(ErrorCode.ORDER_NOT_EXISTS);
    }

    return OrderVO.fromEntity(contractOrderItemEntity);
  }

  @Override
  public List<OrderVO> getOrderListByContractId(Long contractId) {
    // 使用自定义 Mapper 方法查询订单列表
    List<ContractOrderItemEntity> entities = baseMapper.selectByContractId(contractId);

    // 转换为VO列表
    return entities.stream().map(OrderVO::fromEntity).toList();
  }

  @Override
  @Privacy
  public Page<OrderVO> getAllOrders(Integer pageNum, Integer pageSize) {
    // 参数校验
    if (pageNum == null || pageNum <= 0) {
      pageNum = 1;
    }
    if (pageSize == null || pageSize <= 0) {
      pageSize = 10;
    }

    Page<ContractOrderItemEntity> page = new Page<>(pageNum, pageSize);

    // 构建查询条件（无条件查询所有）
    LambdaQueryWrapper<ContractOrderItemEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.orderByDesc(ContractOrderItemEntity::getId);

    // 执行分页查询
    Page<ContractOrderItemEntity> entityPage = page(page, queryWrapper);

    // 转换为VO
    return convertToVOPage(entityPage);
  }

  // 辅助方法：构建查询条件
  private LambdaQueryWrapper<ContractOrderItemEntity> buildQueryWrapper(OrderDTO dto) {
    LambdaQueryWrapper<ContractOrderItemEntity> queryWrapper = new LambdaQueryWrapper<>();

    if (dto != null) {
      if (dto.getContractId() != null) {
        queryWrapper.eq(ContractOrderItemEntity::getContractId, dto.getContractId());
      }
      if (dto.getProductName() != null && !dto.getProductName().isEmpty()) {
        queryWrapper.like(ContractOrderItemEntity::getProductName, dto.getProductName());
      }
      if (dto.getId() != null) {
        queryWrapper.eq(ContractOrderItemEntity::getId, dto.getId());
      }
    }

    // 默认按ID倒序
    queryWrapper.orderByDesc(ContractOrderItemEntity::getId);

    return queryWrapper;
  }

  // 辅助方法：转换实体分页结果为VO分页结果
  private Page<OrderVO> convertToVOPage(Page<ContractOrderItemEntity> entityPage) {
    Page<OrderVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage);

    List<OrderVO> voList = new ArrayList<>();
    for (ContractOrderItemEntity entity : entityPage.getRecords()) {
      OrderVO vo = new OrderVO();
      BeanUtils.copyProperties(entity, vo);
      voList.add(vo);
    }

    voPage.setRecords(voList);
    return voPage;
  }
}
