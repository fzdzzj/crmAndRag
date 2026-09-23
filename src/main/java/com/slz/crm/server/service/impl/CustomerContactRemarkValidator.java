package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.RemarkType;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.CustomerContactRemarkDTO;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.server.mapper.CustomerContactRemarkMapper;
import java.util.List;
import org.springframework.beans.BeanUtils;

/** 客户联系人备注校验与维护支持类：备注类型/关系等级校验与备注增删维护，纯静态、无状态。 */
final class CustomerContactRemarkValidator {

  private CustomerContactRemarkValidator() {}

  /** 校验并整体替换联系人备注：先删除原有备注，再逐条校验插入新备注（remarks 为空即清空备注） */
  static void replaceRemarks(
      Long contactId,
      List<CustomerContactRemarkDTO> remarks,
      Long creatorId,
      CustomerContactRemarkMapper mapper) {
    // 删除该联系人原有的所有备注
    LambdaQueryWrapper<CustomerContactRemarkEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(CustomerContactRemarkEntity::getContactId, contactId);
    mapper.delete(queryWrapper);

    // 新增备注
    if (remarks != null && !remarks.isEmpty()) {
      for (CustomerContactRemarkDTO remark : remarks) {
        // 验证备注类型
        validateRemarkType(remark);

        CustomerContactRemarkEntity remarkEntity = new CustomerContactRemarkEntity();
        BeanUtils.copyProperties(remark, remarkEntity);
        remarkEntity.setContactId(contactId);
        remarkEntity.setCreatorId(creatorId);
        mapper.insert(remarkEntity);
      }
    }
  }

  /** 校验并插入联系人备注（不删除已有备注，remarks 为空时无操作） */
  static void insertRemarks(
      Long contactId,
      List<CustomerContactRemarkDTO> remarks,
      Long creatorId,
      CustomerContactRemarkMapper mapper) {
    if (remarks != null && !remarks.isEmpty()) {
      for (CustomerContactRemarkDTO remark : remarks) {
        // 验证备注类型
        validateRemarkType(remark);

        CustomerContactRemarkEntity remarkEntity = new CustomerContactRemarkEntity();
        BeanUtils.copyProperties(remark, remarkEntity);
        remarkEntity.setContactId(contactId);
        remarkEntity.setCreatorId(creatorId);
        mapper.insert(remarkEntity);
      }
    }
  }

  /**
   * 验证备注类型是否合法
   *
   * @param remark 备注 DTO
   */
  static void validateRemarkType(CustomerContactRemarkDTO remark) {
    if (remark.getRemarkType() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "备注类型不能为空");
    }

    RemarkType remarkType = RemarkType.getByCode(remark.getRemarkType());
    if (remarkType == null) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, "备注类型无效，只支持：1-喜好、2-住址、3-本人出生日期、4-亲属出生日期、5-自定义");
    }

    int remarkTypeCode = remark.getRemarkType();
    validateRequiredFieldsByRemarkType(remark, remarkType, remarkTypeCode);
    validateForbiddenFieldsByRemarkType(remark, remarkType, remarkTypeCode);
  }

  /** 按备注类型校验必填字段：喜好/住址/自定义需内容，本人出生日期需出生日期，亲属出生日期需姓名 */
  private static void validateRequiredFieldsByRemarkType(
      CustomerContactRemarkDTO remark, RemarkType remarkType, int remarkTypeCode) {
    // 根据备注类型验证必填字段
    // 喜好、住址、自定义类型需要填写备注内容
    if (RemarkType.needContent(remarkTypeCode)
        && (remark.getRemarkContent() == null || remark.getRemarkContent().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型需要填写备注内容");
    }

    // 本人出生日期、亲属出生日期类型需要填写出生日期
    if (RemarkType.needBirthday(remarkTypeCode) && remark.getRemarkDate() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型需要填写出生日期");
    }

    // 亲属出生日期类型需要填写姓名
    if (RemarkType.needName(remarkTypeCode)
        && (remark.getRemarkName() == null || remark.getRemarkName().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型需要填写姓名");
    }
  }

  /** 按备注类型校验禁止填写的字段，防止同一备注混入不属于该类型的字段 */
  private static void validateForbiddenFieldsByRemarkType(
      CustomerContactRemarkDTO remark, RemarkType remarkType, int remarkTypeCode) {
    // 喜好、住址、自定义类型只能填备注内容，不能填姓名和出生日期
    if (RemarkType.onlyNeedContent(remarkTypeCode)) {
      forbidNameAndBirthday(remark, remarkType);
    }

    // 本人出生日期类型只填写出生日期，不能填备注内容和姓名
    if (RemarkType.isSelfBirthday(remarkTypeCode)) {
      forbidContentAndName(remark, remarkType);
    }

    // 亲属出生日期类型需要填写姓名和出生日期，不能填备注内容
    if (RemarkType.isRelativeBirthday(remarkTypeCode)) {
      forbidContent(remark, remarkType);
    }
  }

  /** 禁止填写姓名和出生日期（拆自 validateForbiddenFieldsByRemarkType，行为等价） */
  private static void forbidNameAndBirthday(
      CustomerContactRemarkDTO remark, RemarkType remarkType) {
    if (remark.getRemarkName() != null && !remark.getRemarkName().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写姓名");
    }
    if (remark.getRemarkDate() != null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写出生日期");
    }
  }

  /** 禁止填写备注内容和姓名（拆自 validateForbiddenFieldsByRemarkType，行为等价） */
  private static void forbidContentAndName(CustomerContactRemarkDTO remark, RemarkType remarkType) {
    if (remark.getRemarkContent() != null && !remark.getRemarkContent().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写备注内容");
    }
    if (remark.getRemarkName() != null && !remark.getRemarkName().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写姓名");
    }
  }

  /** 禁止填写备注内容（拆自 validateForbiddenFieldsByRemarkType，行为等价） */
  private static void forbidContent(CustomerContactRemarkDTO remark, RemarkType remarkType) {
    if (remark.getRemarkContent() != null && !remark.getRemarkContent().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写备注内容");
    }
  }

  /**
   * 验证关系等级是否合法（1-9）
   *
   * @param relationLevel 关系等级
   */
  static void validateRelationLevel(Integer relationLevel) {
    if (relationLevel == null) {
      return; // 允许为空，不强制填写
    }
    if (relationLevel < 1 || relationLevel > 9) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "关系等级必须在 1-9 之间");
    }
  }
}
