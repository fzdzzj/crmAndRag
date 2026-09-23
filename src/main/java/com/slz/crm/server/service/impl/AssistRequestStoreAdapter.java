package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.vo.AssistVO;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * {@link AssistRequestStore} 的落地实现：把能力接口的方法逐条转交给主类实例（tighten-pmd-residual-325 任务 6.5）。
 *
 * <p>为什么需要这层适配而不是让主类直接实现能力接口：主类继承 MyBatis-Plus {@code ServiceImpl}，其 {@code
 * list/getById/save/updateById/updateBatchById/removeByIds/remove/count} 全部来自 {@code IService} 的
 * <b>default</b> 方法。Java 语言规则下，从接口继承来的 default 方法<b>不能</b>用它去满足另一个无继承关系接口声明的抽象方法（实测 javac
 * 报「未覆盖抽象方法」），所以主类无法直接 {@code implements AssistRequestStore}。本适配器把调用显式转交，
 * 语义等价且不引入任何新方法到主类上（主类的方法数不受影响）。
 *
 * <p>「主类入口回调」段的 4 个方法转交的是主类自己的公共入口，用于把拆分前的自我调用原样保留（{@code applyAssists -> createAssists}、 {@code
 * listAssistsByRecord -> listAssistsByRecords}、{@code getVisibleAssists -> listAssistsByRecord}、
 * {@code getRelatedActivityAttachments / deleteRelatedAttachments -> requirePendingAssistSource}）。
 *
 * <p>事务口径（本批硬约束）：适配器不携带事务注解、不持有 Spring 代理、不新开事务。它持有的是 主类实例本身（{@code this}，即 Spring 目标对象，不是代理），
 * 因此转交调用与拆分前主类内部的自我调用逐处等价 —— 都运行在主类入口方法已开启的同一个事务与连接上， 既不新增事务、也不提前提交，传播语义不变。
 */
final class AssistRequestStoreAdapter implements AssistRequestStore {

  private final AssistRequestServiceImpl target;

  AssistRequestStoreAdapter(AssistRequestServiceImpl target) {
    this.target = target;
  }

  @Override
  public List<AssistRequestEntity> list(Wrapper<AssistRequestEntity> query) {
    return target.list(query);
  }

  @Override
  public AssistRequestEntity getById(Serializable id) {
    return target.getById(id);
  }

  @Override
  public boolean save(AssistRequestEntity entity) {
    return target.save(entity);
  }

  @Override
  public boolean updateById(AssistRequestEntity entity) {
    return target.updateById(entity);
  }

  @Override
  public boolean updateBatchById(Collection<AssistRequestEntity> entities) {
    return target.updateBatchById(entities);
  }

  @Override
  public boolean removeByIds(Collection<?> ids) {
    return target.removeByIds(ids);
  }

  @Override
  public boolean remove(Wrapper<AssistRequestEntity> query) {
    return target.remove(query);
  }

  @Override
  public long count(Wrapper<AssistRequestEntity> query) {
    return target.count(query);
  }

  @Override
  public void createAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList) {
    target.createAssists(modelName, recordId, applicantId, applyList);
  }

  @Override
  public List<AssistVO> listAssistsByRecords(String modelName, List<Long> recordIds) {
    return target.listAssistsByRecords(modelName, recordIds);
  }

  @Override
  public List<AssistVO> listAssistsByRecord(String modelName, Long recordId) {
    return target.listAssistsByRecord(modelName, recordId);
  }

  @Override
  public AssistRequestEntity requirePendingAssistSource(
      Long assistId, String expectedModelName, Long userId) {
    return target.requirePendingAssistSource(assistId, expectedModelName, userId);
  }
}
