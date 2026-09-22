package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.dto.PaymentRecordDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.service.DataConvertService;
import org.springframework.beans.BeanUtils;

/** 回款 VO 转换与查询条件支持类（tighten-pmd-residual-325 任务 6.3 自 PaymentRecordServiceImpl 拆出，行为等价）。 */
final class PaymentVoSupport {
  private PaymentVoSupport() {}

  /** 将字符串状态转换为数字 */
  static Integer convertStatusStringToInteger(String statusStr) {
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

  /**
   * 组装回款分页各字段查询条件（拆自 queryPage，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param dto 查询条件
   */
  static void applyPaymentFilters(
      LambdaQueryWrapper<PaymentRecordEntity> queryWrapper, PaymentRecordDTO dto) {
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
    applyPaymentStatusFilter(queryWrapper, dto);
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

  /**
   * 状态过滤：字符串状态优先转换，否则按数值状态过滤（拆自 queryPage，行为等价）。
   *
   * @param queryWrapper 查询构造器
   * @param dto 查询条件
   */
  private static void applyPaymentStatusFilter(
      LambdaQueryWrapper<PaymentRecordEntity> queryWrapper, PaymentRecordDTO dto) {
    if (dto.getPaymentStatusStr() != null && !dto.getPaymentStatusStr().trim().isEmpty()) {
      Integer status = convertStatusStringToInteger(dto.getPaymentStatusStr());
      if (status != null) {
        queryWrapper.eq(PaymentRecordEntity::getPaymentStatus, status);
      }
    } else if (dto.getPaymentStatus() != null) {
      queryWrapper.eq(PaymentRecordEntity::getPaymentStatus, dto.getPaymentStatus());
    }
  }

  /** 转换Entity为VO */
  static PaymentRecordVO convertToVO(
      PaymentRecordEntity entity,
      ContractMapper contractMapper,
      ContractOrderItemMapper contractOrderItemMapper,
      DataConvertService dataConvertService) {
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
