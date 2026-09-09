package com.slz.crm.pojo.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.HeadFontStyle;
import com.slz.crm.pojo.ao.Excel;
import com.slz.crm.pojo.ao.Privacy;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode
public class UserExcel implements Excel, Privacy {

    @ExcelProperty("ID")
    private Long id;

    @ExcelProperty("初始密码")
    private String password;

    @ExcelProperty("真名")
    @HeadFontStyle(color = 10)
    private String realName;

    @ExcelProperty("手机号")
    private String phone;

    @ExcelProperty("邮箱")
    @HeadFontStyle(color = 10)
    private String email;

    @ExcelProperty("角色ID")
    @HeadFontStyle(color = 10)
    private Long roleId;

    @ExcelProperty("账号状态")
    private int status;

    @ExcelProperty("创建人ID")
    private Long creatorId;

    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @ExcelProperty("更新时间")
    private LocalDateTime updateTime;


}
