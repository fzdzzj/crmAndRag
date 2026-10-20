package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.RemarkType;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.CustomerContactRemarkDTO;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.server.mapper.CustomerContactRemarkMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeanUtils;

/** 客户联系人备注校验与维护支持类：备注类型/关系等级校验与备注增删维护，纯静态、无状态。 */
final class CustomerContactRemarkValidator {

  private CustomerContactRemarkValidator() {}

  /** 校验并整体替换联系人备注：先删除原有备注，再全量校验后批量插入新备注（remarks 为空即清空备注） */
  static void replaceRemarks(
      Long contactId,
      List<CustomerContactRemarkDTO> remarks,
      Long creatorId,
      CustomerContactRemarkMapper mapper) {
    // 删除该联系人原有的所有备注
    LambdaQueryWrapper<CustomerContactRemarkEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(CustomerContactRemarkEntity::getContactId, contactId);
    mapper.delete(queryWrapper);

    // 新增备注（校验前置、单次批量插入，见 insertRemarks）
    insertRemarks(contactId, remarks, creatorId, mapper);
  }

  /**
   * 校验并插入联系人备注（不删除已有备注，remarks 为空时无操作）。
   *
   * <p>E-14：旧实现「逐条校验 + 逐条 insert」，第 N 条非法时前 N-1 条已入库；现改为先把全部 DTO 校验一遍（任一非法即零写入）， 再构建实体列表调一次 {@code
   * mapper.insertBatch}（XML foreach 多值 INSERT 已存在）。实体装饰 （BeanUtils 拷贝 +
   * contactId/creatorId）与旧实现一致；create_time 显式填充，等价旧单条 insert 的库默认值。
   */
  static void insertRemarks(
      Long contactId,
      List<CustomerContactRemarkDTO> remarks,
      Long creatorId,
      CustomerContactRemarkMapper mapper) {
    if (remarks == null || remarks.isEmpty()) {
      return;
    }

    // 1. 先全量校验：任一非法即零写入
    for (CustomerContactRemarkDTO remark : remarks) {
      validateRemarkType(remark);
    }

    // 2. 构建实体（装饰与旧实现一致）后一次批量插入
    List<CustomerContactRemarkEntity> entities = new ArrayList<>(remarks.size());
    LocalDateTime createTime = LocalDateTime.now();
    for (CustomerContactRemarkDTO remark : remarks) {
      CustomerContactRemarkEntity remarkEntity = new CustomerContactRemarkEntity();
      BeanUtils.copyProperties(remark, remarkEntity);
      remarkEntity.setContactId(contactId);
      remarkEntity.setCreatorId(creatorId);
      remarkEntity.setCreateTime(createTime);
      entities.add(remarkEntity);
    }
    mapper.insertBatch(entities);
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
