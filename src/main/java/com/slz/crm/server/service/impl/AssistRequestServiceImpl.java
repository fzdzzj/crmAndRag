package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协助申请服务实现。
 *
 * <p>tighten-pmd-residual-325 任务 6.5（F-4 批）：本类原为 1940 行 / 83 方法的巨类（类级 NCSS 830、圈复杂度 442、 35 条
 * OnlyOneReturn，并因「同一聚合根的事务边界不宜被切开」豁免了 TooManyMethods）。本批按职责域把校验、装配、
 * 查询、权限守卫、快照全部拆到同包协作类；本类只保留三件事：接口入口（26 个公共方法）、 {@code @Transactional} 事务边界、协作对象的懒初始化装配。分片 D 任务 4.5
 * 的 TooManyMethods 豁免随拆分一起删除。
 *
 * <p>事务口径（本批硬约束）：{@code @Transactional} 注解只留在本类的公共入口方法上。协作类不带事务注解、 不持有 Spring 自代理、不新开事务；它们经 {@link
 * AssistRequestStore}（由 {@link AssistRequestStoreAdapter} 转交给本类实例）读写协助记录， 与拆分前本类内部的自我调用逐处等价 ——
 * 跨类调用既不新增事务、也不提前提交，传播语义不变。
 *
 * <p>装配口径：协作对象懒初始化（构建后内容不可变，竞态只会产生等价实例）。这样既不改动 Spring 装配面， 也保持既有单测的 {@code @InjectMocks} /
 * {@code @Spy} 用法可用（与 F-3 批C {@code DataScopeServiceImpl} 同法）； 协作类拿到的是注入好的同一批 mapper / service
 * 实例，测试里就是同一批 mock。
 *
 * <p>行为等价基准：{@code AssistRequestSplitEquivalenceTest}（状态机主干 / reapply 幂等与越权 / 快照与内容装配保真）
 * 在拆分前后各跑一遍且逐条绿，与既有 {@code AssistRequestServiceTest} 一起构成本批的等价网。
 */
