package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistMessageService;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;

/**
 * 驳回后重新申请协作类（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属主类入口方法 {@code reapply}。事务由主类入口统一开启（{@code @Transactional} 只在主类），本类不带事务注解、 不持有 Spring
 * 自代理、不新开事务：落库经 {@link AssistRequestStore}（主类实例本身）进行，与拆分前主类的自我调用等价。
 *
 * <p>校验顺序逐处保持拆分前的次序：原记录存在 → 申请人或业务负责人 → 原记录已驳回 → 条目合法性 → 幂等（待协助 / 重申请链） → 协助人在职 → 落库。
 */
class AssistReapplyCoordinator {

  private final AssistRequestStore store;
  private final UserMapper userMapper;
  private final AssistMessageService assistMessageService;
  private final AssistResponsibilityResolver responsibilityResolver;

  AssistReapplyCoordinator(
      AssistRequestStore store,
      UserMapper userMapper,
      AssistMessageService assistMessageService,
      AssistResponsibilityResolver responsibilityResolver) {
    this.store = store;
    this.userMapper = userMapper;
    this.assistMessageService = assistMessageService;
    this.responsibilityResolver = responsibilityResolver;
  }

  /** 驳回后重新申请：创建新协助记录（parent_id 指向原记录），原记录保留。 */
  Long reapply(Long originalAssistId, List<AssistApplyItem> applyList) {
    if (originalAssistId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    Long currentId = BaseUnit.getCurrentId();
    AssistRequestEntity original =
        requireReappliableOriginal(store.getById(originalAssistId), currentId);
    AssistApplyItem item = AssistApplyRules.validateReapplyItem(applyList, original, currentId);
    assertNoPendingOrReapplied(original, originalAssistId);
    assertReapplyAssistUserActive(original);

    AssistRequestEntity entity =
        AssistApplyRules.buildReapplyEntity(original, currentId, item, originalAssistId);
    persistReapplied(entity);
    return entity.getId();
  }

  /** 重新申请的前置校验：原记录存在、当前用户是申请人或业务负责人、且原记录处于已驳回状态。 */
  private AssistRequestEntity requireReappliableOriginal(
      AssistRequestEntity original, Long currentId) {
    if (original == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "原协助记录不存在");
    }
    if (!Objects.equals(original.getApplicantId(), currentId)
        && !responsibilityResolver.isCurrentBusinessResponsible(original, currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    if (!Objects.equals(original.getAssistStatus(), 2)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "仅已驳回的协助申请可以重新申请");
    }
    return original;
  }

  /** 幂等校验：同一业务同一协助人不能有待协助记录；重申请链只能针对最新驳回记录继续。 */
  private void assertNoPendingOrReapplied(AssistRequestEntity original, Long originalAssistId) {
    long pendingCount =
        store.count(
            AssistRequestQueries.pendingCountOf(
                original.getModelName(), original.getRecordId(), original.getAssistUserId()));
    if (pendingCount > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助人已有待协助申请，请勿重复发起");
    }
    // parentId 是一条重申请链：应针对最新的驳回记录继续申请，不能回头复用旧节点。
    if (store.count(AssistRequestQueries.childrenOf(originalAssistId)) > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助申请已有重新申请记录，请针对最新驳回记录重新申请");
    }
  }

  /** 校验原协助人仍存在且在职（status=1）。 */
  private void assertReapplyAssistUserActive(AssistRequestEntity original) {
    UserEntity assistUser = userMapper.selectById(original.getAssistUserId());
    if (assistUser == null || !Objects.equals(assistUser.getStatus(), 1)) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, "协助人不存在或已冻结/离职：用户ID " + original.getAssistUserId());
    }
  }

  /** 落库并追加「已重新发起协助申请」系统消息；并发下唯一索引是最后一道防线。 */
  private void persistReapplied(AssistRequestEntity entity) {
    try {
      store.save(entity);
      assistMessageService.appendSystemMessage(entity.getId(), "已重新发起协助申请");
    } catch (DuplicateKeyException e) {
      // 并发请求可能同时通过上面的查询；唯一索引是最后一道防线。
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助申请已重新发起，请勿重复提交");
    }
  }
}
