package com.slz.crm.common.excellistener;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ValidationUtils;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.pojo.excel.CustomerContactExcel;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import lombok.Getter;
import org.springframework.beans.BeanUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CustomerContactListener implements ReadListener<CustomerContactExcel> {

    @Getter
    private final List<CustomerContactEntity> dataList = new ArrayList<>();

    @Getter
    private final List<List<CustomerContactRemarkEntity>> remarkList = new ArrayList<>();

    private final Long ownerId;
    private final CustomerCompanyMapper customerCompanyMapper;

    public CustomerContactListener(CustomerCompanyMapper customerCompanyMapper) {
        this.ownerId = BaseUnit.getCurrentId();
        this.customerCompanyMapper = customerCompanyMapper;
    }

    @Override
    public void invoke(CustomerContactExcel customerContactExcel, AnalysisContext analysisContext) {


        //检查是否存在联系人姓名，电话，性别，公司
        if(customerContactExcel.getName() == null ||
                customerContactExcel.getMobile() == null ||
                customerContactExcel.getGender() == null ||
                (customerContactExcel.getCompanyId() == null && customerContactExcel.getCompanyName() == null)){
            throw new BaseException(ErrorCode.EXCEL_FORMAT_ERROR, String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
        }

        //todo检查手机号和固定电话格式
//        if((!ValidationUtils.isValidMobile(customerContactExcel.getMobile())) || ((customerContactExcel.getPhone() != null) && (!ValidationUtils.isValidPhone(customerContactExcel.getPhone())))){
//            throw new BaseException(ErrorCode.EXCEL_FORMAT_ERROR, String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), dataList.size() + 2));
//        }

        //转为 Entity 类型
        CustomerContactEntity customerContact = new CustomerContactEntity();
        BeanUtils.copyProperties(customerContactExcel, customerContact);

        //转化性别
        customerContact.setGender(Objects.equals(customerContactExcel.getGender(), "男") ? 1 : 2);

        //转化关系等级
        if (customerContactExcel.getRelationLevel() != null && !customerContactExcel.getRelationLevel().isEmpty()) {
            try {
                customerContact.setRelationLevel(Integer.parseInt(customerContactExcel.getRelationLevel()));
            } catch (NumberFormatException e) {
                throw new BaseException(ErrorCode.EXCEL_FORMAT_ERROR, "关系等级必须为数字");
            }
        }

        if(customerContact.getCreatorId() == null){
            customerContact.setCreatorId(ownerId);
        }
        //判断是否通过公司名称添加公司
        CustomerCompanyEntity customerCompany;

        //判断是否填写公司 ID，如果没有填写，则通过公司名称进行添加
        if(customerContactExcel.getCompanyId() == null){
            //通过公司名称添加公司（只查询未删除的公司）
            customerCompany = customerCompanyMapper.selectOne(new LambdaQueryWrapper<CustomerCompanyEntity>()
                    .eq(CustomerCompanyEntity::getCompanyName, customerContactExcel.getCompanyName())
                    .eq(CustomerCompanyEntity::getIsDeleted, false)
                    .last("LIMIT 1"));

            if(customerCompany == null){
                throw new BaseException(ErrorCode.EXCEL_COMPANY_NOT_EXISTS, String.format(ErrorCode.EXCEL_COMPANY_NOT_EXISTS.getMessage(), dataList.size() + 2));
            }

            customerContact.setCompanyId(customerCompany.getId());
        }

        // 存储备注数据（与 dataList 索引对应）
        List<CustomerContactRemarkEntity> remarks = parseRemarks(customerContactExcel, ownerId);
        remarkList.add(remarks);

        dataList.add(customerContact);
    }

    /**
     * 解析 Excel 中的备注列，生成备注实体列表
     */
    private List<CustomerContactRemarkEntity> parseRemarks(CustomerContactExcel excel, Long creatorId) {
        List<CustomerContactRemarkEntity> remarkList = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 1. 解析喜好备注（类型 1）
        if (excel.getHobbyRemark() != null && !excel.getHobbyRemark().trim().isEmpty()) {
            CustomerContactRemarkEntity remark = new CustomerContactRemarkEntity();
            remark.setRemarkType(1);
            remark.setRemarkContent(excel.getHobbyRemark().trim());
            remark.setCreatorId(creatorId);
            remarkList.add(remark);
        }

        // 2. 解析住址备注（类型 2）
        if (excel.getAddressRemark() != null && !excel.getAddressRemark().trim().isEmpty()) {
            CustomerContactRemarkEntity remark = new CustomerContactRemarkEntity();
            remark.setRemarkType(2);
            remark.setRemarkContent(excel.getAddressRemark().trim());
            remark.setCreatorId(creatorId);
            remarkList.add(remark);
        }

        // 3. 解析本人出生日期（类型 3）
        if (excel.getSelfBirthday() != null && !excel.getSelfBirthday().trim().isEmpty()) {
            try {
                LocalDate birthday = LocalDate.parse(excel.getSelfBirthday().trim(), formatter);
                CustomerContactRemarkEntity remark = new CustomerContactRemarkEntity();
                remark.setRemarkType(3);
                remark.setRemarkDate(birthday);
                remark.setCreatorId(creatorId);
                remarkList.add(remark);
            } catch (Exception e) {
                throw new BaseException(ErrorCode.EXCEL_FORMAT_ERROR, "本人出生日期格式错误，应为 yyyy-MM-dd");
            }
        }

        // 4. 解析亲属信息（类型 4，格式：姓名:日期;姓名:日期）
        if (excel.getRelativeInfo() != null && !excel.getRelativeInfo().trim().isEmpty()) {
            String[] relativeItems = excel.getRelativeInfo().trim().split(";");
            for (String item : relativeItems) {
                if (item.trim().isEmpty()) {
                    continue;
                }
                String[] parts = item.trim().split(":");
                if (parts.length != 2) {
                    throw new BaseException(ErrorCode.EXCEL_FORMAT_ERROR,
                        "亲属信息格式错误，应为：姓名:日期;姓名:日期");
                }
                try {
                    String name = parts[0].trim();
                    LocalDate birthday = LocalDate.parse(parts[1].trim(), formatter);
                    CustomerContactRemarkEntity remark = new CustomerContactRemarkEntity();
                    remark.setRemarkType(4);
                    remark.setRemarkName(name);
                    remark.setRemarkDate(birthday);
                    remark.setCreatorId(creatorId);
                    remarkList.add(remark);
                } catch (Exception e) {
                    throw new BaseException(ErrorCode.EXCEL_FORMAT_ERROR,
                        "亲属信息中日期格式错误，应为 yyyy-MM-dd");
                }
            }
        }

        return remarkList;
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext analysisContext) {

    }



}