@Service
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

  @Autowired private AssistRelatedRecordResolver assistRelatedRecordResolver;

  @Autowired private AssistMessageService assistMessageService;

  // 给历史快照中的每个附件临时签发下载令牌，令牌绑定当前查看人和协助记录。
  @Autowired private AttachmentDownloadTokenUtil downloadTokenUtil;

  // 仅用于读取应用的 context path，避免部署在子路径时拼出的下载地址失效。
  @Autowired private HttpServletRequest httpRequest;

  @Autowired private ObjectMapper objectMapper;

  private AssistApplyCoordinator applyCoordinator;

  private AssistReapplyCoordinator reapplyCoordinator;

  private AssistResponsibilityResolver responsibilityResolver;

  private AssistAccessGuard accessGuard;

  private AssistOpportunitySnapshotSupport opportunitySnapshotSupport;

  private AssistSnapshotAssembler snapshotAssembler;

  private AssistSnapshotHistorySupport snapshotHistorySupport;

  private AssistRecordBriefCollector recordBriefCollector;

  private AssistRecordBriefFiller recordBriefFiller;

  private AssistVoAssembler voAssembler;

  private AssistDetailAssembler detailAssembler;

  private AssistSourceAttachmentSupport sourceAttachmentSupport;

  private AssistListQueryCoordinator listQueryCoordinator;

  private AssistRequestStore store;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void createAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList) {
    getApplyCoordinator().createAssists(modelName, recordId, applicantId, applyList);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void updatePendingAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList) {
    getApplyCoordinator().syncPendingAssists(modelName, recordId, applicantId, applyList);
  }

  @Override
  public List<AssistVO> listAssistsByRecords(String modelName, List<Long> recordIds) {
    return getListQueryCoordinator().listAssistsByRecords(modelName, recordIds);
  }

  @Override
  public List<AssistVO> listAssistsByRecord(String modelName, Long recordId) {
    return getListQueryCoordinator().listAssistsByRecord(modelName, recordId);
  }

  @Override
  public Set<Long> getRelatedRecordIdsByUser(String modelName, Long userId) {
    return getListQueryCoordinator().getRelatedRecordIdsByUser(modelName, userId);
  }

  @Override
  public List<AssistVO> getVisibleAssists(String modelName, Long recordId, Long currentUserId) {
    return getListQueryCoordinator().getVisibleAssists(modelName, recordId, currentUserId);
  }

  @Override
  public Boolean handleAssist(AssistHandleDTO dto) {
    return getApplyCoordinator().handleAssist(dto);
  }

  @Override
  public Page<AssistVO> pageMyAssists(Integer pageNum, Integer pageSize, Integer assistStatus) {
    return getListQueryCoordinator()
        .pageMyAssists(
            page(
                new Page<>(pageNum, pageSize),
                AssistRequestQueries.byAssistUser(BaseUnit.getCurrentId(), assistStatus)));
  }

  @Override
  public Page<AssistVO> pageMyApplications(
      Integer pageNum, Integer pageSize, Integer assistStatus) {
    return getListQueryCoordinator()
        .pageMyApplications(
            page(
                new Page<>(pageNum, pageSize),
                AssistRequestQueries.byApplicant(BaseUnit.getCurrentId(), assistStatus)));
  }

  @Override
  public AssistVO getDetail(Long id) {
    AssistRequestEntity entity = getAccessGuard().requireVisibleAssist(id, false);
    return getDetailAssembler()
        .detail(
            entity,
            id,
            listAssistsByRecords(
                entity.getModelName(), Collections.singletonList(entity.getRecordId())));
  }

  @Override
  public AssistRelatedRecordVO getRelatedRecord(Long id) {
    return getSnapshotAssembler()
        .resolveRelatedRecord(getAccessGuard().requireVisibleAssist(id, true));
  }

  @Override
  public SalesStageApprovalVO getRelatedApproval(Long id) {
    return getDetailAssembler().relatedApproval(getAccessGuard().requireVisibleAssist(id, true));
  }

  @Override
  public BusinessActivityVO getRelatedActivity(Long id) {
    return getDetailAssembler().relatedActivity(getAccessGuard().requireVisibleAssist(id, true));
  }

  @Override
  public ContactTaskVO getRelatedTask(Long id) {
    return getDetailAssembler().relatedTask(getAccessGuard().requireVisibleAssist(id, true));
  }

  @Override
  public List<ApprovalAttachmentVO> getRelatedActivityAttachments(Long id, Long activityId) {
    return getSourceAttachmentSupport().relatedActivityAttachments(id, activityId);
  }

  @Override
  public List<ApprovalAttachmentVO> getRelatedTaskAttachments(Long id) {
    return getSourceAttachmentSupport().relatedTaskAttachments(id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AttachmentDeleteResultVO deleteRelatedAttachments(Long id, List<Long> attachmentIds) {
    return getSourceAttachmentSupport().deleteRelatedAttachments(id, attachmentIds);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void uploadRelatedAttachments(Long id, List<ApprovalAttachmentDTO> attachments) {
    getSourceAttachmentSupport().uploadRelatedAttachments(id, attachments);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void applyAssists(String modelName, Long recordId, List<AssistApplyItem> applyList) {
    getApplyCoordinator().applyAssists(modelName, recordId, applyList);
  }

  @Override
  public AssistRequestEntity requirePendingAssistSource(
      Long assistId, String expectedModelName, Long userId) {
    return getAccessGuard().requirePendingAssistSource(assistId, expectedModelName, userId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Long reapply(Long originalAssistId, List<AssistApplyItem> applyList) {
    return getReapplyCoordinator().reapply(originalAssistId, applyList);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void appendAssists(Long originalAssistId, List<AssistApplyItem> applyList) {
    getApplyCoordinator().appendAssists(originalAssistId, applyList);
  }

  @Override
  public boolean isOperable(String modelName, Long recordId, Long userId) {
    return getResponsibilityResolver().canOperate(modelName, recordId, userId);
  }

  @Override
  public boolean canWriteAssistDelivery(Long assistId, Long userId) {
    return getResponsibilityResolver().canWriteDelivery(assistId, userId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void cancelPendingByRecord(String modelName, Long recordId, String reason) {
    getApplyCoordinator().cancelPendingByRecord(modelName, recordId, reason);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteByRecords(String modelName, List<Long> recordIds) {
    getListQueryCoordinator().deleteByRecords(modelName, recordIds);
  }

  // ===== 协作对象懒初始化 =====
  // 每个 getXxx() 都只有一个返回语句：TooManyMethods 的判定把「get/set/is 前缀且语句数 <= 1」的方法排除在外，
  // 因此这些装配点不会把本类的方法数重新推回阈值以上；已构建的对象内容不可变，竞态只会产生等价实例。

  private AssistRequestStore getStore() {
    return store == null ? (store = new AssistRequestStoreAdapter(this)) : store;
  }

  private AssistApplyCoordinator getApplyCoordinator() {
    return applyCoordinator == null
        ? (applyCoordinator =
            new AssistApplyCoordinator(
                getStore(),
                userMapper,
                approvalAttachmentService,
                assistMessageService,
                getResponsibilityResolver(),
                getSnapshotAssembler()))
        : applyCoordinator;
  }

  private AssistReapplyCoordinator getReapplyCoordinator() {
    return reapplyCoordinator == null
        ? (reapplyCoordinator =
            new AssistReapplyCoordinator(
                getStore(), userMapper, assistMessageService, getResponsibilityResolver()))
        : reapplyCoordinator;
  }

  private AssistResponsibilityResolver getResponsibilityResolver() {
    return responsibilityResolver == null
        ? (responsibilityResolver =
            new AssistResponsibilityResolver(
                userMapper,
                salesStageApprovalMapper,
                salesOpportunityMapper,
                businessActivityMapper,
                businessActivityUserMapper,
                contactTaskMapper,
                getStore()))
        : responsibilityResolver;
  }

  private AssistAccessGuard getAccessGuard() {
    return accessGuard == null
        ? (accessGuard = new AssistAccessGuard(getStore(), getResponsibilityResolver()))
        : accessGuard;
  }

  private AssistOpportunitySnapshotSupport getOpportunitySnapshotSupport() {
    return opportunitySnapshotSupport == null
        ? (opportunitySnapshotSupport =
            new AssistOpportunitySnapshotSupport(
                salesOpportunityMapper,
                businessActivityMapper,
                approvalAttachmentService,
                dataConvertService))
        : opportunitySnapshotSupport;
  }

  private AssistSnapshotAssembler getSnapshotAssembler() {
    return snapshotAssembler == null
        ? (snapshotAssembler =
            new AssistSnapshotAssembler(
                salesStageApprovalMapper,
                businessActivityMapper,
                contactTaskMapper,
                approvalAttachmentService,
                assistRelatedRecordResolver,
                dataConvertService,
                objectMapper,
                getOpportunitySnapshotSupport()))
        : snapshotAssembler;
  }

  private AssistSnapshotHistorySupport getSnapshotHistorySupport() {
    return snapshotHistorySupport == null
        ? (snapshotHistorySupport =
            new AssistSnapshotHistorySupport(objectMapper, downloadTokenUtil, httpRequest))
        : snapshotHistorySupport;
  }

  private AssistRecordBriefCollector getRecordBriefCollector() {
    return recordBriefCollector == null
        ? (recordBriefCollector =
            new AssistRecordBriefCollector(
                salesStageApprovalMapper,
                businessActivityMapper,
                contactTaskMapper,
                salesOpportunityMapper))
        : recordBriefCollector;
  }

  private AssistRecordBriefFiller getRecordBriefFiller() {
    return recordBriefFiller == null
        ? (recordBriefFiller =
            new AssistRecordBriefFiller(getRecordBriefCollector(), dataConvertService))
        : recordBriefFiller;
  }

  private AssistVoAssembler getVoAssembler() {
    return voAssembler == null
        ? (voAssembler = new AssistVoAssembler(userMapper, dataConvertService))
        : voAssembler;
  }

  private AssistDetailAssembler getDetailAssembler() {
    return detailAssembler == null
        ? (detailAssembler =
            new AssistDetailAssembler(
                salesStageApprovalMapper,
                businessActivityMapper,
                contactTaskMapper,
                salesOpportunityMapper,
                dataConvertService,
                getRecordBriefFiller(),
                getSnapshotHistorySupport()))
        : detailAssembler;
  }

  private AssistSourceAttachmentSupport getSourceAttachmentSupport() {
    return sourceAttachmentSupport == null
        ? (sourceAttachmentSupport =
            new AssistSourceAttachmentSupport(
                getStore(), getAccessGuard(), approvalAttachmentService, businessActivityMapper))
        : sourceAttachmentSupport;
  }

  private AssistListQueryCoordinator getListQueryCoordinator() {
    return listQueryCoordinator == null
        ? (listQueryCoordinator =
            new AssistListQueryCoordinator(
                getStore(),
                getVoAssembler(),
                getRecordBriefFiller(),
                getAccessGuard(),
                approvalAttachmentService))
        : listQueryCoordinator;
  }
}
