package com.slz.crm.common.excellistener;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ValidationUtils;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.excel.CustomerCompanyExcel;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeanUtils;

public class CustomerCompanyListener implements ReadListener<CustomerCompanyExcel> {

  private final List<CustomerCompanyEntity> dataList = new ArrayList<>();
  private final Long ownerId;
  private final UserMapper userMapper;
  private final CustomerCompanyMapper customerCompanyMapper;

  /** 本次 Excel 实际读取到的数据行数（含重复/已存在的行） */
  private int rowCount = 0;

  // 用于记录本次上传中已处理的公司（按“公司名+部门”组合去重，同名不同部门可同时导入）
  private final Map<String, CustomerCompanyEntity> processedCompanies = new HashMap<>();

  public CustomerCompanyListener(
      UserMapper userMapper, CustomerCompanyMapper customerCompanyMapper) {
    this.ownerId = BaseUnit.getCurrentId();
    this.userMapper = userMapper;
    this.customerCompanyMapper = customerCompanyMapper;
  }

  @Override
  public void invoke(CustomerCompanyExcel customerCompanyExcel, AnalysisContext analysisContext) {

    rowCount++;

    // 检查公司名称是否为空
    if (customerCompanyExcel.getCompanyName() == null
        || customerCompanyExcel.getCompanyName().trim().isEmpty()) {
      throw new BaseException(
          ErrorCode.EXCEL_FORMAT_ERROR,
          String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
    }

    String companyName = customerCompanyExcel.getCompanyName().trim();
    String dept = normalizeDept(customerCompanyExcel.getDept());

    // 本次上传去重 key：公司名 + 部门（部门可空，空按未填写处理）
    String dedupKey = companyName + "|" + (dept == null ? "" : dept);

    // 检查本次上传中是否已经处理过该公司（去重）
    if (processedCompanies.containsKey(dedupKey)) {
      // 已处理过，跳过
      return;
    }

    // 检查数据库中是否已存在“同名同部门”公司（未删除的）
    CustomerCompanyEntity existingCompany =
        customerCompanyMapper.selectOne(
            new LambdaQueryWrapper<CustomerCompanyEntity>()
                .eq(CustomerCompanyEntity::getCompanyName, companyName)
                .and(
                    w -> {
                      if (dept == null) {
                        w.isNull(CustomerCompanyEntity::getDept)
                            .or()
                            .eq(CustomerCompanyEntity::getDept, "");
                      } else {
                        w.eq(CustomerCompanyEntity::getDept, dept);
                      }
                    })
                .eq(CustomerCompanyEntity::getIsDeleted, false)
                .last("LIMIT 1"));

    if (existingCompany != null) {
      // 数据库中已存在同名同部门，记录到已处理列表，但不添加到 dataList（不更新）
      processedCompanies.put(dedupKey, existingCompany);
      return;
    }

    // 转为 Entity 类型
    CustomerCompanyEntity customerCompany = new CustomerCompanyEntity();
    BeanUtils.copyProperties(customerCompanyExcel, customerCompany);

    // 部门统一规范化：空串视为 null，避免库里同时存在 null 和空串两种“未填写”
    customerCompany.setDept(dept);

    if (customerCompany.getCreatorId() == null) {
      customerCompany.setCreatorId(ownerId);
    }

    // 处理客户属性字段，直接存储字符串值（可为空）
    String customerTypeStr = customerCompanyExcel.getCustomerType();
    if (customerTypeStr != null && !customerTypeStr.trim().isEmpty()) {
      String trimmedType = customerTypeStr.trim();
      if (!"代理".equals(trimmedType) && !"直销".equals(trimmedType)) {
        throw new BaseException(
            ErrorCode.EXCEL_FORMAT_ERROR,
            String.format("客户属性格式错误，只能为'代理'或'直销'，第%d行值为：%s", dataList.size() + 2, trimmedType));
      }
      customerCompany.setCustomerType(trimmedType);
    }

    // 处理客户等级字段（0-9，所有用户都可以配置）
    Integer grade = customerCompanyExcel.getGrade();
    if (grade != null) {
      // 检查等级范围是否在 0-9 之间
      if (grade < 0 || grade > 9) {
        throw new BaseException(
            ErrorCode.EXCEL_FORMAT_ERROR,
            String.format("客户等级格式错误，必须在 0-9 之间，第%d行值为：%d", dataList.size() + 2, grade));
      }
      customerCompany.setGrade(grade);
    } else {
      // 如果未填写等级，默认为 0
      customerCompany.setGrade(0);
    }

    // 检查部门长度（可选字段，长度不超过 50）
    if (dept != null && dept.length() > 50) {
      throw new BaseException(
          ErrorCode.EXCEL_FORMAT_ERROR,
          String.format(
              "部门格式错误，长度不能超过 50 个字符，第%d行值为：%s",
              dataList.size() + 2, customerCompanyExcel.getDept()));
    }

    // 检查创建人是否存在
    if (customerCompany.getCreatorId() != null) {
      UserEntity userEntity = userMapper.selectById(customerCompany.getCreatorId());
      if (userEntity == null) {
        throw new BaseException(
            ErrorCode.EXCEL_FORMAT_ERROR,
            String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
      }
    }

    // 检查负责人是否存在（通过负责人名称查询）
    if (customerCompanyExcel.getOwnerName() != null
        && !customerCompanyExcel.getOwnerName().trim().isEmpty()) {
      String ownerName = customerCompanyExcel.getOwnerName().trim();
      // 根据负责人名称查询用户
      UserEntity ownerUser =
          userMapper.selectOne(
              new LambdaQueryWrapper<UserEntity>()
                  .eq(UserEntity::getRealName, ownerName)
                  .last("LIMIT 1"));

      if (ownerUser != null) {
        // 找到负责人，设置负责人 ID
        customerCompany.setOwnerId(ownerUser.getId());
      }
    }

    // 检查格式：电话选填，填了才校验手机格式；公司名称必填
    if (customerCompany.getPhone() != null
        && !customerCompany.getPhone().isBlank()
        && !ValidationUtils.isValidMobile(customerCompany.getPhone())) {
      throw new BaseException(
          String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
    }
    if (customerCompany.getCompanyName() == null) {
      throw new BaseException(
          String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
    }

    // 记录到已处理列表
    processedCompanies.put(dedupKey, customerCompany);
    dataList.add(customerCompany);
  }

  /** 规范化部门：去除首尾空格，空串视为 null（与“未填写”等价） */
  private static String normalizeDept(String dept) {
    if (dept == null) {
      return null;
    }
    String trimmed = dept.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @Override
  public void doAfterAllAnalysed(AnalysisContext analysisContext) {}

  public List<CustomerCompanyEntity> getData() {
    return this.dataList;
  }

  public int getRowCount() {
    return rowCount;
  }

  @Override
  public void onException(Exception exception, AnalysisContext context) {
    // 处理读取异常（如数据格式错误等）
    if (exception instanceof IllegalArgumentException) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    } else if (exception instanceof BaseException) {
      // 如果是 BaseException，直接重新抛出，保留原始错误信息
      throw (BaseException) exception;
    } else {
      // 其他异常包装为 ServiceException
      throw new ServiceException(exception.getMessage());
    }
  }
}
