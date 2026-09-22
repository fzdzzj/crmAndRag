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

    validateCompanyNameNotEmpty(customerCompanyExcel);

    String companyName = customerCompanyExcel.getCompanyName().trim();
    String dept = normalizeDept(customerCompanyExcel.getDept());

    // 本次上传去重 key：公司名 + 部门（部门可空，空按未填写处理）
    String dedupKey = companyName + "|" + (dept == null ? "" : dept);

    // 检查本次上传中是否已经处理过该公司（去重）
    if (!processedCompanies.containsKey(dedupKey)) {
      processNewRow(customerCompanyExcel, companyName, dept, dedupKey);
    }
  }

  /** 校验公司名称非空（Excel 导入的必填字段） */
  private void validateCompanyNameNotEmpty(CustomerCompanyExcel customerCompanyExcel) {
    if (customerCompanyExcel.getCompanyName() == null
        || customerCompanyExcel.getCompanyName().trim().isEmpty()) {
      throw new BaseException(
          ErrorCode.EXCEL_FORMAT_ERROR,
          String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
    }
  }

  /** 处理首次出现的行：先查库去重，已存在则仅登记，否则构造新实体并入列表 */
  private void processNewRow(
      CustomerCompanyExcel customerCompanyExcel, String companyName, String dept, String dedupKey) {
    CustomerCompanyEntity existingCompany = findExistingCompany(companyName, dept);
    if (existingCompany != null) {
      // 数据库中已存在同名同部门，记录到已处理列表，但不添加到 dataList（不更新）
      processedCompanies.put(dedupKey, existingCompany);
      return;
    }
    CustomerCompanyEntity customerCompany = buildNewCompany(customerCompanyExcel, dept);
    // 记录到已处理列表
    processedCompanies.put(dedupKey, customerCompany);
    dataList.add(customerCompany);
  }

  /** 查询数据库中是否已存在"同名同部门"公司（未删除的） */
  private CustomerCompanyEntity findExistingCompany(String companyName, String dept) {
    return customerCompanyMapper.selectOne(
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
  }

  /** 从 Excel 行构造新公司实体：字段拷贝 + 逐项校验与关联字段填充 */
  private CustomerCompanyEntity buildNewCompany(
      CustomerCompanyExcel customerCompanyExcel, String dept) {
    // 转为 Entity 类型
    CustomerCompanyEntity customerCompany = new CustomerCompanyEntity();
    BeanUtils.copyProperties(customerCompanyExcel, customerCompany);

    // 部门统一规范化：空串视为 null，避免库里同时存在 null 和空串两种"未填写"
    customerCompany.setDept(dept);

    if (customerCompany.getCreatorId() == null) {
      customerCompany.setCreatorId(ownerId);
    }

    applyCustomerType(customerCompany, customerCompanyExcel);
    applyGrade(customerCompany, customerCompanyExcel);
    validateDeptLength(dept, customerCompanyExcel);
    validateCreatorExists(customerCompany);
    applyOwnerByName(customerCompany, customerCompanyExcel);
    validatePhoneAndCompanyName(customerCompany);

    return customerCompany;
  }

  /** 处理客户属性字段，直接存储字符串值（可为空），只允许"代理"或"直销" */
  private void applyCustomerType(
      CustomerCompanyEntity customerCompany, CustomerCompanyExcel customerCompanyExcel) {
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
  }

  /** 处理客户等级字段（0-9，所有用户都可以配置），未填写默认为 0 */
  private void applyGrade(
      CustomerCompanyEntity customerCompany, CustomerCompanyExcel customerCompanyExcel) {
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
  }

  /** 校验部门长度（可选字段，长度不超过 50） */
  private void validateDeptLength(String dept, CustomerCompanyExcel customerCompanyExcel) {
    if (dept != null && dept.length() > 50) {
      throw new BaseException(
          ErrorCode.EXCEL_FORMAT_ERROR,
          String.format(
              "部门格式错误，长度不能超过 50 个字符，第%d行值为：%s",
              dataList.size() + 2, customerCompanyExcel.getDept()));
    }
  }

  /** 校验创建人是否存在于用户表 */
  private void validateCreatorExists(CustomerCompanyEntity customerCompany) {
    if (customerCompany.getCreatorId() != null) {
      UserEntity userEntity = userMapper.selectById(customerCompany.getCreatorId());
      if (userEntity == null) {
        throw new BaseException(
            ErrorCode.EXCEL_FORMAT_ERROR,
            String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
      }
    }
  }

  /** 按负责人名称查询用户并填充负责人 ID（查不到则保持为空） */
  private void applyOwnerByName(
      CustomerCompanyEntity customerCompany, CustomerCompanyExcel customerCompanyExcel) {
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
  }

  /** 校验电话格式（选填，填了才校验手机格式）与公司名称必填 */
  private void validatePhoneAndCompanyName(CustomerCompanyEntity customerCompany) {
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
  }

  /** 规范化部门：去除首尾空格，空串视为 null（与“未填写”等价） */
  private static String normalizeDept(String dept) {
    String result = dept == null ? null : dept.trim();
    if (result != null && result.isEmpty()) {
      result = null;
    }
    return result;
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
