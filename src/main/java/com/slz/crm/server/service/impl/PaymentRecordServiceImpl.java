package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.NumberGenerator;
import com.slz.crm.pojo.dto.PaymentRecordDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.PaymentRecordService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 回款记录服务实现类 */
@Service
public class PaymentRecordServiceImpl extends ServiceImpl<PaymentRecordMapper, PaymentRecordEntity>
    implements PaymentRecordService {

  @Autowired private PaymentRecordMapper paymentRecordMapper;

  @Autowired private ContractMapper contractMapper;

  @Autowired private ContractOrderItemMapper contractOrderItemMapper;

  @Autowired private DataConvertService dataConvertService;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PaymentRecordVO create(PaymentRecordDTO dto) {
    // 参数校验
    ContractEntity contract = validatePaymentCreate(dto);

    PaymentRecordEntity entity = buildPaymentEntity(dto);

    // 如果没有填写回款单号，自动生成（带重复检测和重试机制）
    PaymentRecordVO generated;
    if (!StringUtils.hasText(entity.getPaymentNo())) {
      generated = insertWithGeneratedPaymentNo(entity, contract);
    } else {
      // 前端传入了回款单号，直接插入
      generated = paymentRecordMapper.insert(entity) > 0 ? toCreatedVO(entity, contract) : null;
    }
    return generated;
  }

  /** 创建回款的关联校验：合同必须存在；指定订单明细时明细必须存在且属于该合同。返回已加载的合同。 */
  private ContractEntity validatePaymentCreate(PaymentRecordDTO dto) {
    if (dto.getContractId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    // 验证合同是否存在
    ContractEntity contract = contractMapper.selectById(dto.getContractId());
    if (contract == null) {
      throw new BaseException("合同不存在");
    }

    // 如果指定了订单明细，验证订单明细是否存在
    if (dto.getOrderItemId() != null) {
      ContractOrderItemEntity orderItem = contractOrderItemMapper.selectById(dto.getOrderItemId());
      if (orderItem == null) {
        throw new BaseException("订单明细不存在");
      }
      // 验证订单明细是否属于该合同
      if (!orderItem.getContractId().equals(dto.getContractId())) {
        throw new BaseException("订单明细不属于该合同");
      }
    }
    return contract;
  }

  /** 由 DTO 手动装配回款实体（状态缺省为已确认 0） */
  private PaymentRecordEntity buildPaymentEntity(PaymentRecordDTO dto) {
    PaymentRecordEntity entity = new PaymentRecordEntity();
    // 手动设置属性，避免 BeanUtils 类型转换问题
    entity.setId(dto.getId());
    entity.setContractId(dto.getContractId());
    entity.setOrderItemId(dto.getOrderItemId());
    entity.setPaymentAmount(dto.getPaymentAmount());
    entity.setPaymentDate(dto.getPaymentDate());
    entity.setPaymentMethod(dto.getPaymentMethod());
    entity.setRemark(dto.getRemark());
    entity.setCreatorId(BaseUnit.getCurrentId());

    // 设置状态：如果前端传了就用前端的，否则默认已确认
    if (dto.getPaymentStatus() != null) {
      entity.setPaymentStatus(dto.getPaymentStatus());
    } else {
      entity.setPaymentStatus(0); // 默认已确认
    }
    return entity;
  }

  /** 自动生成回款单号并插入，单号冲突时重试生成，超过最大重试次数报错 */
  private PaymentRecordVO insertWithGeneratedPaymentNo(
      PaymentRecordEntity entity, ContractEntity contract) {
    PaymentRecordVO result;
    int maxRetries = 10; // 最大重试次数
    int retryCount = 0;
    boolean insertSuccess = false;

    while (!insertSuccess && retryCount < maxRetries) {
      entity.setPaymentNo(NumberGenerator.generatePaymentNo());

      try {
        int insertResult = paymentRecordMapper.insert(entity);
        insertSuccess = insertResult > 0;
      } catch (org.springframework.dao.DuplicateKeyException e) {
        // 回款单号重复，重试生成新的单号
        retryCount++;
        if (retryCount >= maxRetries) {
          throw new BaseException("生成回款单号失败：已达到最大重试次数（" + maxRetries + "次），请稍后重试");
        }
        // 继续下一次循环，重新生成单号
      }
    }

    result = insertSuccess ? toCreatedVO(entity, contract) : null;
    return result;
  }

  private PaymentRecordVO toCreatedVO(PaymentRecordEntity entity, ContractEntity contract) {
    PaymentRecordVO created = new PaymentRecordVO();
    created.setId(entity.getId());
    created.setContractId(entity.getContractId());
    created.setContractName(contract != null ? contract.getContractName() : null);
    created.setPaymentNo(entity.getPaymentNo());
    created.setPaymentAmount(entity.getPaymentAmount());
    return created;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean update(PaymentRecordDTO dto) {
    if (dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    PaymentRecordEntity entity = paymentRecordMapper.selectById(dto.getId());
    if (entity == null) {
      throw new BaseException("回款记录不存在");
    }

    // 如果修改了订单明细，验证是否属于同一合同
    if (dto.getOrderItemId() != null) {
      ContractOrderItemEntity orderItem = contractOrderItemMapper.selectById(dto.getOrderItemId());
      if (orderItem == null || !orderItem.getContractId().equals(entity.getContractId())) {
        throw new BaseException("订单明细不属于该合同");
      }
    }

    BeanUtils.copyProperties(dto, entity);
    return paymentRecordMapper.updateById(entity) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer deleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    // 软删除：将回款状态设置为已作废（status=2）
    int count = 0;
    for (Long id : idList) {
      PaymentRecordEntity entity = paymentRecordMapper.selectById(id);
      if (entity != null) {
        // 将状态设置为已作废
        entity.setPaymentStatus(2);
        count += paymentRecordMapper.updateById(entity);
      }
    }
    return count;
  }

  @Override
  public PaymentRecordVO getDetailById(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    PaymentRecordEntity entity = paymentRecordMapper.selectById(id);
    if (entity == null) {
      throw new BaseException("回款记录不存在");
    }

    return PaymentVoSupport.convertToVO(
        entity, contractMapper, contractOrderItemMapper, dataConvertService);
  }

  @Override
  public Page<PaymentRecordVO> queryPage(Integer pageNum, Integer pageSize, PaymentRecordDTO dto) {
    Page<PaymentRecordEntity> page = new Page<>(pageNum, pageSize);

    LambdaQueryWrapper<PaymentRecordEntity> queryWrapper = new LambdaQueryWrapper<>();

    // ���询条件
    if (dto != null) {
      PaymentVoSupport.applyPaymentFilters(queryWrapper, dto);
    }

    // 按创建时间倒序
    queryWrapper.orderByDesc(PaymentRecordEntity::getCreateTime);

    Page<PaymentRecordEntity> entityPage = paymentRecordMapper.selectPage(page, queryWrapper);

    // 转换为VO
    Page<PaymentRecordVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage, "records");

    List<PaymentRecordVO> voList =
        entityPage.getRecords().stream()
            .map(
                entity ->
                    PaymentVoSupport.convertToVO(
                        entity, contractMapper, contractOrderItemMapper, dataConvertService))
            .collect(Collectors.toList());
    voPage.setRecords(voList);

    return voPage;
  }

  @Override
  public List<PaymentRecordVO> listByContractId(Long contractId) {
    List<PaymentRecordVO> result;
    if (contractId == null) {
      result = new ArrayList<>();
    } else {

      LambdaQueryWrapper<PaymentRecordEntity> queryWrapper = new LambdaQueryWrapper<>();
      queryWrapper.eq(PaymentRecordEntity::getContractId, contractId);
      queryWrapper.orderByDesc(PaymentRecordEntity::getCreateTime);

      List<PaymentRecordEntity> entityList = paymentRecordMapper.selectList(queryWrapper);
      result =
          entityList.stream()
              .map(
                  entity ->
                      PaymentVoSupport.convertToVO(
                          entity, contractMapper, contractOrderItemMapper, dataConvertService))
              .collect(Collectors.toList());
    }
    return result;
  }

  @Override
  public List<PaymentRecordVO> listByOrderItemId(Long orderItemId) {
    List<PaymentRecordVO> result;
    if (orderItemId == null) {
      result = new ArrayList<>();
    } else {

      LambdaQueryWrapper<PaymentRecordEntity> queryWrapper = new LambdaQueryWrapper<>();
      queryWrapper.eq(PaymentRecordEntity::getOrderItemId, orderItemId);
      queryWrapper.orderByDesc(PaymentRecordEntity::getCreateTime);

      List<PaymentRecordEntity> entityList = paymentRecordMapper.selectList(queryWrapper);
      result =
          entityList.stream()
              .map(
                  entity ->
                      PaymentVoSupport.convertToVO(
                          entity, contractMapper, contractOrderItemMapper, dataConvertService))
              .collect(Collectors.toList());
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean confirmPayment(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    PaymentRecordEntity entity = paymentRecordMapper.selectById(id);
    if (entity == null) {
      throw new BaseException("回款记录不存在");
    }

    // 更新为已确认状态
    entity.setPaymentStatus(0);
    return paymentRecordMapper.updateById(entity) > 0;
  }
}
