package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.NumberGenerator;
import com.slz.crm.pojo.dto.InvoiceInfoDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.InvoiceInfoEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.InvoiceInfoMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.InvoiceInfoService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 开票信息服务实现类 */
@Service
public class InvoiceInfoServiceImpl extends ServiceImpl<InvoiceInfoMapper, InvoiceInfoEntity>
    implements InvoiceInfoService {

  @Autowired private InvoiceInfoMapper invoiceInfoMapper;

  @Autowired private ContractMapper contractMapper;

  @Autowired private PaymentRecordMapper paymentRecordMapper;

  @Autowired private UserMapper userMapper;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public InvoiceInfoVO create(InvoiceInfoDTO dto) {
    // 参数校验
    if (dto.getContractId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    // 验证合同是否存在
    ContractEntity contract = contractMapper.selectById(dto.getContractId());
    if (contract == null) {
      throw new BaseException("合同不存在");
    }

    // 如果指定了回款记录，验证回款记录是否存在
    if (dto.getPaymentId() != null) {
      PaymentRecordEntity payment = paymentRecordMapper.selectById(dto.getPaymentId());
      if (payment == null) {
        throw new BaseException("回款记录不存在");
      }
      // 验证回款记录是否属于该合同
      if (!payment.getContractId().equals(dto.getContractId())) {
        throw new BaseException("回款记录不属于该合同");
      }
    }

    InvoiceInfoEntity entity = new InvoiceInfoEntity();
    // 手动设置属性，避免潜在的类型转换问题
    entity.setId(dto.getId());
    entity.setContractId(dto.getContractId());
    entity.setPaymentId(dto.getPaymentId());
    entity.setInvoiceNo(dto.getInvoiceNo());
    entity.setInvoiceAmount(dto.getInvoiceAmount());
    entity.setInvoiceDate(dto.getInvoiceDate());
    entity.setInvoiceType(dto.getInvoiceType());
    entity.setCreatorId(BaseUnit.getCurrentId());
    entity.setRemark(dto.getRemark());

    // 设置状态：如果前端传了就用前端的，否则默认已开具
    if (dto.getStatus() != null) {
      entity.setStatus(dto.getStatus());
    } else {
      entity.setStatus(0); // 默认已开具
    }

    // 如果没有填写发票编号，自动生成（带重复检测和重试机制）
    if (!StringUtils.hasText(entity.getInvoiceNo())) {
      int maxRetries = 10; // 最大重试次数
      int retryCount = 0;
      boolean insertSuccess = false;

      while (!insertSuccess && retryCount < maxRetries) {
        entity.setInvoiceNo(NumberGenerator.generateInvoiceNo());

        try {
          int result = invoiceInfoMapper.insert(entity);
          insertSuccess = result > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
          // 发票编号重复，重试生成新的编号
          retryCount++;
          if (retryCount >= maxRetries) {
            throw new BaseException("生成发票编号失败：已达到最大重试次数（" + maxRetries + "次），请稍后重试");
          }
          // 继续下一次循环，重新生成编号
        }
      }

      return insertSuccess ? toCreatedVO(entity) : null;
    } else {
      // 前端传入了发票编号，直接插入
      return invoiceInfoMapper.insert(entity) > 0 ? toCreatedVO(entity) : null;
    }
  }

  private InvoiceInfoVO toCreatedVO(InvoiceInfoEntity entity) {
    InvoiceInfoVO created = new InvoiceInfoVO();
    created.setId(entity.getId());
    created.setContractId(entity.getContractId());
    created.setInvoiceNo(entity.getInvoiceNo());
    return created;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean update(InvoiceInfoDTO dto) {
    if (dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    InvoiceInfoEntity entity = invoiceInfoMapper.selectById(dto.getId());
    if (entity == null) {
      throw new BaseException("开票信息不存在");
    }

    // 如果修改了回款记录，验证是否属于同一合同
    if (dto.getPaymentId() != null) {
      PaymentRecordEntity payment = paymentRecordMapper.selectById(dto.getPaymentId());
      if (payment == null || !payment.getContractId().equals(entity.getContractId())) {
        throw new BaseException("回款记录不属于该合同");
      }
    }

    // 发票编号为空时保留原值（编号由后端生成或已存在，不允许清空）
    String originalInvoiceNo = entity.getInvoiceNo();
    BeanUtils.copyProperties(dto, entity);
    if (!StringUtils.hasText(dto.getInvoiceNo())) {
      entity.setInvoiceNo(originalInvoiceNo);
    }
    return invoiceInfoMapper.updateById(entity) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer deleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    // 软删除：将发票状态设置为已作废（status=1）
    int count = 0;
    for (Long id : idList) {
      InvoiceInfoEntity entity = invoiceInfoMapper.selectById(id);
      if (entity != null) {
        // 将状态设置为已作废
        entity.setStatus(1);
        count += invoiceInfoMapper.updateById(entity);
      }
    }
    return count;
  }

  @Override
  public InvoiceInfoVO getDetailById(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    InvoiceInfoEntity entity = invoiceInfoMapper.selectById(id);
    if (entity == null) {
      throw new BaseException("开票信息不存在");
    }

    return convertToVO(entity);
  }

  @Override
  public Page<InvoiceInfoVO> queryPage(Integer pageNum, Integer pageSize, InvoiceInfoDTO dto) {
    Page<InvoiceInfoEntity> page = new Page<>(pageNum, pageSize);

    LambdaQueryWrapper<InvoiceInfoEntity> queryWrapper = new LambdaQueryWrapper<>();

    // 查询条件
    if (dto.getContractId() != null) {
      queryWrapper.eq(InvoiceInfoEntity::getContractId, dto.getContractId());
    }
    if (dto.getPaymentId() != null) {
      queryWrapper.eq(InvoiceInfoEntity::getPaymentId, dto.getPaymentId());
    }
    if (dto.getStatus() != null) {
      queryWrapper.eq(InvoiceInfoEntity::getStatus, dto.getStatus());
    }
    if (dto.getInvoiceNo() != null && !dto.getInvoiceNo().isEmpty()) {
      queryWrapper.like(InvoiceInfoEntity::getInvoiceNo, dto.getInvoiceNo());
    }
    if (dto.getInvoiceType() != null && !dto.getInvoiceType().isEmpty()) {
      queryWrapper.eq(InvoiceInfoEntity::getInvoiceType, dto.getInvoiceType());
    }
    if (dto.getCreatorId() != null) {
      queryWrapper.eq(InvoiceInfoEntity::getCreatorId, dto.getCreatorId());
    }

    // 开票日期范围查询
    if (dto.getMinInvoiceDate() != null && dto.getMaxInvoiceDate() != null) {
      queryWrapper.between(
          InvoiceInfoEntity::getInvoiceDate, dto.getMinInvoiceDate(), dto.getMaxInvoiceDate());
    } else if (dto.getMinInvoiceDate() != null) {
      queryWrapper.ge(InvoiceInfoEntity::getInvoiceDate, dto.getMinInvoiceDate());
    } else if (dto.getMaxInvoiceDate() != null) {
      queryWrapper.le(InvoiceInfoEntity::getInvoiceDate, dto.getMaxInvoiceDate());
    }

    // 开票金额范围查询
    if (dto.getMinInvoiceAmount() != null && dto.getMaxInvoiceAmount() != null) {
      queryWrapper.between(
          InvoiceInfoEntity::getInvoiceAmount,
          dto.getMinInvoiceAmount(),
          dto.getMaxInvoiceAmount());
    } else if (dto.getMinInvoiceAmount() != null) {
      queryWrapper.ge(InvoiceInfoEntity::getInvoiceAmount, dto.getMinInvoiceAmount());
    } else if (dto.getMaxInvoiceAmount() != null) {
      queryWrapper.le(InvoiceInfoEntity::getInvoiceAmount, dto.getMaxInvoiceAmount());
    }

    // 创建时间范围查询
    if (dto.getMinCreateTime() != null && dto.getMaxCreateTime() != null) {
      queryWrapper.between(
          InvoiceInfoEntity::getCreateTime, dto.getMinCreateTime(), dto.getMaxCreateTime());
    } else if (dto.getMinCreateTime() != null) {
      queryWrapper.ge(InvoiceInfoEntity::getCreateTime, dto.getMinCreateTime());
    } else if (dto.getMaxCreateTime() != null) {
      queryWrapper.le(InvoiceInfoEntity::getCreateTime, dto.getMaxCreateTime());
    }

    // 按创建时间倒序
    queryWrapper.orderByDesc(InvoiceInfoEntity::getCreateTime);

    Page<InvoiceInfoEntity> entityPage = invoiceInfoMapper.selectPage(page, queryWrapper);

    // 转换为VO
    Page<InvoiceInfoVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage, "records");

    List<InvoiceInfoVO> voList =
        entityPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());
    voPage.setRecords(voList);

    return voPage;
  }

  @Override
  public List<InvoiceInfoVO> listByContractId(Long contractId) {
    if (contractId == null) {
      return new ArrayList<>();
    }

    LambdaQueryWrapper<InvoiceInfoEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(InvoiceInfoEntity::getContractId, contractId);
    queryWrapper.orderByDesc(InvoiceInfoEntity::getCreateTime);

    List<InvoiceInfoEntity> entityList = invoiceInfoMapper.selectList(queryWrapper);
    return entityList.stream().map(this::convertToVO).collect(Collectors.toList());
  }

  @Override
  public List<InvoiceInfoVO> listByPaymentId(Long paymentId) {
    if (paymentId == null) {
      return new ArrayList<>();
    }

    LambdaQueryWrapper<InvoiceInfoEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(InvoiceInfoEntity::getPaymentId, paymentId);
    queryWrapper.orderByDesc(InvoiceInfoEntity::getCreateTime);

    List<InvoiceInfoEntity> entityList = invoiceInfoMapper.selectList(queryWrapper);
    return entityList.stream().map(this::convertToVO).collect(Collectors.toList());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean voidInvoice(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }

    InvoiceInfoEntity entity = invoiceInfoMapper.selectById(id);
    if (entity == null) {
      throw new BaseException("开票信息不存在");
    }

    // 检查是否已作废（status=1）
    if (entity.getStatus() == 1) {
      throw new BaseException("发票已作废，无法重复作废");
    }

    // 更新为已作废状态（status=1）
    entity.setStatus(1);
    return invoiceInfoMapper.updateById(entity) > 0;
  }

  /** 转换Entity为VO */
  private InvoiceInfoVO convertToVO(InvoiceInfoEntity entity) {
    InvoiceInfoVO vo = new InvoiceInfoVO();
    BeanUtils.copyProperties(entity, vo);

    // 设置状态描述（0=已开具, 1=已作废）
    String statusDesc =
        switch (entity.getStatus()) {
          case 0 -> "已开具";
          case 1 -> "已作废";
          default -> "未知状态";
        };
    vo.setStatusDesc(statusDesc);

    // 查询合同信息
    if (entity.getContractId() != null) {
      ContractEntity contract = contractMapper.selectById(entity.getContractId());
      if (contract != null) {
        vo.setContractNo(contract.getContractNo());
        vo.setContractName(contract.getContractName());
      }
    }

    // 查询回款信息
    if (entity.getPaymentId() != null) {
      PaymentRecordEntity payment = paymentRecordMapper.selectById(entity.getPaymentId());
      if (payment != null) {
        vo.setPaymentNo(payment.getPaymentNo());
        vo.setPaymentAmount(payment.getPaymentAmount());
      }
    }

    // 查询创建人信息
    if (entity.getCreatorId() != null) {
      UserEntity creator = userMapper.selectById(entity.getCreatorId());
      if (creator != null) {
        vo.setCreatorName(creator.getRealName());
      }
    }

    return vo;
  }
}
