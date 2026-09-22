package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.server.service.DataConvertService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.BeanUtils;

/**
 * 合同 VO 组装支持类（tighten-pmd-residual-325 任务 6.3 自 ContractServiceImpl 拆出，行为等价）。 批量收集用户/公司/商机 ID
 * 并一次换名， 供分页查询与全量查询两条路径共用。
 */
final class ContractVoAssembler {
  private ContractVoAssembler() {}

  /** 分页实体页 → 分页 VO 页（记录逐条换名转换，分页元信息原样拷贝）。 */
  static Page<ContractVO> toVoPage(
      Page<ContractEntity> entityPage, DataConvertService dataConvertService) {
    List<ContractEntity> entityList = entityPage.getRecords();
    Map<Long, String> userNameMap = dataConvertService.getUserNames(collectUserIds(entityList));
    Map<Long, String> companyNameMap =
        dataConvertService.getCompanyNames(collectCompanyIds(entityList));
    Map<Long, String> opportunityNameMap =
        dataConvertService.getOpportunityNames(collectOpportunityIds(entityList));

    Page<ContractVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage);
    voPage.setRecords(toVoList(entityList, userNameMap, companyNameMap, opportunityNameMap));
    return voPage;
  }

  private static Set<Long> collectUserIds(List<ContractEntity> entityList) {
    Set<Long> allUserIds = new HashSet<>();
    for (ContractEntity entity : entityList) {
      if (entity.getCreatorId() != null) allUserIds.add(entity.getCreatorId());
      if (entity.getOwnerId() != null) allUserIds.add(entity.getOwnerId());
    }
    return allUserIds;
  }

  private static Set<Long> collectCompanyIds(List<ContractEntity> entityList) {
    Set<Long> companyIds = new HashSet<>();
    for (ContractEntity entity : entityList) {
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
    }
    return companyIds;
  }

  private static Set<Long> collectOpportunityIds(List<ContractEntity> entityList) {
    Set<Long> opportunityIds = new HashSet<>();
    for (ContractEntity entity : entityList) {
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
    }
    return opportunityIds;
  }

  private static List<ContractVO> toVoList(
      List<ContractEntity> entityList,
      Map<Long, String> userNameMap,
      Map<Long, String> companyNameMap,
      Map<Long, String> opportunityNameMap) {
    return entityList.stream()
        .map(
            entity -> {
              String companyName = companyNameMap.get(entity.getCompanyId());
              String opportunityName = opportunityNameMap.get(entity.getOpportunityId());
              String ownerName = userNameMap.get(entity.getOwnerId());
              String creatorName = userNameMap.get(entity.getCreatorId());
              return ContractVO.fromEntity(
                  entity, companyName, opportunityName, ownerName, creatorName);
            })
        .toList();
  }
}
