package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.server.service.DataConvertService;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 协助列表内容回填（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属：待我协助/我发起的协助列表的 {@code fillRecordContent}。把采集到的来源记录摘要与公司/联系人名称回填到 VO。 只读，不写库、不带事务注解、不新开事务。
 */
class AssistRecordBriefFiller {

  private final AssistRecordBriefCollector briefCollector;
  private final DataConvertService dataConvertService;

  AssistRecordBriefFiller(
      AssistRecordBriefCollector briefCollector, DataConvertService dataConvertService) {
    this.briefCollector = briefCollector;
    this.dataConvertService = dataConvertService;
  }

  /** 为协助列表填充关联业务记录摘要（标题/内容/时间）与公司/联系人名称。 */
  void fillRecordContent(List<AssistVO> voList) {
    if (voList != null && !voList.isEmpty()) {
      Map<String, Map<Long, AssistRecordBrief>> briefsByModel = new HashMap<>();
      putIfNotEmpty(
          briefsByModel,
          ModelName.SALES_STAGE_APPROVAL,
          briefCollector.collectApprovalBriefs(voList));
      putIfNotEmpty(
          briefsByModel, ModelName.BUSINESS_ACTIVITY, briefCollector.collectActivityBriefs(voList));
      putIfNotEmpty(
          briefsByModel, ModelName.CONTACT_TASK, briefCollector.collectTaskBriefs(voList));

      applyBriefs(voList, briefsByModel);
      fillCompanyContactNames(voList);
    }
  }

  /** 非空摘要映射才放进按模型分组的字典，保持与原「空集合不登记」行为一致。 */
  private void putIfNotEmpty(
      Map<String, Map<Long, AssistRecordBrief>> briefsByModel,
      String modelName,
      Map<Long, AssistRecordBrief> briefs) {
    if (briefs != null && !briefs.isEmpty()) {
      briefsByModel.put(modelName, briefs);
    }
  }

  /** 将各模型摘要回填到 VO；模型或记录缺失时跳过该 VO。 */
  private void applyBriefs(
      List<AssistVO> voList, Map<String, Map<Long, AssistRecordBrief>> briefsByModel) {
    for (AssistVO vo : voList) {
      if (vo.getModelName() != null && vo.getRecordId() != null) {
        Map<Long, AssistRecordBrief> modelBriefs = briefsByModel.get(vo.getModelName());
        AssistRecordBrief brief = modelBriefs == null ? null : modelBriefs.get(vo.getRecordId());
        if (brief != null) {
          vo.setRecordTitle(brief.title);
          vo.setRecordContent(brief.content);
          vo.setRecordTime(brief.time);
          vo.setOpportunityId(brief.opportunityId);
          vo.setOpportunityName(brief.opportunityName);
          vo.setCompanyId(brief.companyId);
          vo.setContactId(brief.contactId);
        }
      }
    }
  }

  /** 批量填充 VO 上的公司/联系人名称。 */
  private void fillCompanyContactNames(List<AssistVO> voList) {
    Set<Long> companyIds =
        voList.stream()
            .map(AssistVO::getCompanyId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Set<Long> contactIds =
        voList.stream()
            .map(AssistVO::getContactId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> companyNameMap =
        companyIds.isEmpty()
            ? Collections.emptyMap()
            : dataConvertService.getCompanyNames(companyIds);
    Map<Long, String> contactNameMap =
        contactIds.isEmpty()
            ? Collections.emptyMap()
            : dataConvertService.getContactNames(contactIds);
    for (AssistVO vo : voList) {
      if (vo.getCompanyId() != null) {
        vo.setCompanyName(companyNameMap.get(vo.getCompanyId()));
      }
      if (vo.getContactId() != null) {
        vo.setContactName(contactNameMap.get(vo.getContactId()));
      }
    }
  }
}
