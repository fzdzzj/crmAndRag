package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.pojo.excel.CustomerContactExcel;
import com.slz.crm.server.mapper.CustomerContactRemarkMapper;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;

/** 客户联系人 Excel 导出行构建支持类：批量收集名称与备注并装配导出行，纯静态、无状态。 */
final class CustomerContactExcelSupport {

  private CustomerContactExcelSupport() {}

  /** Excel 导入后保存备注数据：按行序回填联系人 ID 并批量插入 */
  static void saveImportedRemarks(
      List<List<CustomerContactRemarkEntity>> remarkList,
      List<CustomerContactEntity> dataList,
      CustomerContactRemarkMapper customerContactRemarkMapper) {
    if (!remarkList.isEmpty()) {
      List<CustomerContactRemarkEntity> allRemarks = new ArrayList<>();
      for (int i = 0; i < remarkList.size(); i++) {
        List<CustomerContactRemarkEntity> remarks = remarkList.get(i);
        if (!remarks.isEmpty() && i < dataList.size()) {
          Long contactId = dataList.get(i).getId();
          for (CustomerContactRemarkEntity remark : remarks) {
            remark.setContactId(contactId);
            remark.setCreateTime(java.time.LocalDateTime.now());
            allRemarks.add(remark);
          }
        }
      }
      if (!allRemarks.isEmpty()) {
        customerContactRemarkMapper.insertBatch(allRemarks);
      }
    }
  }

  /** 由未删除的联系人实体批量构建 Excel 导出行（备注中提取喜好/住址/本人及亲属生日） */
  static List<CustomerContactExcel> buildExcelRows(
      List<CustomerContactEntity> entities,
      DataConvertService dataConvertService,
      CustomerContactRemarkMapper customerContactRemarkMapper) {
    List<CustomerContactExcel> list = new ArrayList<>();

    // 批量收集ID后统一查询
    Set<Long> creatorIds = new HashSet<>();
    Set<Long> companyIds = new HashSet<>();
    for (CustomerContactEntity entity : entities) {
      if (entity.getCreatorId() != null) creatorIds.add(entity.getCreatorId());
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
    }
    Map<Long, String> creatorNameMap = dataConvertService.getUserNames(creatorIds);
    Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);

    entities.forEach(
        entity -> {
          CustomerContactExcel excel = new CustomerContactExcel();
          BeanUtils.copyProperties(entity, excel);

          // 公司名称、性别、关系等级
          excel.setCreateName(creatorNameMap.get(entity.getCreatorId()));
          excel.setGender(entity.getGender() == null ? "" : (entity.getGender() == 0 ? "男" : "女"));
          excel.setCompanyName(companyNameMap.getOrDefault(entity.getCompanyId(), ""));

          // 客户关系等级（数字转字符串）
          if (entity.getRelationLevel() != null) {
            excel.setRelationLevel(String.valueOf(entity.getRelationLevel()));
          }

          // 查询该联系人的所有备注
          List<CustomerContactRemarkEntity> remarks =
              customerContactRemarkMapper.selectByContactId(entity.getId());

          // 获取喜好备注（类型 1）
          String hobbyRemark =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 1) // 1-喜好
                  .map(CustomerContactRemarkEntity::getRemarkContent)
                  .filter(content -> content != null && !content.trim().isEmpty())
                  .findFirst()
                  .orElse(null);
          excel.setHobbyRemark(hobbyRemark);

          // 获取住址备注（类型 2）
          String addressRemark =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 2) // 2-住址
                  .map(CustomerContactRemarkEntity::getRemarkContent)
                  .filter(content -> content != null && !content.trim().isEmpty())
                  .findFirst()
                  .orElse(null);
          excel.setAddressRemark(addressRemark);

          // 获取本人出生日期（类型 3）
          String selfBirthday =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 3) // 3-本人出生日期
                  .findFirst()
                  .map(r -> r.getRemarkDate().toString())
                  .orElse(null);
          excel.setSelfBirthday(selfBirthday);

          // 聚合亲属信息（类型 4），格式：姓名：日期;姓名:日期
          String relativeInfos =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 4) // 4-亲属出生日期
                  .filter(r -> r.getRemarkName() != null && r.getRemarkDate() != null)
                  .map(r -> r.getRemarkName() + ":" + r.getRemarkDate())
                  .collect(Collectors.joining(";"));
          excel.setRelativeInfo(relativeInfos);

          list.add(excel);
        });

    return list;
  }
}
