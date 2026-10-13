package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.excel.GetCustomerCompanyExcel;
import java.util.List;

/**
 * 客户公司查询装配支持类（add-customer-semantic-retrieval 任务 4.3 PMD NcssCount 拆分：查询条件装配与模板导出 拆自 {@link
 * CustomerCompanyServiceImpl}，行为等价；likeIfNotBlank/eqIfNotBlank 复用同包 {@link
 * CustomerCompanySupport}）。纯静态、无状态。
 */
final class CustomerCompanyQuerySupport {

  private CustomerCompanyQuerySupport() {}

  /**
   * 组合查询的 wrapper 装配（拆自 customQuery，行为等价）：九字段非空白即过滤 + 软删位可指定。
   *
   * @param dto 查询条件
   * @return 组装好的查询 wrapper
   */
  static LambdaQueryWrapper<CustomerCompanyEntity> customQueryWrapper(CustomerCompanyDTO dto) {
    LambdaQueryWrapper<CustomerCompanyEntity> wrapper = new LambdaQueryWrapper<>();
    CustomerCompanySupport.likeIfNotBlank(
        wrapper, CustomerCompanyEntity::getCompanyName, dto.getCompanyName());
    CustomerCompanySupport.likeIfNotBlank(
        wrapper, CustomerCompanyEntity::getIndustry, dto.getIndustry());
    CustomerCompanySupport.eqIfNotBlank(
        wrapper, CustomerCompanyEntity::getCustomerType, dto.getCustomerType());
    CustomerCompanySupport.likeIfNotBlank(
        wrapper, CustomerCompanyEntity::getBelongGroup, dto.getBelongGroup());
    CustomerCompanySupport.likeIfNotBlank(wrapper, CustomerCompanyEntity::getDept, dto.getDept());
    CustomerCompanySupport.likeIfNotBlank(
        wrapper, CustomerCompanyEntity::getAddress, dto.getAddress());
    CustomerCompanySupport.likeIfNotBlank(wrapper, CustomerCompanyEntity::getPhone, dto.getPhone());
    CustomerCompanySupport.likeIfNotBlank(
        wrapper, CustomerCompanyEntity::getWebsite, dto.getWebsite());
    CustomerCompanySupport.likeIfNotBlank(
        wrapper, CustomerCompanyEntity::getDescription, dto.getDescription());
    if (dto.getIsDeleted() != null) {
      wrapper.eq(CustomerCompanyEntity::getIsDeleted, dto.getIsDeleted());
    }
    return wrapper;
  }

  /**
   * 分组查询前置（拆自 groupByCustomerCompany，行为等价）：字段合法性校验 + 未删除过滤。
   *
   * @param field 分组字段（industry/customerType/ownerId）
   * @return 组装好的查询 wrapper
   */
  static LambdaQueryWrapper<CustomerCompanyEntity> groupFieldQuery(String field) {
    if (!List.of("industry", "customerType", "ownerId").contains(field)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }
    return new LambdaQueryWrapper<CustomerCompanyEntity>()
        .eq(CustomerCompanyEntity::getIsDeleted, false);
  }

  /**
   * 集团维度查询 wrapper（拆自 findCompanyByGroup，行为等价）。
   *
   * @param belongGroup 所属集团（like 匹配）
   * @return 组装好的查询 wrapper
   */
  static LambdaQueryWrapper<CustomerCompanyEntity> belongGroupQuery(String belongGroup) {
    return new LambdaQueryWrapper<CustomerCompanyEntity>()
        .like(CustomerCompanyEntity::getBelongGroup, belongGroup)
        .eq(CustomerCompanyEntity::getIsDeleted, false);
  }

  /**
   * 下载模板字节流（拆自 template()，行为等价）。
   *
   * @param aotoExcelUntil Excel 导出工具
   * @return 模板字节流
   */
  static byte[] buildTemplateBytes(AOTOExcelUntil aotoExcelUntil) {
    List<GetCustomerCompanyExcel> rows = List.of(new GetCustomerCompanyExcel());
    return aotoExcelUntil.AOTOExcelByStream(rows, "customer_company_template.xlsx");
  }
}
