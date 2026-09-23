package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.vo.AssistVO;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 协助记录持久化入口（tighten-pmd-residual-325 任务 6.5）。
 *
 * <p>本类拆分出的支持类需要读写协助记录本身，本接口把这份能力显式交给它们。接口由 {@link AssistRequestStoreAdapter} 实现（转交给 {@link
 * AssistRequestServiceImpl} 实例）。 为什么不让主类直接实现本接口：主类继承 MyBatis-Plus {@code ServiceImpl}，下面「持久化能力」段的 8
 * 个方法全部来自 {@code IService} 的 <b>default</b> 方法，而 Java 语言规则不允许用继承来的 default 方法去满足另一个接口声明的抽象方法（实测
 * javac 报「未覆盖抽象方法」）。
 *
 * <p>事务口径（本批硬约束）：支持类不携带任何事务注解、不持有 Spring 自代理、不新开事务。它们拿到的是 主类实例本身（不经过 Spring
 * 代理），落库调用与拆分前主类内部的自我调用逐处等价 —— 都运行在主类入口方法 {@code @Transactional(rollbackFor = Exception.class)}
 * 已开启的同一个事务与连接上。 因为不经过代理重入，传播语义与拆分前完全一致：跨类调用不会新增、也不会提前提交任何事务。
 */
public interface AssistRequestStore {

  // ===== 持久化能力（与 MyBatis-Plus IService 上的同名方法一一对应） =====

  List<AssistRequestEntity> list(Wrapper<AssistRequestEntity> query);

  AssistRequestEntity getById(Serializable id);

  boolean save(AssistRequestEntity entity);

  boolean updateById(AssistRequestEntity entity);

  boolean updateBatchById(Collection<AssistRequestEntity> entities);

  boolean removeByIds(Collection<?> ids);

  boolean remove(Wrapper<AssistRequestEntity> query);

  long count(Wrapper<AssistRequestEntity> query);

  // ===== 主类入口回调 =====
  // 以下 4 个方法在拆分前是主类内部的自我调用（createAssists / listAssistsByRecords /
  // listAssistsByRecord / requirePendingAssistSource）。转由本接口回调，是为了让跨类调用与
  // 拆分前的自我调用在调用图上一一对应：既有单测对这几个入口的 stub 与 verify 仍然成立，
  // 事务语义也不变（回调同样不经过 Spring 代理）。

  void createAssists(
      String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList);

  List<AssistVO> listAssistsByRecords(String modelName, List<Long> recordIds);

  List<AssistVO> listAssistsByRecord(String modelName, Long recordId);

  AssistRequestEntity requirePendingAssistSource(
      Long assistId, String expectedModelName, Long userId);
}
