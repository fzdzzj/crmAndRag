package com.slz.crm.pojo.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.HeadFontStyle;
import com.slz.crm.pojo.ao.Excel;
import com.slz.crm.pojo.ao.Privacy;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode
public class GetCustomerContactExcel implements Excel, Privacy {


    @ExcelProperty("公司名称")
    @HeadFontStyle(color = 10)
    @ColumnWidth(25)
    private String companyName;

    @ExcelProperty("联系人姓名")
    @HeadFontStyle(color = 10)
    @ColumnWidth(15)
    private String name;

    @ExcelProperty("职位")
    @ColumnWidth(15)
    private String position;

    @ExcelProperty("部门")
    @ColumnWidth(15)
    private String dept;

    @ExcelProperty("固定电话")
    @ColumnWidth(20)
    private String phone;

    @Override
    public Boolean phone() {
        this.phone = "********";
        return true;
    }

    @ExcelProperty("手机号码")
    @HeadFontStyle(color = 10)
    @ColumnWidth(20)
    private String mobile;

    @Override
    public Boolean mobile() {
        this.mobile = "********";
        return true;
    }

    @ExcelProperty("邮箱")
    @ColumnWidth(30)
    private String email;

    @Override
    public Boolean email() {
        this.email = "********";
        return true;
    }

    @ExcelProperty("性别")
    @ColumnWidth(10)
    private String gender;

    @ExcelProperty("关系等级")
    @ColumnWidth(15)
    private String relationLevel;

    @ExcelProperty("喜好备注")
    @ColumnWidth(40)
    private String hobbyRemark;

    @ExcelProperty("住址备注")
    @ColumnWidth(50)
    private String addressRemark;

    @ExcelProperty("本人出生日期 (格式：yyyy-MM-dd)")
    @ColumnWidth(15)
    private String selfBirthday;

    @ExcelProperty("亲属信息 (格式： 姓名:日期;姓名:日期)")
    @ColumnWidth(60)
    private String relativeInfo;

}