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

  /** 将字符串状态转换为数字 */
  private Integer convertStatusStringToInteger(String statusStr) {
    Integer result;
    if (statusStr == null || statusStr.trim().isEmpty()) {
      result = null;
    } else {
      String trimmed = statusStr.trim();
      result =
          switch (trimmed) {
            case "已确认" -> 0;
            case "待确认" -> 1;
            case "已作废" -> 2;
            default -> null; // 无效值返回 null
          };
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PaymentRecordVO create(PaymentRecordDTO dto) {
    // 参数校验
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

    // 如果没有填写回款单号，自动生成（带重复检测和重试机制）
    PaymentRecordVO generated;
    if (!StringUtils.hasText(entity.getPaymentNo())) {
      int maxRetries = 10; // 最大重试次数
      int retryCount = 0;
      boolean insertSuccess = false;

      while (!insertSuccess && retryCount < maxRetries) {
        entity.setPaymentNo(NumberGenerator.generatePaymentNo());

        try {
          int result = paymentRecordMapper.insert(entity);
          insertSuccess = result > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
          // 回款单号重复，重试生成新的单号
          retryCount++;
          if (retryCount >= maxRetries) {
            throw new BaseException("生成回款单号失败：已达到最大重试次数（" + maxRetries + "次），请稍后重试");
          }
          // 继续下一次循环，重新生成单号
        }
      }

      generated = insertSuccess ? toCreatedVO(entity, contract) : null;
    } else {
      // 前端传入了回款单号，直接插入
      generated = paymentRecordMapper.insert(entity) > 0 ? toCreatedVO(entity, contract) : null;
    }
    return generated;
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

    return convertToVO(entity);
  }

  @Override
  public Page<PaymentRecordVO> queryPage(Integer pageNum, Integer pageSize, PaymentRecordDTO dto) {
    Page<PaymentRecordEntity> page = new Page<>(pageNum, pageSize);

    LambdaQueryWrapper<PaymentRecordEntity> queryWrapper = new LambdaQueryWrapper<>();

    // ���询条件
    if (dto != null) {
      // 按合同ID查询
      if (dto.getContractId() != null) {
        queryWrapper.eq(PaymentRecordEntity::getContractId, dto.getContractId());
      }
      // 按订单明细ID查询
      if (dto.getOrderItemId() != null) {
        queryWrapper.eq(PaymentRecordEntity::getOrderItemId, dto.getOrderItemId());
      }
      // 按回款方式查询
      if (dto.getPaymentMethod() != null && !dto.getPaymentMethod().trim().isEmpty()) {
        queryWrapper.eq(PaymentRecordEntity::getPaymentMethod, dto.getPaymentMethod().trim());
      }
      // 处理状态：如果传入了字符串状态，优先转换字符串
      if (dto.getPaymentStatusStr() != null && !dto.getPaymentStatusStr().trim().isEmpty()) {
        Integer status = convertStatusStringToInteger(dto.getPaymentStatusStr());
        if (status != null) {
          queryWrapper.eq(PaymentRecordEntity::getPaymentStatus, status);
        }
      } else if (dto.getPaymentStatus() != null) {
        queryWrapper.eq(PaymentRecordEntity::getPaymentStatus, dto.getPaymentStatus());
      }
      // 按回款单号模糊查询
      if (dto.getPaymentNo() != null && !dto.getPaymentNo().trim().isEmpty()) {
        queryWrapper.like(PaymentRecordEntity::getPaymentNo, dto.getPaymentNo().trim());
      }
      // 按回款日期范围查询
      if (dto.getPaymentDateStart() != null) {
        queryWrapper.ge(PaymentRecordEntity::getPaymentDate, dto.getPaymentDateStart());
      }
      if (dto.getPaymentDateEnd() != null) {
        queryWrapper.le(PaymentRecordEntity::getPaymentDate, dto.getPaymentDateEnd());
      }
      // 按创建人查询
      if (dto.getCreatorId() != null) {
        queryWrapper.eq(PaymentRecordEntity::getCreatorId, dto.getCreatorId());
      }
      // 按备注模糊查询
      if (dto.getRemark() != null && !dto.getRemark().trim().isEmpty()) {
        queryWrapper.like(PaymentRecordEntity::getRemark, dto.getRemark().trim());
      }
    }

    // 按创建时间倒序
    queryWrapper.orderByDesc(PaymentRecordEntity::getCreateTime);

    Page<PaymentRecordEntity> entityPage = paymentRecordMapper.selectPage(page, queryWrapper);

    // 转换为VO
    Page<PaymentRecordVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage, "records");

    List<PaymentRecordVO> voList =
        entityPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());
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
      result = entityList.stream().map(this::convertToVO).collect(Collectors.toList());
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
      result = entityList.stream().map(this::convertToVO).collect(Collectors.toList());
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

  /** 转换Entity为VO */
  private PaymentRecordVO convertToVO(PaymentRecordEntity entity) {
    PaymentRecordVO vo = new PaymentRecordVO();
    BeanUtils.copyProperties(entity, vo);

    // 设置回款状态描述（0=已确认, 1=待确认, 2=已作废）
    String statusDesc =
        switch (entity.getPaymentStatus()) {
          case 0 -> "已确认";
          case 1 -> "待确认";
          case 2 -> "已作废";
          default -> "未知状态";
        };
    vo.setPaymentStatusDesc(statusDesc);

    // 查询合同信息
    if (entity.getContractId() != null) {
      ContractEntity contract = contractMapper.selectById(entity.getContractId());
      if (contract != null) {
        vo.setContractNo(contract.getContractNo());
        vo.setContractName(contract.getContractName());
      }
    }

    // 查询订单明细信息
    if (entity.getOrderItemId() != null) {
      ContractOrderItemEntity orderItem =
          contractOrderItemMapper.selectById(entity.getOrderItemId());
      if (orderItem != null) {
        vo.setProductName(orderItem.getProductName());
      }
    }

    // 查询创建人信息
    if (entity.getCreatorId() != null) {
      vo.setCreatorName(dataConvertService.getUserName(entity.getCreatorId()));
    }

    return vo;
  }
}
