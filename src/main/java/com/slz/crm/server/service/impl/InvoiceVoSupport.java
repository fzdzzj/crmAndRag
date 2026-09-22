package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.dto.InvoiceInfoDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.InvoiceInfoEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.UserMapper;
import org.springframework.beans.BeanUtils;

/** 发票 VO 转换与查询范围条件支持类（tighten-pmd-residual-325 任务 6.3 自 InvoiceInfoServiceImpl 拆出，行为等价）。 */
final class InvoiceVoSupport {
  private InvoiceVoSupport() {}

  /** 转换Entity为VO */
  static InvoiceInfoVO convertToVO(
      InvoiceInfoEntity entity,
      ContractMapper contractMapper,
      PaymentRecordMapper paymentRecordMapper,
      UserMapper userMapper) {
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

  /** 开票日期范围查询：双界 between，仅单界时 ge/le */
  static void applyInvoiceDateRange(
      LambdaQueryWrapper<InvoiceInfoEntity> queryWrapper, InvoiceInfoDTO dto) {
    if (dto.getMinInvoiceDate() != null && dto.getMaxInvoiceDate() != null) {
      queryWrapper.between(
          InvoiceInfoEntity::getInvoiceDate, dto.getMinInvoiceDate(), dto.getMaxInvoiceDate());
    } else if (dto.getMinInvoiceDate() != null) {
      queryWrapper.ge(InvoiceInfoEntity::getInvoiceDate, dto.getMinInvoiceDate());
    } else if (dto.getMaxInvoiceDate() != null) {
      queryWrapper.le(InvoiceInfoEntity::getInvoiceDate, dto.getMaxInvoiceDate());
    }
  }

  /** 开票金额范围查询：双界 between，仅单界时 ge/le */
  static void applyInvoiceAmountRange(
      LambdaQueryWrapper<InvoiceInfoEntity> queryWrapper, InvoiceInfoDTO dto) {
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
  }

  /** 创建时间范围查询：双界 between，仅单界时 ge/le */
  static void applyCreateTimeRange(
      LambdaQueryWrapper<InvoiceInfoEntity> queryWrapper, InvoiceInfoDTO dto) {
    if (dto.getMinCreateTime() != null && dto.getMaxCreateTime() != null) {
      queryWrapper.between(
          InvoiceInfoEntity::getCreateTime, dto.getMinCreateTime(), dto.getMaxCreateTime());
    } else if (dto.getMinCreateTime() != null) {
      queryWrapper.ge(InvoiceInfoEntity::getCreateTime, dto.getMinCreateTime());
    } else if (dto.getMaxCreateTime() != null) {
      queryWrapper.le(InvoiceInfoEntity::getCreateTime, dto.getMaxCreateTime());
    }
  }
}
