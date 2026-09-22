package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistMessageService;
import com.slz.crm.server.service.AssistRelatedRecordResolver;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.DataConvertService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协助申请服务实现。
 *
 * <p>tighten-pmd-residual-325 任务 4.5：本类方法数超阈值，拆类会切开同一聚合根的事务边界与权限收敛逻辑， 收益低于风险；按「类过大默认豁免」登记，不拆。
 */
@Service
@Slf4j
@SuppressWarnings("PMD.TooManyMethods") // 拆类触面大、收益低：同一聚合根的事务边界不宜被切开
public class AssistRequestServiceImpl extends ServiceImpl<AssistRequestMapper, AssistRequestEntity>
    implements AssistRequestService {

  @Autowired private UserMapper userMapper;

  @Autowired private DataConvertService dataConvertService;

  @Autowired private SalesStageApprovalMapper salesStageApprovalMapper;

  @Autowired private BusinessActivityMapper businessActivityMapper;

  @Autowired private BusinessActivityUserMapper businessActivityUserMapper;

  @Autowired private ContactTaskMapper contactTaskMapper;

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;

  @Autowired private ApprovalAttachmentService approvalAttachmentService;

  // 给历史快照中的每个附件临时签发下载令牌，令牌绑定当前查看人和协助记录。
  @Autowired private AttachmentDownloadTokenUtil downloadTokenUtil;

  // 仅用于读取应用的 context path，避免部署在子路径时拼出的下载地址失效。
  @Autowired private HttpServletRequest httpRequest;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private AssistRelatedRecordResolver assistRelatedRecordResolver;

  @Autowired private AssistMessageService assistMessageService;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void createAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList) {
    if (recordId == null || applyList == null || applyList.isEmpty()) {
      return;
    }

    // 同一次申请中重复选择同一人是明确的输入错误，不能静默取最后一条。
    Map<Long, AssistApplyItem> itemMap = new LinkedHashMap<>();
    for (AssistApplyItem item : applyList) {
      if (item == null || item.getAssistUserId() == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
      }
      if (item.getAssistUserId().equals(applicantId)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不能是申请人自己");
      }
      if (itemMap.putIfAbsent(item.getAssistUserId(), item) != null) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "同一次协助申请不能重复选择同一协助人");
      }
    }
    if (itemMap.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
    }
    for (AssistApplyItem item : itemMap.values()) {
      if (trimToNull(item.getApplyPurpose()) == null
          || trimToNull(item.getApplyRequirement()) == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "每位协助人都必须填写协作目的与协作要求");
      }
    }
    Set<Long> distinctIds = new LinkedHashSet<>(itemMap.keySet());

    // 幂等：同一业务记录上同一协助人不能同时存在两条待协助记录。
    List<AssistRequestEntity> existing =
        list(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getModelName, modelName)
                .eq(AssistRequestEntity::getRecordId, recordId)
                .in(AssistRequestEntity::getAssistUserId, distinctIds)
                .eq(AssistRequestEntity::getAssistStatus, 0)
                .select(AssistRequestEntity::getAssistUserId));
    if (!existing.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助人已有待协助申请，请勿重复发起");
    }

    // 校验协助人存在且在职（status=1）
    List<UserEntity> users = userMapper.selectBatchIds(distinctIds);
    Set<Long> activeIds =
        users.stream()
            .filter(u -> Objects.equals(u.getStatus(), 1))
            .map(UserEntity::getId)
            .collect(Collectors.toSet());
    for (Long assistUserId : distinctIds) {
      if (!activeIds.contains(assistUserId)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不存在或已冻结/离职：用户ID " + assistUserId);
      }
    }

    List<AssistRequestEntity> entities =
        distinctIds.stream()
            .map(
                assistUserId -> {
                  AssistApplyItem item = itemMap.get(assistUserId);
                  AssistRequestEntity entity = new AssistRequestEntity();
                  entity.setModelName(modelName);
                  entity.setRecordId(recordId);
                  entity.setApplicantId(applicantId);
                  entity.setApplyPurpose(trimToNull(item.getApplyPurpose()));
                  entity.setApplyRequirement(trimToNull(item.getApplyRequirement()));
                  entity.setAssistUserId(assistUserId);
                  entity.setAssistStatus(0);
                  entity.setPendingKey("PENDING");
                  entity.setCreateTime(LocalDateTime.now());
                  return entity;
                })
            .collect(Collectors.toList());

    for (AssistRequestEntity entity : entities) {
      try {
        save(entity);
        assistMessageService.appendSystemMessage(entity.getId(), "已发起协助申请");
      } catch (DuplicateKeyException e) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助人已有待协助申请，请勿重复提交");
      }
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void updatePendingAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList) {
    if (recordId == null || applyList == null) {
      return;
    }

    Map<Long, AssistApplyItem> itemMap = new LinkedHashMap<>();
    for (AssistApplyItem item : applyList) {
      if (item == null || item.getAssistUserId() == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
      }
      if (Objects.equals(item.getAssistUserId(), applicantId)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不能是申请人自己");
      }
      if (trimToNull(item.getApplyPurpose()) == null
          || trimToNull(item.getApplyRequirement()) == null) {
        throw new BaseException(ErrorCode.PARAM_REQUIRED, "每位协助人都必须填写协作目的与协作要求");
      }
      if (itemMap.putIfAbsent(item.getAssistUserId(), item) != null) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "同一次协助申请不能重复选择同一协助人");
      }
    }

    List<AssistRequestEntity> pendingAssists =
        list(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getModelName, modelName)
                .eq(AssistRequestEntity::getRecordId, recordId)
                .eq(AssistRequestEntity::getApplicantId, applicantId)
                .eq(AssistRequestEntity::getAssistStatus, 0));
    Map<Long, AssistRequestEntity> existingByUser =
        pendingAssists.stream()
            .collect(Collectors.toMap(AssistRequestEntity::getAssistUserId, entity -> entity));

    List<Long> removedIds =
        pendingAssists.stream()
            .filter(entity -> !itemMap.containsKey(entity.getAssistUserId()))
            .map(AssistRequestEntity::getId)
            .toList();
    if (!removedIds.isEmpty()) {
      approvalAttachmentService.removeByAndIds(removedIds, ModelName.ASSIST_REQUEST);
      if (!removeByIds(removedIds)) {
        throw new BaseException(ErrorCode.UPDATE_FAILED, "删除协助人失败");
      }
    }

    List<AssistRequestEntity> updates =
        pendingAssists.stream()
            .filter(entity -> itemMap.containsKey(entity.getAssistUserId()))
            .peek(
                entity -> {
                  AssistApplyItem item = itemMap.get(entity.getAssistUserId());
                  entity.setApplyPurpose(trimToNull(item.getApplyPurpose()));
                  entity.setApplyRequirement(trimToNull(item.getApplyRequirement()));
                })
            .collect(Collectors.toList());
    if (!updates.isEmpty() && !updateBatchById(updates)) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "更新协助申请失败");
    }

    List<AssistApplyItem> additions =
        itemMap.entrySet().stream()
            .filter(entry -> !existingByUser.containsKey(entry.getKey()))
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    createAssists(modelName, recordId, applicantId, additions);
  }

  private String trimToNull(String value) {
    String result = null;
    if (value != null) {
      String trimmed = value.trim();
      if (!trimmed.isEmpty()) {
        result = trimmed;
      }
    }
    return result;
  }

  @Override
  public List<AssistVO> listAssistsByRecords(String modelName, List<Long> recordIds) {
    if (recordIds == null || recordIds.isEmpty()) {
      return Collections.emptyList();
    }

    List<AssistRequestEntity> entities =
        list(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getModelName, modelName)
                .in(AssistRequestEntity::getRecordId, recordIds)
                .orderByAsc(AssistRequestEntity::getId));
    if (entities.isEmpty()) {
      return Collections.emptyList();
    }

    // 批量收集用户并查姓名/部门
    Set<Long> userIds = new HashSet<>();
    entities.forEach(
        e -> {
          userIds.add(e.getApplicantId());
          userIds.add(e.getAssistUserId());
        });
    Map<Long, UserEntity> userMap =
        userIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    Set<Long> deptIds =
        userMap.values().stream()
            .map(UserEntity::getDeptId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> deptNameMap =
        deptIds.isEmpty() ? Collections.emptyMap() : dataConvertService.getDeptNames(deptIds);

    return entities.stream()
        .map(
            entity -> {
              AssistVO vo = new AssistVO();
              vo.setId(entity.getId());
              vo.setModelName(entity.getModelName());
              vo.setRecordId(entity.getRecordId());
              vo.setApplicantId(entity.getApplicantId());
              vo.setApplyPurpose(entity.getApplyPurpose());
              vo.setApplyRequirement(entity.getApplyRequirement());
              vo.setAssistUserId(entity.getAssistUserId());
              vo.setAssistStatus(entity.getAssistStatus());
              vo.setAssistContent(entity.getAssistContent());
              vo.setRejectReason(entity.getRejectReason());
              vo.setParentId(entity.getParentId());
              vo.setAssistTime(entity.getAssistTime());
              vo.setCreateTime(entity.getCreateTime());

              UserEntity applicant = userMap.get(entity.getApplicantId());
              UserEntity assistUser = userMap.get(entity.getAssistUserId());
              vo.setApplicantName(applicant != null ? applicant.getRealName() : null);
              vo.setAssistUserName(assistUser != null ? assistUser.getRealName() : null);
              vo.setAssistUserDeptName(
                  assistUser != null && assistUser.getDeptId() != null
                      ? deptNameMap.get(assistUser.getDeptId())
                      : null);
              return vo;
            })
        .collect(Collectors.toList());
  }

  @Override
  public List<AssistVO> listAssistsByRecord(String modelName, Long recordId) {
    if (recordId == null) {
      return Collections.emptyList();
    }
    return listAssistsByRecords(modelName, Collections.singletonList(recordId));
  }

  @Override
  public Set<Long> getRelatedRecordIdsByUser(String modelName, Long userId) {
    if (modelName == null || userId == null) {
      return Collections.emptySet();
    }
    List<AssistRequestEntity> entities =
        list(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getModelName, modelName)
                .eq(AssistRequestEntity::getAssistUserId, userId)
                .eq(AssistRequestEntity::getAssistStatus, 0));
    if (entities.isEmpty()) {
      return Collections.emptySet();
    }
    return entities.stream()
        .map(AssistRequestEntity::getRecordId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  @Override
  public List<AssistVO> getVisibleAssists(String modelName, Long recordId, Long currentUserId) {
    List<AssistVO> all = listAssistsByRecord(modelName, recordId);
    if (all.isEmpty() || currentUserId == null) {
      return Collections.emptyList();
    }
    // 超管豁免：与列表可见性保持一致，超管可见全部协助记录
    UserEntity currentUser = userMapper.selectById(currentUserId);
    if (currentUser != null && Objects.equals(currentUser.getRoleId(), 1L)) {
      return all;
    }
    boolean isApplicant =
        all.stream().anyMatch(vo -> Objects.equals(vo.getApplicantId(), currentUserId));
    if (isApplicant) {
      return all;
    }
    AssistRequestEntity context = new AssistRequestEntity();
    context.setModelName(modelName);
    context.setRecordId(recordId);
    if (isCurrentBusinessResponsible(context, currentUserId)) {
      return all;
    }
    return all.stream().filter(vo -> Objects.equals(vo.getAssistUserId(), currentUserId)).toList();
  }

  @Override
  public Boolean handleAssist(AssistHandleDTO dto) {
    if (dto == null || dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    if (dto.getAssistStatus() == null
        || (!dto.getAssistStatus().equals(1)
            && !dto.getAssistStatus().equals(2)
            && !dto.getAssistStatus().equals(3))) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助状态只能为：1已协助/2已驳回/3已拒绝");
    }
    if (dto.getAssistStatus().equals(1)
        && (dto.getAssistContent() == null || dto.getAssistContent().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "协助内容不能为空");
    }
    if ((dto.getAssistStatus().equals(2) || dto.getAssistStatus().equals(3))
        && (dto.getRejectReason() == null || dto.getRejectReason().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "驳回/拒绝理由不能为空");
    }

    AssistRequestEntity entity = getById(dto.getId());
    if (entity == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    Long currentId = BaseUnit.getCurrentId();
    if (!Objects.equals(entity.getAssistUserId(), currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (!Objects.equals(entity.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助申请已处理");
    }

    // 必须先捕获终态前一刻的实时范围，再改变状态；这样快照与待协助期间
    // 协助人实际能看到的记录边界保持一致，快照失败时事务不会留下半更新状态。
    String recordSnapshot = buildRecordSnapshot(entity);

    entity.setAssistStatus(dto.getAssistStatus());
    entity.setPendingKey(null);
    if (dto.getAssistStatus().equals(1)) {
      entity.setAssistContent(dto.getAssistContent().trim());
      entity.setRejectReason(null);
    } else {
      entity.setRejectReason(dto.getRejectReason().trim());
      entity.setAssistContent(null);
    }
    // 任一终态均冻结按来源模型收窄后的业务快照；交付物不写入快照，单独按 assistId 查询。
    entity.setRecordSnapshot(recordSnapshot);
    entity.setAssistTime(LocalDateTime.now());
    if (!updateById(entity)) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "协助状态更新失败");
    }
    assistMessageService.appendSystemMessage(
        entity.getId(),
        switch (dto.getAssistStatus()) {
          case 1 -> "协助已完成";
          case 2 -> "协助已驳回";
          default -> "协助已拒绝";
        });
    return true;
  }

  /** 构建审批推进协助需要的完整商机详情。该方法只在协助结束时调用， 不能用于业务活动/联络任务协助，也不能用于待协助详情。 */
  private Map<String, Object> buildOpportunitySnapshotData(Long opportunityId) {
    if (opportunityId == null) {
      return null;
    }
    SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(opportunityId);
    if (opportunity == null) {
      return null;
    }

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("opportunityId", opportunity.getId());
    snapshot.put("opportunityName", opportunity.getOpportunityName());
    snapshot.put("stage", opportunity.getStage());
    snapshot.put("stageName", stageName(opportunity.getStage()));
    snapshot.put("amount", opportunity.getAmount());
    snapshot.put("expectedCloseDate", opportunity.getExpectedCloseDate());
    snapshot.put("source", opportunity.getSource());
    snapshot.put("description", opportunity.getDescription());
    snapshot.put("ownerId", opportunity.getOwnerId());
    snapshot.put("companyId", opportunity.getCompanyId());
    snapshot.put(
        "companyName",
        opportunity.getCompanyId() == null
            ? null
            : dataConvertService.getCompanyName(opportunity.getCompanyId()));
    snapshot.put("contactId", opportunity.getContactId());
    snapshot.put(
        "contactName",
        opportunity.getContactId() == null
            ? null
            : dataConvertService.getContactName(opportunity.getContactId()));

    List<BusinessActivityEntity> activities =
        businessActivityMapper.selectByOpportunityId(opportunity.getId());
    List<Map<String, Object>> activityList = new ArrayList<>();
    if (activities != null && !activities.isEmpty()) {
      List<Long> activityIds = activities.stream().map(BusinessActivityEntity::getId).toList();
      List<ApprovalAttachmentVO> attachmentVOs =
          Optional.ofNullable(
                  approvalAttachmentService.getByAndIds(activityIds, ModelName.BUSINESS_ACTIVITY))
              .orElseGet(Collections::emptyList);
      Map<Long, List<ApprovalAttachmentVO>> attachByActivity =
          attachmentVOs.stream().collect(Collectors.groupingBy(ApprovalAttachmentVO::getAndId));
      for (BusinessActivityEntity activity : activities) {
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("activityId", activity.getId());
        act.put("activityTitle", activity.getActivityTitle());
        act.put("activityType", activity.getActivityType());
        act.put("activityTime", activity.getActivityTime());
        act.put("activityContent", activity.getActivityContent());
        act.put(
            "attachments",
            attachmentMetadata(
                attachByActivity.getOrDefault(activity.getId(), Collections.emptyList())));
        activityList.add(act);
      }
    }
    snapshot.put("activities", activityList);
    return snapshot;
  }

  /** 业务活动/联络任务只需要商机摘要，不应把同商机其他活动带入快照。 */
  private Map<String, Object> buildOpportunitySummaryData(Long opportunityId) {
    if (opportunityId == null) {
      return null;
    }
    SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(opportunityId);
    if (opportunity == null) {
      return null;
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("opportunityId", opportunity.getId());
    summary.put("opportunityName", opportunity.getOpportunityName());
    summary.put("stage", opportunity.getStage());
    summary.put("stageName", stageName(opportunity.getStage()));
    summary.put("amount", opportunity.getAmount());
    summary.put("expectedCloseDate", opportunity.getExpectedCloseDate());
    summary.put("source", opportunity.getSource());
    summary.put("description", opportunity.getDescription());
    summary.put("ownerId", opportunity.getOwnerId());
    summary.put("companyId", opportunity.getCompanyId());
    summary.put(
        "companyName",
        opportunity.getCompanyId() == null
            ? null
            : dataConvertService.getCompanyName(opportunity.getCompanyId()));
    summary.put("contactId", opportunity.getContactId());
    summary.put(
        "contactName",
        opportunity.getContactId() == null
            ? null
            : dataConvertService.getContactName(opportunity.getContactId()));
    return summary;
  }

  /** 联络任务快照中的活动必须来自 task_id 的明确关联，而不是按商机反查全部活动。 */
  private List<Map<String, Object>> activitySnapshotList(List<BusinessActivityEntity> activities) {
    if (activities == null || activities.isEmpty()) {
      return Collections.emptyList();
    }
    List<Long> activityIds =
        activities.stream().map(BusinessActivityEntity::getId).filter(Objects::nonNull).toList();
    Map<Long, List<ApprovalAttachmentVO>> attachmentMap = new HashMap<>();
    if (!activityIds.isEmpty()) {
      List<ApprovalAttachmentVO> attachments =
          Optional.ofNullable(
                  approvalAttachmentService.getByAndIds(activityIds, ModelName.BUSINESS_ACTIVITY))
              .orElseGet(Collections::emptyList);
      attachmentMap =
          attachments.stream().collect(Collectors.groupingBy(ApprovalAttachmentVO::getAndId));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (BusinessActivityEntity activity : activities) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("activityId", activity.getId());
      item.put("activityTitle", activity.getActivityTitle());
      item.put("activityType", activity.getActivityType());
      item.put("activityContent", activity.getActivityContent());
      item.put("activityTime", activity.getActivityTime());
      item.put("activityDuration", activity.getActivityDuration());
      item.put("companyId", activity.getCompanyId());
      item.put("opportunityId", activity.getOpportunityId());
      item.put("creatorId", activity.getCreatorId());
      item.put("remark", activity.getRemark());
      item.put("taskId", activity.getTaskId());
      item.put(
          "attachments",
          attachmentMetadata(
              attachmentMap.getOrDefault(activity.getId(), Collections.emptyList())));
      result.add(item);
    }
    return result;
  }

  /**
   * 按来源模型构建终态快照。
   *
   * <p>审批推进是商机级协助，保留整条商机的活动范围；业务活动和联络任务只冻结 当前来源记录及明确关联的活动，不能因为同一商机而扩大到其他活动。
   *
   * <p>附件只保存 ID、来源模型、名称等元数据，不把下载 URL 写入数据库。协助交付物 属于 assist_request 本身，始终通过实时交付物接口查询，因此不会进入该快照。
   */
  @SuppressWarnings(
      "PMD.AvoidCatchingGenericException") // 快照构建混调 resolver+objectMapper 多源，非业务异常统一包装上抛
  private String buildRecordSnapshot(AssistRequestEntity assist) {
    try {
      AssistRelatedRecordVO related = resolveRelatedRecord(assist);
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("version", 2);
      snapshot.put("capturedAt", LocalDateTime.now());
      snapshot.put("modelName", assist.getModelName());
      snapshot.put("recordId", assist.getRecordId());

      Map<String, Object> relatedSnapshot = new LinkedHashMap<>();
      relatedSnapshot.put("opportunityId", related.getOpportunityId());
      relatedSnapshot.put("opportunityName", related.getOpportunityName());
      relatedSnapshot.put("companyId", related.getCompanyId());
      relatedSnapshot.put("companyName", related.getCompanyName());
      relatedSnapshot.put("contactId", related.getContactId());
      relatedSnapshot.put("contactName", related.getContactName());
      snapshot.put("related", relatedSnapshot);
      snapshot.put("opportunity", null);

      Map<String, Object> record = new LinkedHashMap<>();
      switch (assist.getModelName()) {
        case ModelName.SALES_STAGE_APPROVAL -> {
          SalesStageApprovalEntity approval =
              salesStageApprovalMapper.selectById(assist.getRecordId());
          // 审批推进是商机级协助：只有这里才冻结商机下全部活动及活动附件。
          snapshot.put("opportunity", buildOpportunitySnapshotData(related.getOpportunityId()));
          record.put("type", "salesStageApproval");
          record.put("approvalId", assist.getRecordId());
          record.put("opportunityId", approval == null ? null : approval.getOpportunityId());
          record.put("currentStage", approval == null ? null : approval.getCurrentStage());
          record.put("targetStage", approval == null ? null : approval.getTargetStage());
          record.put("approvalStatus", approval == null ? null : approval.getApprovalStatus());
          record.put("message", approval == null ? null : approval.getMessage());
          record.put("approvalOpinion", approval == null ? null : approval.getApprovalOpinion());
          record.put("applyTime", approval == null ? null : approval.getApplyTime());
          record.put("approvalTime", approval == null ? null : approval.getApprovalTime());
          record.put(
              "attachments",
              attachmentMetadataForAssist(
                  assist.getRecordId(), ModelName.APPROVAL_ATTACHMENT, assist.getId()));
        }
        case ModelName.BUSINESS_ACTIVITY -> {
          BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
          snapshot.put("opportunity", buildOpportunitySummaryData(related.getOpportunityId()));
          record.put("type", "businessActivity");
          record.put("activityId", assist.getRecordId());
          record.put("title", activity == null ? null : activity.getActivityTitle());
          record.put("activityType", activity == null ? null : activity.getActivityType());
          record.put("content", activity == null ? null : activity.getActivityContent());
          record.put("time", activity == null ? null : activity.getActivityTime());
          record.put("activityDuration", activity == null ? null : activity.getActivityDuration());
          record.put("companyId", activity == null ? null : activity.getCompanyId());
          record.put("opportunityId", activity == null ? null : activity.getOpportunityId());
          record.put("creatorId", activity == null ? null : activity.getCreatorId());
          record.put("remark", activity == null ? null : activity.getRemark());
          record.put("taskId", activity == null ? null : activity.getTaskId());
          record.put(
              "attachments",
              attachmentMetadataForAssist(
                  assist.getRecordId(), ModelName.BUSINESS_ACTIVITY, assist.getId()));
        }
        case ModelName.CONTACT_TASK -> {
          ContactTaskEntity task = contactTaskMapper.selectById(assist.getRecordId());
          snapshot.put("opportunity", buildOpportunitySummaryData(related.getOpportunityId()));
          record.put("type", "contactTask");
          record.put("taskId", assist.getRecordId());
          record.put("title", task == null ? null : task.getTaskTitle());
          record.put("taskType", task == null ? null : task.getTaskType());
          record.put("content", task == null ? null : task.getTaskContent());
          record.put("startTime", task == null ? null : toLocalDateTime(task.getStartTime()));
          record.put("endTime", task == null ? null : toLocalDateTime(task.getEndTime()));
          record.put("companyId", task == null ? null : task.getCompanyId());
          record.put("contactId", task == null ? null : task.getContactId());
          record.put("opportunityId", task == null ? null : task.getOpportunityId());
          record.put("priority", task == null ? null : task.getPriority());
          record.put("status", task == null ? null : task.getStatus());
          record.put("assigneeId", task == null ? null : task.getAssigneeId());
          record.put("assignerId", task == null ? null : task.getAssignerId());
          record.put("creatorId", task == null ? null : task.getCreatorId());
          record.put(
              "attachments",
              attachmentMetadataForAssist(
                  assist.getRecordId(), ModelName.CONTACT_TASK, assist.getId()));
          List<BusinessActivityEntity> linkedActivities =
              businessActivityMapper.selectByTaskId(assist.getRecordId());
          record.put("relatedActivities", activitySnapshotList(linkedActivities));
        }
        default -> throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "暂不支持该协助来源的快照");
      }
      snapshot.put("record", record);
      String serialized = objectMapper.writeValueAsString(snapshot);
      if (serialized == null || serialized.isBlank()) {
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "协助历史快照写入失败");
      }
      return serialized;
    } catch (Exception e) {
      log.warn("协助终态快照构建失败，assistId={}", assist.getId(), e);
      if (e instanceof BaseException baseException) {
        throw baseException;
      }
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "协助历史快照写入失败");
    }
  }

  /** 查询一个来源记录下的附件，并转换为可冻结在快照中的最小元数据。 */
  private List<Map<String, Object>> attachmentMetadata(Long recordId, String modelName) {
    List<ApprovalAttachmentVO> attachments =
        approvalAttachmentService.getByAndIds(Collections.singletonList(recordId), modelName);
    return attachmentMetadata(attachments);
  }

  private List<Map<String, Object>> attachmentMetadataForAssist(
      Long recordId, String modelName, Long assistId) {
    List<ApprovalAttachmentVO> attachments =
        approvalAttachmentService.getByAndIdsForAssist(
            Collections.singletonList(recordId), modelName, assistId);
    return attachmentMetadata(attachments);
  }

  /** 快照必须同时记录附件 ID、所属模型和所属记录：历史下载时三者共同用于确认附件确实属于该次协助。 */
  private List<Map<String, Object>> attachmentMetadata(List<ApprovalAttachmentVO> attachments) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (ApprovalAttachmentVO attachment :
        attachments == null ? Collections.<ApprovalAttachmentVO>emptyList() : attachments) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("attachmentId", attachment.getId());
      item.put("modelName", attachment.getModelName());
      item.put("recordId", attachment.getAndId());
      item.put("fileName", attachment.getFileName());
      item.put("fileSize", attachment.getFileSize());
      item.put("fileType", attachment.getFileType());
      item.put("uploaderName", attachment.getUploaderName());
      result.add(item);
    }
    return result;
  }

  private String stageName(Integer stage) {
    String result = null;
    if (stage != null) {
      result =
          switch (stage) {
            case 0 -> "种子";
            case 1 -> "潜在商机";
            case 2 -> "确认商机";
            case 3 -> "储备项目";
            case 4 -> "立项签约";
            case 5 -> "关闭";
            default -> "未知阶段";
          };
    }
    return result;
  }

  @Override
  public Page<AssistVO> pageMyAssists(Integer pageNum, Integer pageSize, Integer assistStatus) {
    Page<AssistRequestEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<AssistRequestEntity> wrapper =
        new LambdaQueryWrapper<AssistRequestEntity>()
            .eq(AssistRequestEntity::getAssistUserId, BaseUnit.getCurrentId())
            .orderByDesc(AssistRequestEntity::getCreateTime);
    if (assistStatus != null) {
      wrapper.eq(AssistRequestEntity::getAssistStatus, assistStatus);
    }
    Page<AssistRequestEntity> pageResult = page(page, wrapper);

    Page<AssistVO> ans =
        new Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());
    if (pageResult.getRecords().isEmpty()) {
      ans.setRecords(Collections.emptyList());
      return ans;
    }

    Set<Long> userIds = new HashSet<>();
    pageResult
        .getRecords()
        .forEach(
            e -> {
              userIds.add(e.getApplicantId());
              userIds.add(e.getAssistUserId());
            });
    Map<Long, UserEntity> userMap =
        userIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    Set<Long> deptIds =
        userMap.values().stream()
            .map(UserEntity::getDeptId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> deptNameMap =
        deptIds.isEmpty() ? Collections.emptyMap() : dataConvertService.getDeptNames(deptIds);

    List<AssistVO> voList =
        pageResult.getRecords().stream()
            .map(
                entity -> {
                  AssistVO vo = new AssistVO();
                  vo.setId(entity.getId());
                  vo.setModelName(entity.getModelName());
                  vo.setRecordId(entity.getRecordId());
                  vo.setApplicantId(entity.getApplicantId());
                  vo.setApplyPurpose(entity.getApplyPurpose());
                  vo.setApplyRequirement(entity.getApplyRequirement());
                  vo.setAssistUserId(entity.getAssistUserId());
                  vo.setAssistStatus(entity.getAssistStatus());
                  vo.setAssistContent(entity.getAssistContent());
                  vo.setRejectReason(entity.getRejectReason());
                  vo.setParentId(entity.getParentId());
                  vo.setAssistTime(entity.getAssistTime());
                  vo.setCreateTime(entity.getCreateTime());

                  UserEntity applicant = userMap.get(entity.getApplicantId());
                  UserEntity assistUser = userMap.get(entity.getAssistUserId());
                  vo.setApplicantName(applicant != null ? applicant.getRealName() : null);
                  vo.setAssistUserName(assistUser != null ? assistUser.getRealName() : null);
                  vo.setAssistUserDeptName(
                      assistUser != null && assistUser.getDeptId() != null
                          ? deptNameMap.get(assistUser.getDeptId())
                          : null);
                  return vo;
                })
            .collect(Collectors.toList());

    fillRecordContent(voList);
    ans.setRecords(voList);
    return ans;
  }

  /**
   * 为待我协助列表填充关联业务记录摘要（标题/内容/时间）， 供协助人在处理前查看具体业务内容
   *
   * @param voList 协助VO列表
   */
  private void fillRecordContent(List<AssistVO> voList) {
    if (voList == null || voList.isEmpty()) {
      return;
    }
    Map<String, Map<Long, RecordBrief>> briefsByModel = new HashMap<>();

    // 订单推进审批：商机名称 + 审批备注 + 申请时间
    Set<Long> approvalIds = collectRecordIds(voList, ModelName.SALES_STAGE_APPROVAL);
    if (!approvalIds.isEmpty()) {
      Map<Long, RecordBrief> map = new HashMap<>();
      // 批量查询关联商机名称，避免逐条 N+1
      Set<Long> opportunityIds = new HashSet<>();
      List<SalesStageApprovalEntity> approvals =
          salesStageApprovalMapper.selectBatchIds(approvalIds);
      for (SalesStageApprovalEntity approval : approvals) {
        if (approval.getOpportunityId() != null) {
          opportunityIds.add(approval.getOpportunityId());
        }
      }
      Map<Long, SalesOpportunityEntity> opportunityMap =
          opportunityIds.isEmpty()
              ? Collections.emptyMap()
              : salesOpportunityMapper.selectBatchIds(opportunityIds).stream()
                  .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));
      for (SalesStageApprovalEntity approval : approvals) {
        SalesOpportunityEntity opportunity =
            approval.getOpportunityId() == null
                ? null
                : opportunityMap.get(approval.getOpportunityId());
        String opportunityName = opportunity == null ? null : opportunity.getOpportunityName();
        StringBuilder content = new StringBuilder();
        if (opportunityName != null && !opportunityName.isEmpty()) {
          content.append("商机：").append(opportunityName);
        }
        if (approval.getMessage() != null && !approval.getMessage().isEmpty()) {
          if (content.length() > 0) {
            content.append("；");
          }
          content.append("备注：").append(approval.getMessage());
        }
        map.put(
            approval.getId(),
            new RecordBrief(
                "销售阶段推进审批",
                content.toString(),
                approval.getApplyTime(),
                approval.getOpportunityId(),
                opportunityName,
                opportunity == null ? null : opportunity.getCompanyId(),
                opportunity == null ? null : opportunity.getContactId()));
      }
      briefsByModel.put(ModelName.SALES_STAGE_APPROVAL, map);
    }

    // 业务活动：活动标题 + 活动内容 + 活动时间
    Set<Long> activityIds = collectRecordIds(voList, ModelName.BUSINESS_ACTIVITY);
    if (!activityIds.isEmpty()) {
      Map<Long, RecordBrief> map = new HashMap<>();
      List<BusinessActivityEntity> activities = businessActivityMapper.selectBatchIds(activityIds);
      Set<Long> opportunityIds =
          activities.stream()
              .map(BusinessActivityEntity::getOpportunityId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      Map<Long, SalesOpportunityEntity> opportunityMap =
          opportunityIds.isEmpty()
              ? Collections.emptyMap()
              : salesOpportunityMapper.selectBatchIds(opportunityIds).stream()
                  .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));
      for (BusinessActivityEntity activity : activities) {
        SalesOpportunityEntity opportunity =
            activity.getOpportunityId() == null
                ? null
                : opportunityMap.get(activity.getOpportunityId());
        map.put(
            activity.getId(),
            new RecordBrief(
                "业务活动：" + (activity.getActivityTitle() == null ? "" : activity.getActivityTitle()),
                activity.getActivityContent(),
                activity.getActivityTime(),
                activity.getOpportunityId(),
                opportunity == null ? null : opportunity.getOpportunityName(),
                activity.getCompanyId() != null
                    ? activity.getCompanyId()
                    : opportunity == null ? null : opportunity.getCompanyId(),
                opportunity == null ? null : opportunity.getContactId()));
      }
      briefsByModel.put(ModelName.BUSINESS_ACTIVITY, map);
    }

    // 联络任务：任务标题 + 任务内容 + 结束时间
    Set<Long> taskIds = collectRecordIds(voList, ModelName.CONTACT_TASK);
    if (!taskIds.isEmpty()) {
      Map<Long, RecordBrief> map = new HashMap<>();
      List<ContactTaskEntity> tasks = contactTaskMapper.selectBatchIds(taskIds);
      Set<Long> opportunityIds =
          tasks.stream()
              .map(ContactTaskEntity::getOpportunityId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      Map<Long, SalesOpportunityEntity> opportunityMap =
          opportunityIds.isEmpty()
              ? Collections.emptyMap()
              : salesOpportunityMapper.selectBatchIds(opportunityIds).stream()
                  .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));
      for (ContactTaskEntity task : tasks) {
        SalesOpportunityEntity opportunity =
            task.getOpportunityId() == null ? null : opportunityMap.get(task.getOpportunityId());
        map.put(
            task.getId(),
            new RecordBrief(
                "联络任务：" + (task.getTaskTitle() == null ? "" : task.getTaskTitle()),
                task.getTaskContent(),
                toLocalDateTime(task.getEndTime()),
                task.getOpportunityId(),
                opportunity == null ? null : opportunity.getOpportunityName(),
                task.getCompanyId() != null
                    ? task.getCompanyId()
                    : opportunity == null ? null : opportunity.getCompanyId(),
                task.getContactId() != null
                    ? task.getContactId()
                    : opportunity == null ? null : opportunity.getContactId()));
      }
      briefsByModel.put(ModelName.CONTACT_TASK, map);
    }

    for (AssistVO vo : voList) {
      if (vo.getModelName() == null || vo.getRecordId() == null) {
        continue;
      }
      Map<Long, RecordBrief> modelBriefs = briefsByModel.get(vo.getModelName());
      RecordBrief brief = modelBriefs == null ? null : modelBriefs.get(vo.getRecordId());
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

    // 批量填充公司/联系人名称
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

  private Set<Long> collectRecordIds(List<AssistVO> voList, String modelName) {
    Set<Long> ids = new HashSet<>();
    for (AssistVO vo : voList) {
      if (modelName.equals(vo.getModelName()) && vo.getRecordId() != null) {
        ids.add(vo.getRecordId());
      }
    }
    return ids;
  }

  private LocalDateTime toLocalDateTime(java.util.Date date) {
    return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
  }

  /** 业务记录摘要 */
  private static class RecordBrief {
    private final String title;
    private final String content;
    private final LocalDateTime time;
    private final Long opportunityId;
    private final String opportunityName;
    private final Long companyId;
    private final Long contactId;

    private RecordBrief(
        String title,
        String content,
        LocalDateTime time,
        Long opportunityId,
        String opportunityName,
        Long companyId,
        Long contactId) {
      this.title = title;
      this.content = content;
      this.time = time;
      this.opportunityId = opportunityId;
      this.opportunityName = opportunityName;
      this.companyId = companyId;
      this.contactId = contactId;
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteByRecords(String modelName, List<Long> recordIds) {
    if (recordIds == null || recordIds.isEmpty()) {
      return;
    }
    List<Long> assistIds =
        list(
                new LambdaQueryWrapper<AssistRequestEntity>()
                    .select(AssistRequestEntity::getId)
                    .eq(AssistRequestEntity::getModelName, modelName)
                    .in(AssistRequestEntity::getRecordId, recordIds))
            .stream()
            .map(AssistRequestEntity::getId)
            .toList();
    // 协助交付物的路径归属的是协助记录。先删文件和元数据，再删协助本身，
    // 避免源业务删除后留下无法访问的附件孤儿记录。
    approvalAttachmentService.removeByAndIds(assistIds, ModelName.ASSIST_REQUEST);
    remove(
        new LambdaQueryWrapper<AssistRequestEntity>()
            .eq(AssistRequestEntity::getModelName, modelName)
            .in(AssistRequestEntity::getRecordId, recordIds));
  }

  @Override
  public Page<AssistVO> pageMyApplications(
      Integer pageNum, Integer pageSize, Integer assistStatus) {
    Page<AssistRequestEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<AssistRequestEntity> wrapper =
        new LambdaQueryWrapper<AssistRequestEntity>()
            .eq(AssistRequestEntity::getApplicantId, BaseUnit.getCurrentId())
            .orderByDesc(AssistRequestEntity::getCreateTime);
    if (assistStatus != null) {
      wrapper.eq(AssistRequestEntity::getAssistStatus, assistStatus);
    }
    Page<AssistRequestEntity> pageResult = page(page, wrapper);

    Page<AssistVO> ans =
        new Page<>(pageResult.getCurrent(), pageResult.getSize(), pageResult.getTotal());
    if (pageResult.getRecords().isEmpty()) {
      ans.setRecords(Collections.emptyList());
      return ans;
    }

    Set<Long> userIds = new HashSet<>();
    pageResult
        .getRecords()
        .forEach(
            e -> {
              userIds.add(e.getApplicantId());
              userIds.add(e.getAssistUserId());
            });
    Map<Long, UserEntity> userMap =
        userIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    Set<Long> deptIds =
        userMap.values().stream()
            .map(UserEntity::getDeptId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> deptNameMap =
        deptIds.isEmpty() ? Collections.emptyMap() : dataConvertService.getDeptNames(deptIds);

    List<AssistVO> voList =
        pageResult.getRecords().stream()
            .map(
                entity -> {
                  AssistVO vo = new AssistVO();
                  vo.setId(entity.getId());
                  vo.setModelName(entity.getModelName());
                  vo.setRecordId(entity.getRecordId());
                  vo.setApplicantId(entity.getApplicantId());
                  vo.setApplyPurpose(entity.getApplyPurpose());
                  vo.setApplyRequirement(entity.getApplyRequirement());
                  vo.setAssistUserId(entity.getAssistUserId());
                  vo.setAssistStatus(entity.getAssistStatus());
                  vo.setAssistContent(entity.getAssistContent());
                  vo.setRejectReason(entity.getRejectReason());
                  vo.setParentId(entity.getParentId());
                  vo.setAssistTime(entity.getAssistTime());
                  vo.setCreateTime(entity.getCreateTime());

                  UserEntity applicant = userMap.get(entity.getApplicantId());
                  UserEntity assistUser = userMap.get(entity.getAssistUserId());
                  vo.setApplicantName(applicant != null ? applicant.getRealName() : null);
                  vo.setAssistUserName(assistUser != null ? assistUser.getRealName() : null);
                  vo.setAssistUserDeptName(
                      assistUser != null && assistUser.getDeptId() != null
                          ? deptNameMap.get(assistUser.getDeptId())
                          : null);
                  return vo;
                })
            .collect(Collectors.toList());

    fillRecordContent(voList);
    ans.setRecords(voList);
    return ans;
  }

  @Override
  public AssistVO getDetail(Long id) {
    AssistRequestEntity entity = requireVisibleAssist(id, false);

    List<AssistVO> vos =
        listAssistsByRecords(
            entity.getModelName(), Collections.singletonList(entity.getRecordId()));
    AssistVO vo =
        vos.stream()
            .filter(v -> Objects.equals(v.getId(), id))
            .findFirst()
            .orElseThrow(() -> new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在"));

    if (Objects.equals(entity.getAssistStatus(), 0)) {
      // 待协助可看到最新进展，因此回填实时摘要；完整商机快照只在进入终态时构建并持久化。
      fillRecordContent(Collections.singletonList(vo));
    } else {
      // 历史终态绝不通过实时业务回填。只在冻结 JSON 上补短期下载链接，旧历史缺快照时交给客户端明确提示。
      vo.setRecordSnapshot(
          hydrateHistoricalAttachmentLinks(entity.getRecordSnapshot(), entity.getId()));
      vo.setSnapshotMissing(
          entity.getRecordSnapshot() == null || entity.getRecordSnapshot().isBlank());
    }
    return vo;
  }

  /**
   * 在内存中给终态快照补充短期下载链接，不修改数据库中的原始快照。
   *
   * <p>这里不查询来源任务、活动、审批来重建快照，保证协助结束后看到的业务内容不漂移。 下载接口仍会再次验证附件记录与物理文件是否存在，因此文件之后被删除时链接自然失效。
   */
  @SuppressWarnings({"unchecked", "PMD.AvoidCatchingGenericException"}) // 快照解析递归签发令牌多源，失败回落原快照
  private String hydrateHistoricalAttachmentLinks(String snapshot, Long assistId) {
    if (snapshot == null || snapshot.isBlank() || assistId == null) {
      return snapshot;
    }
    try {
      Object root = objectMapper.readValue(snapshot, Object.class);
      if (!(root instanceof Map<?, ?>)) {
        return snapshot;
      }
      addHistoricalAttachmentLinks(root, assistId, null);
      return objectMapper.writeValueAsString(root);
    } catch (Exception exception) {
      log.warn("协助历史附件链接生成失败，assistId={}", assistId, exception);
      return snapshot;
    }
  }

  /**
   * 递归遍历快照中的对象和数组，找到 {@code attachmentId} 后签发历史下载令牌。
   *
   * <p>{@code inheritedModelName} 用于处理旧版本快照：旧快照可能没有在每个附件上保存 {@code
   * modelName}，但其父节点已能确定来源模型；交付物则固定属于 {@code ASSIST_REQUEST}。
   */
  @SuppressWarnings("unchecked")
  private void addHistoricalAttachmentLinks(Object node, Long assistId, String inheritedModelName) {
    if (node instanceof Map<?, ?> rawMap) {
      Map<String, Object> map = (Map<String, Object>) rawMap;
      String modelName = attachmentModelName(map, inheritedModelName);
      Long attachmentId = longValue(map.get("attachmentId"));
      if (attachmentId != null && modelName != null) {
        Long currentUserId = BaseUnit.getCurrentId();
        String token =
            downloadTokenUtil.generateDownloadToken(
                attachmentId, currentUserId, "approval_attachment", modelName, assistId);
        map.put("downloadUrl", attachmentBaseUrl() + "/public/attachment/download?token=" + token);
      }
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        // 交付物不属于历史业务快照；终态交付物统一走 /assist/{id}/attachments 实时查询。
        if ("deliveryAttachments".equals(entry.getKey())) {
          continue;
        }
        String childModelName = modelName;
        addHistoricalAttachmentLinks(entry.getValue(), assistId, childModelName);
      }
    } else if (node instanceof Iterable<?> values) {
      for (Object value : values) {
        addHistoricalAttachmentLinks(value, assistId, inheritedModelName);
      }
    }
  }

  /** 优先读取新快照中的 modelName；若是旧快照则从记录 type 或 activityId 推断模型， 保证存量历史记录也能使用新的下载链路。 */
  private String attachmentModelName(Map<String, Object> map, String inheritedModelName) {
    String explicitModelName = stringValue(map.get("modelName"));
    if (explicitModelName != null) {
      return attachmentModelForSource(explicitModelName);
    }
    String recordType = stringValue(map.get("type"));
    if (recordType != null) {
      return switch (recordType) {
        case "salesStageApproval" -> ModelName.APPROVAL_ATTACHMENT;
        case "businessActivity" -> ModelName.BUSINESS_ACTIVITY;
        case "contactTask" -> ModelName.CONTACT_TASK;
        default -> inheritedModelName;
      };
    }
    return map.containsKey("activityId") ? ModelName.BUSINESS_ACTIVITY : inheritedModelName;
  }

  /** 审批业务的来源模型名与附件表使用的模型名不同，需要在签发令牌前转换。 */
  private String attachmentModelForSource(String modelName) {
    return ModelName.SALES_STAGE_APPROVAL.equals(modelName)
        ? ModelName.APPROVAL_ATTACHMENT
        : modelName;
  }

  // Jackson 反序列化旧快照后，数字可能是 Number 或 String；统一转换以兼容两种格式。
  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String stringValue) {
      try {
        return Long.valueOf(stringValue);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private String stringValue(Object value) {
    return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
  }

  /** 下载地址只需要当前应用的 context path，不拼 host，前端会按当前服务域名发起请求。 */
  private String attachmentBaseUrl() {
    String contextPath = httpRequest.getContextPath();
    return contextPath == null ? "" : contextPath;
  }

  @Override
  public AssistRelatedRecordVO getRelatedRecord(Long id) {
    return resolveRelatedRecord(requireVisibleAssist(id, true));
  }

  @Override
  public SalesStageApprovalVO getRelatedApproval(Long id) {
    AssistRequestEntity assist = requireVisibleAssist(id, true);
    requireModel(assist, ModelName.SALES_STAGE_APPROVAL);

    SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(assist.getRecordId());
    if (approval == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "审批记录不存在或已删除");
    }

    SalesOpportunityEntity opportunity =
        approval.getOpportunityId() == null
            ? null
            : salesOpportunityMapper.selectById(approval.getOpportunityId());
    SalesStageApprovalVO vo =
        SalesStageApprovalVO.fromEntity(
            approval,
            opportunity == null ? null : opportunity.getOpportunityName(),
            stageName(approval.getCurrentStage()),
            stageName(approval.getTargetStage()),
            dataConvertService.getUserName(approval.getApproverId()));
    return vo;
  }

  @Override
  public BusinessActivityVO getRelatedActivity(Long id) {
    AssistRequestEntity assist = requireVisibleAssist(id, true);
    requireModel(assist, ModelName.BUSINESS_ACTIVITY);

    BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
    if (activity == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS, "业务活动不存在或已删除");
    }
    BusinessActivityVO vo =
        BusinessActivityVO.fromEntity(
            activity,
            dataConvertService.getUserName(activity.getCreatorId()),
            dataConvertService.getOpportunityName(activity.getOpportunityId()));
    return vo;
  }

  @Override
  public ContactTaskVO getRelatedTask(Long id) {
    AssistRequestEntity assist = requireVisibleAssist(id, true);
    Long taskId;
    if (Objects.equals(assist.getModelName(), ModelName.CONTACT_TASK)) {
      taskId = assist.getRecordId();
    } else if (Objects.equals(assist.getModelName(), ModelName.BUSINESS_ACTIVITY)) {
      BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
      taskId = activity == null ? null : activity.getTaskId();
    } else {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源没有关联联络任务");
    }

    if (taskId == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "该业务活动未关联联络任务");
    }
    ContactTaskEntity task = contactTaskMapper.selectById(taskId);
    if (task == null) {
      throw new BaseException(ErrorCode.DATA_NULL, "联络任务不存在或已删除");
    }
    ContactTaskVO vo =
        ContactTaskVO.fromEntity(
            task,
            dataConvertService.getCompanyName(task.getCompanyId()),
            dataConvertService.getContactName(task.getContactId()),
            dataConvertService.getOpportunityName(task.getOpportunityId()),
            dataConvertService.getUserName(task.getAssigneeId()),
            dataConvertService.getUserName(task.getAssignerId()),
            dataConvertService.getUserName(task.getCreatorId()));
    return vo;
  }

  private void requireModel(AssistRequestEntity assist, String expectedModelName) {
    if (!Objects.equals(expectedModelName, assist.getModelName())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助来源与请求详情类型不匹配");
    }
  }

  @Override
  public List<ApprovalAttachmentVO> getRelatedActivityAttachments(Long id, Long activityId) {
    if (activityId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity assist =
        requirePendingAssistSource(id, ModelName.BUSINESS_ACTIVITY, BaseUnit.getCurrentId());
    BusinessActivityEntity activity = businessActivityMapper.selectById(activityId);
    if (activity == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "业务活动不存在");
    }
    if (!Objects.equals(assist.getRecordId(), activityId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "该业务活动不属于本次协助关联范围");
    }
    return approvalAttachmentService.getByAndIdsForAssist(
        Collections.singletonList(activityId), ModelName.BUSINESS_ACTIVITY, id);
  }

  @Override
  public List<ApprovalAttachmentVO> getRelatedTaskAttachments(Long id) {
    AssistRequestEntity assist = requirePendingTaskAssistParticipant(id, BaseUnit.getCurrentId());
    return approvalAttachmentService.getByAndIdsForAssist(
        Collections.singletonList(assist.getRecordId()), ModelName.CONTACT_TASK, id);
  }

  /** 协助专用删除不复用普通删除授权：前者的授权依据是本条待协助记录， 但仍把 modelName 和 recordId 一并传给附件服务，防止把其他业务记录的附件 ID 混入删除请求。 */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public com.slz.crm.pojo.vo.AttachmentDeleteResultVO deleteRelatedAttachments(
      Long id, List<Long> attachmentIds) {
    AssistRequestEntity assist = requirePendingAssistSource(id, null, BaseUnit.getCurrentId());
    String sourceModel = assist.getModelName();
    if (!ModelName.BUSINESS_ACTIVITY.equals(sourceModel)
        && !ModelName.CONTACT_TASK.equals(sourceModel)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源不支持删除业务附件");
    }
    return approvalAttachmentService.removeSourceAttachments(
        attachmentIds, sourceModel, assist.getRecordId());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void uploadRelatedAttachments(
      Long id, List<com.slz.crm.pojo.dto.ApprovalAttachmentDTO> attachments) {
    AssistRequestEntity assist = getById(id);
    if (assist != null && ModelName.CONTACT_TASK.equals(assist.getModelName())) {
      assist = requirePendingTaskAssistParticipant(id, BaseUnit.getCurrentId());
    } else {
      assist = requirePendingAssistSource(id, null, BaseUnit.getCurrentId());
    }
    if (attachments == null || attachments.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择至少一个附件");
    }
    String sourceModel =
        switch (assist.getModelName()) {
          case ModelName.BUSINESS_ACTIVITY, ModelName.CONTACT_TASK -> assist.getModelName();
          default -> throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源不支持上传业务附件");
        };
    approvalAttachmentService.saveAttachments(assist.getRecordId(), sourceModel, attachments);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void applyAssists(String modelName, Long recordId, List<AssistApplyItem> applyList) {
    if (modelName == null || recordId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    AssistRequestEntity context = new AssistRequestEntity();
    context.setModelName(modelName);
    context.setRecordId(recordId);
    if (!isAdmin && !isCurrentBusinessResponsible(context, currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "只有业务负责人或执行人可以发起协助申请");
    }
    createAssists(modelName, recordId, currentId, applyList);
  }

  private AssistRelatedRecordVO resolveRelatedRecord(AssistRequestEntity assist) {
    AssistRelatedRecordVO vo = assistRelatedRecordResolver.resolve(assist);
    vo.setCompanyName(
        vo.getCompanyId() == null ? null : dataConvertService.getCompanyName(vo.getCompanyId()));
    vo.setContactName(
        vo.getContactId() == null ? null : dataConvertService.getContactName(vo.getContactId()));
    return vo;
  }

  /** 校验协助记录访问权。实时关联详情额外要求记录仍处于待协助状态。 */
  private AssistRequestEntity requireVisibleAssist(Long id, boolean pendingRequired) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    boolean visible =
        isAdmin
            || Objects.equals(entity.getApplicantId(), currentId)
            || Objects.equals(entity.getAssistUserId(), currentId)
            || isCurrentBusinessResponsible(entity, currentId);
    if (!visible) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (pendingRequired && !Objects.equals(entity.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，不再提供实时关联详情");
    }
    return entity;
  }

  /**
   * 专用来源附件的统一闸门。 普通活动/任务页面不调用这里；它们使用普通记录权限。这里额外要求 pending 状态， 让下载令牌、上传和删除都绑定同一个
   * assistId，不会因“同一商机可见”扩大到其他附件。
   */
  @Override
  public AssistRequestEntity requirePendingAssistSource(
      Long assistId, String expectedModelName, Long userId) {
    if (assistId == null || userId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity assist = getById(assistId);
    if (assist == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    UserEntity user = userMapper.selectById(userId);
    if (user == null || !Objects.equals(user.getStatus(), 1)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "当前用户不可访问协助来源附件");
    }
    boolean admin = user != null && Objects.equals(user.getRoleId(), 1L);
    boolean participant =
        admin
            || Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId);
    if (!participant) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "当前用户不是本次协助参与人");
    }
    if (!Objects.equals(assist.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，不再提供实时来源附件");
    }
    if (expectedModelName != null && !Objects.equals(expectedModelName, assist.getModelName())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助来源与请求详情类型不匹配");
    }
    return assist;
  }

  /**
   * 联络任务的协助页面允许任务实际参与人共同补充来源材料。
   *
   * <p>仅覆盖当前待协助记录，且只用于读取、上传任务来源附件；删除仍走 {@link #requirePendingAssistSource(Long, String,
   * Long)}，不会因任务参与关系扩大删除权。
   */
  private AssistRequestEntity requirePendingTaskAssistParticipant(Long assistId, Long userId) {
    if (assistId == null || userId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity assist = getById(assistId);
    if (assist == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    if (!ModelName.CONTACT_TASK.equals(assist.getModelName())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源不是联络任务");
    }
    UserEntity user = userMapper.selectById(userId);
    if (user == null || !Objects.equals(user.getStatus(), 1)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "当前用户不可访问协助来源附件");
    }
    boolean participant =
        Objects.equals(user.getRoleId(), 1L)
            || Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId)
            || isCurrentBusinessResponsible(assist, userId);
    if (!participant) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "当前用户不是本次协助的任务参与人");
    }
    if (!Objects.equals(assist.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，不再提供实时来源附件");
    }
    return assist;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Long reapply(Long originalAssistId, List<AssistApplyItem> applyList) {
    if (originalAssistId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity original = getById(originalAssistId);
    if (original == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "原协助记录不存在");
    }
    Long currentId = BaseUnit.getCurrentId();
    if (!Objects.equals(original.getApplicantId(), currentId)
        && !isCurrentBusinessResponsible(original, currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (!Objects.equals(original.getAssistStatus(), 2)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "仅已驳回的协助申请可以重新申请");
    }
    if (applyList == null || applyList.size() != 1) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "重新申请只能针对原协助人逐条发起");
    }
    AssistApplyItem item = applyList.get(0);
    if (item == null || item.getAssistUserId() == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择协助人");
    }
    if (!Objects.equals(item.getAssistUserId(), original.getAssistUserId())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "重新申请只能发送给原协助人");
    }
    if (Objects.equals(item.getAssistUserId(), currentId)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助人不能是申请人自己");
    }
    String purpose = trimToNull(item.getApplyPurpose());
    String requirement = trimToNull(item.getApplyRequirement());
    if (purpose == null || requirement == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "协作目的与协作要求不能为空");
    }

    // 同一业务的同一协助人只能保留一条待协助记录，避免重复待办。
    long pendingCount =
        count(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getModelName, original.getModelName())
                .eq(AssistRequestEntity::getRecordId, original.getRecordId())
                .eq(AssistRequestEntity::getAssistUserId, original.getAssistUserId())
                .eq(AssistRequestEntity::getAssistStatus, 0));
    if (pendingCount > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助人已有待协助申请，请勿重复发起");
    }
    // parentId 是一条重申请链：应针对最新的驳回记录继续申请，不能回头复用旧节点。
    if (count(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getParentId, originalAssistId))
        > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助申请已有重新申请记录，请针对最新驳回记录重新申请");
    }

    UserEntity assistUser = userMapper.selectById(original.getAssistUserId());
    if (assistUser == null || !Objects.equals(assistUser.getStatus(), 1)) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, "协助人不存在或已冻结/离职：用户ID " + original.getAssistUserId());
    }

    AssistRequestEntity entity = new AssistRequestEntity();
    entity.setModelName(original.getModelName());
    entity.setRecordId(original.getRecordId());
    entity.setApplicantId(currentId);
    entity.setApplyPurpose(purpose);
    entity.setApplyRequirement(requirement);
    entity.setAssistUserId(original.getAssistUserId());
    entity.setAssistStatus(0);
    entity.setPendingKey("PENDING");
    entity.setParentId(originalAssistId);
    entity.setCreateTime(LocalDateTime.now());
    try {
      save(entity);
      assistMessageService.appendSystemMessage(entity.getId(), "已重新发起协助申请");
      return entity.getId();
    } catch (DuplicateKeyException e) {
      // 并发请求可能同时通过上面的查询；唯一索引是最后一道防线。
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助申请已重新发起，请勿重复提交");
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void appendAssists(Long originalAssistId, List<AssistApplyItem> applyList) {
    if (originalAssistId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity original = getById(originalAssistId);
    if (original == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "原协助记录不存在");
    }
    Long currentId = BaseUnit.getCurrentId();
    if (!Objects.equals(original.getApplicantId(), currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "只有原申请人可以追加协助人");
    }
    if (Objects.equals(original.getAssistStatus(), 4)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "已取消的协助申请不能追加协助人");
    }
    if (applyList == null || applyList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "请选择至少一位协助人");
    }

    // 复用统一的在职、重复、目的/要求校验；该方法只新增记录，不会修改原记录。
    createAssists(
        original.getModelName(), original.getRecordId(), original.getApplicantId(), applyList);
  }

  @Override
  public boolean isOperable(String modelName, Long recordId, Long userId) {
    if (modelName == null || recordId == null || userId == null) {
      return false;
    }
    UserEntity currentUser = userMapper.selectById(userId);
    if (currentUser != null && Objects.equals(currentUser.getRoleId(), 1L)) {
      return true;
    }
    // 协助记录本身：申请人/协助人可操作（如上传交付物附件）
    if (ModelName.ASSIST_REQUEST.equals(modelName)) {
      AssistRequestEntity assist = getById(recordId);
      return assist != null
          && (Objects.equals(assist.getApplicantId(), userId)
              || Objects.equals(assist.getAssistUserId(), userId));
    }
    // 申请人或协助人
    Long count =
        count(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getModelName, modelName)
                .eq(AssistRequestEntity::getRecordId, recordId)
                .eq(AssistRequestEntity::getAssistStatus, 0)
                .and(
                    w ->
                        w.eq(AssistRequestEntity::getApplicantId, userId)
                            .or()
                            .eq(AssistRequestEntity::getAssistUserId, userId)));
    if (count != null && count > 0) {
      return true;
    }
    // 模块相关人：审批人/创建人
    return switch (modelName) {
      case ModelName.SALES_STAGE_APPROVAL -> {
        SalesStageApprovalEntity approval = salesStageApprovalMapper.selectById(recordId);
        yield approval != null
            && (Objects.equals(approval.getApplicantId(), userId)
                || Objects.equals(approval.getApproverId(), userId));
      }
      case ModelName.BUSINESS_ACTIVITY -> {
        BusinessActivityEntity activity = businessActivityMapper.selectById(recordId);
        yield activity != null && Objects.equals(activity.getCreatorId(), userId);
      }
      case ModelName.CONTACT_TASK -> {
        ContactTaskEntity task = contactTaskMapper.selectById(recordId);
        yield task != null
            && (Objects.equals(task.getCreatorId(), userId)
                || Objects.equals(task.getAssignerId(), userId)
                || Objects.equals(task.getAssigneeId(), userId));
      }
      default -> false;
    };
  }

  @Override
  public boolean canWriteAssistDelivery(Long assistId, Long userId) {
    if (assistId == null || userId == null) {
      return false;
    }
    UserEntity currentUser = userMapper.selectById(userId);
    if (currentUser == null || !Objects.equals(currentUser.getStatus(), 1)) {
      return false;
    }
    if (Objects.equals(currentUser.getRoleId(), 1L)) {
      return true;
    }
    AssistRequestEntity assist = getById(assistId);
    return assist != null
        && Objects.equals(assist.getAssistStatus(), 0)
        && (Objects.equals(assist.getApplicantId(), userId)
            || Objects.equals(assist.getAssistUserId(), userId));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void cancelPendingByRecord(String modelName, Long recordId, String reason) {
    if (modelName == null || recordId == null) {
      return;
    }
    List<AssistRequestEntity> pending =
        list(
            new LambdaQueryWrapper<AssistRequestEntity>()
                .eq(AssistRequestEntity::getModelName, modelName)
                .eq(AssistRequestEntity::getRecordId, recordId)
                .eq(AssistRequestEntity::getAssistStatus, 0));
    for (AssistRequestEntity entity : pending) {
      // 快照失败会抛异常，整个审批状态更新也随事务回滚。
      entity.setRecordSnapshot(buildRecordSnapshot(entity));
      entity.setAssistStatus(4);
      entity.setPendingKey(null);
      entity.setCancelReason(trimToNull(reason));
      entity.setAssistTime(LocalDateTime.now());
    }
    if (!pending.isEmpty() && !updateBatchById(pending)) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "取消待协助记录失败");
    }
    for (AssistRequestEntity entity : pending) {
      assistMessageService.appendSystemMessage(entity.getId(), "协助已取消：" + entity.getCancelReason());
    }
  }

  /** 历史申请人不可改写；人员交接后由当前业务负责人接管查看和重申请。 */
  private boolean isCurrentBusinessResponsible(AssistRequestEntity assist, Long userId) {
    if (assist == null || userId == null || assist.getRecordId() == null) {
      return false;
    }
    return switch (assist.getModelName()) {
      case ModelName.SALES_STAGE_APPROVAL -> {
        SalesStageApprovalEntity approval =
            salesStageApprovalMapper.selectById(assist.getRecordId());
        SalesOpportunityEntity opportunity =
            approval == null || approval.getOpportunityId() == null
                ? null
                : salesOpportunityMapper.selectById(approval.getOpportunityId());
        yield opportunity != null && Objects.equals(opportunity.getOwnerId(), userId);
      }
      case ModelName.BUSINESS_ACTIVITY -> {
        BusinessActivityEntity activity = businessActivityMapper.selectById(assist.getRecordId());
        yield activity != null
            && (Objects.equals(activity.getCreatorId(), userId)
                || businessActivityUserMapper.existsByActivityIdAndUserId(
                        assist.getRecordId(), userId)
                    > 0);
      }
      case ModelName.CONTACT_TASK -> {
        ContactTaskEntity task = contactTaskMapper.selectById(assist.getRecordId());
        yield task != null
            && (Objects.equals(task.getCreatorId(), userId)
                || Objects.equals(task.getAssignerId(), userId)
                || Objects.equals(task.getAssigneeId(), userId));
      }
      default -> false;
    };
  }
}
