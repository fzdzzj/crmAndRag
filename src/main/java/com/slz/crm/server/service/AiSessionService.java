package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.AiSessionEntity;
import com.slz.crm.pojo.vo.AiSessionVO;
import java.time.LocalDateTime;
import java.util.List;

public interface AiSessionService {
  AiSessionEntity createSession(Long userId, String title);

  void updateTitle(Long sessionId, Long userId, String title);

  List<AiSessionVO> scrollSessions(Long userId, LocalDateTime cursorTime, Long cursorId, int limit);

  void archiveSession(Long sessionId, Long userId);

  AiSessionEntity getOwnedSession(Long sessionId, Long userId);
}
