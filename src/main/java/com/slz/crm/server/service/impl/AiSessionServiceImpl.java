package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.pojo.vo.AiSessionVO;
import com.slz.crm.server.ai.AiChatImageContextCache;
import com.slz.crm.server.mapper.AiSessionMapper;
import com.slz.crm.server.service.AiSessionService;
import com.slz.crm.server.service.PendingActionService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class AiSessionServiceImpl extends ServiceImpl<AiSessionMapper, AiSessionEntity>
    implements AiSessionService {

  @Autowired @Lazy private PendingActionService pendingActionService;

  @Autowired private AiChatImageContextCache imageContextCache;

  @Override
  public AiSessionEntity createSession(Long userId, String title) {
    AiSessionEntity session = new AiSessionEntity();

    session.setUserId(userId);

    session.setTitle(title == null || title.isBlank() ? "新对话" : title);

    session.setStatus(1);

    session.setCreatedTime(LocalDateTime.now());

    session.setUpdatedTime(LocalDateTime.now());

    save(session);

    return session;
  }

  @Override
  public void updateTitle(Long sessionId, Long userId, String title) {
    if (sessionId == null || userId == null || title == null || title.isBlank()) {

      return;
    }
    // 仅更新本人会话的标题
    String safeTitle = title.length() > 100 ? title.substring(0, 100) : title;

    update(
        new UpdateWrapper<AiSessionEntity>()
            .eq("id", sessionId)
            .eq("user_id", userId)
            .set("title", safeTitle));
  }

  @Override
  public List<AiSessionVO> scrollSessions(
      Long userId, LocalDateTime cursorTime, Long cursorId, int limit) {
    LambdaQueryWrapper<AiSessionEntity> wrapper =
        new LambdaQueryWrapper<AiSessionEntity>()
            .eq(AiSessionEntity::getUserId, userId)
            .eq(AiSessionEntity::getStatus, 1)
            .orderByDesc(AiSessionEntity::getUpdatedTime)
            .orderByDesc(AiSessionEntity::getId)
            .last("LIMIT " + limit);

    // 游标条件：(updated_time, id) < (cursorTime, cursorId)
    if (cursorTime != null && cursorId != null) {

      wrapper.and(
          w ->
              w.lt(AiSessionEntity::getUpdatedTime, cursorTime)
                  .or(
                      o ->
                          o.eq(AiSessionEntity::getUpdatedTime, cursorTime)
                              .lt(AiSessionEntity::getId, cursorId)));
    }
    List<AiSessionEntity> entities = list(wrapper);

    List<AiSessionVO> result = new ArrayList<>(entities.size());

    for (AiSessionEntity entity : entities) {

      AiSessionVO vo = new AiSessionVO();

      BeanUtils.copyProperties(entity, vo);

      result.add(vo);
    }

    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void archiveSession(Long sessionId, Long userId) {
    AiSessionEntity session = getOwnedSession(sessionId, userId);

    if (session == null || session.getStatus() == null || session.getStatus() == 0) {

      return;
    }
    session.setStatus(0);

    session.setUpdatedTime(LocalDateTime.now());

    updateById(session);

    // 级联取消该会话的 PENDING/DRAFTING 待确认操作
    pendingActionService.cancelBySessionId(sessionId);

    evictImageContextAfterCommit(sessionId);
  }

  /** 缓存释放不可随数据库回滚，必须在两次写操作都成功返回后注册：提交后清该会话，回滚则什么都不清。 */
  private void evictImageContextAfterCommit(Long sessionId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              imageContextCache.evictSession(sessionId);
            }
          });
    } else {
      imageContextCache.evictSession(sessionId);
    }
  }

  @Override
  public AiSessionEntity getOwnedSession(Long sessionId, Long userId) {
    AiSessionEntity result;
    if (sessionId == null || userId == null) {
      result = null;
    } else {
      result =
          getOne(
              new LambdaQueryWrapper<AiSessionEntity>()
                  .eq(AiSessionEntity::getId, sessionId)
                  .eq(AiSessionEntity::getUserId, userId),
              false);
    }
    return result;
  }
}
