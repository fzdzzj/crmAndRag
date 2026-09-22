package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.server.mapper.AiConversationMemoryMapper;
import com.slz.crm.server.service.AiConversationMemoryService;
import com.slz.crm.server.service.AiMessageService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AiConversationMemoryServiceImpl
    extends ServiceImpl<AiConversationMemoryMapper, AiConversationMemoryEntity>
    implements AiConversationMemoryService {

  @Autowired private AiMessageService aiMessageService;

  @Override
  public AiConversationMemoryEntity findBySessionId(Long sessionId) {
    AiConversationMemoryEntity result = null;
    if (sessionId != null) {
      result =
          getOne(
              new LambdaQueryWrapper<AiConversationMemoryEntity>()
                  .eq(AiConversationMemoryEntity::getSessionId, sessionId));
    }
    return result;
  }

  @Override
  public AiConversationMemoryEntity ensureMemory(Long sessionId, Long userId) {
    AiConversationMemoryEntity existing = findBySessionId(sessionId);
    AiConversationMemoryEntity result = existing;
    if (existing == null) {
      AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
      memory.setSessionId(sessionId);
      memory.setUserId(userId);
      memory.setSummary("");
      memory.setFacts("[]");
      memory.setIntent("");
      memory.setVersion(0);
      memory.setCreatedTime(LocalDateTime.now());
      memory.setUpdatedTime(memory.getCreatedTime());
      try {
        save(memory);
        result = memory;
      } catch (DuplicateKeyException exception) {
        // 两个请求同时首建时，唯一键是保护线；回读获胜方，避免覆盖或报错中断主答。
        log.info("AI 会话记忆并发首建冲突，回读已有行: sessionId={}", sessionId);
        result = findBySessionId(sessionId);
      }
    }
    return result;
  }

  @Override
  public boolean updateMemory(AiConversationMemoryEntity memory) {
    boolean result = false;
    if (memory != null && memory.getId() != null && memory.getVersion() != null) {
      memory.setUpdatedTime(LocalDateTime.now());
      result = updateById(memory);
    }
    return result;
  }

  @Override
  public List<AiMessageEntity> restoreRecentProjection(Long sessionId, int limit) {
    List<AiMessageEntity> recent = List.of();
    if (sessionId != null && limit > 0) {
      recent = new ArrayList<>(aiMessageService.listRecentContextMessages(sessionId, limit));
      // Mapper 返回 id 倒序；这里还原为对话时间正序，供窗口/摘要/prompt 直接消费。
      Collections.reverse(recent);
    }
    return recent;
  }

  @Override
  public boolean deleteExpiredBefore(LocalDateTime threshold) {
    boolean result = false;
    if (threshold != null) {
      result =
          remove(
              new LambdaQueryWrapper<AiConversationMemoryEntity>()
                  .lt(AiConversationMemoryEntity::getUpdatedTime, threshold));
    }
    return result;
  }
}
