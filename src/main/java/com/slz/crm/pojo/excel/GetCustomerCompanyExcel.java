package com.slz.crm.pojo.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.HeadFontStyle;
import com.slz.crm.pojo.ao.Excel;
import com.slz.crm.pojo.ao.Privacy;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
public class GetCustomerCompanyExcel implements Excel, Privacy {

  @ExcelProperty("公司名称")
  @HeadFontStyle(color = 10)
  private String companyName;

  @ExcelProperty("行业")
  private String industry;

  @ExcelProperty("属性(直销/代理)")
  private String customerType;

  @ExcelProperty("归属集团")
  private String belongGroup;

  @ExcelProperty("部门")
  private String dept;

  @ExcelProperty("地址")
  private String address;

  @ExcelProperty("电话")
  @HeadFontStyle(color = 10)
  @ColumnWidth(15)
  private String phone;

  @Override
  public Boolean phone() {
    this.phone = "********";
    return true;
  }

  @ExcelProperty("网址")
  @ColumnWidth(15)
  private String website;

  @Override
  public Boolean website() {
    this.website = "***.*****.***";
    return true;
  }

  @ExcelProperty("描述")
  @ColumnWidth(30)
  private String description;

  @ExcelProperty("等级(0-9)")
  @ColumnWidth(30)
  private Integer grade;

  @Override
  public Boolean grade() {
    this.grade = -1;
    return true;
  }

  @ExcelProperty("负责人名称")
  @HeadFontStyle(color = 10)
  private String ownerName;
}
