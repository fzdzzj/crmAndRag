package com.slz.crm.server.service;

import com.slz.crm.pojo.dto.ai.AiDraftResult;
import com.slz.crm.pojo.entity.AiPendingActionEntity;
import com.slz.crm.pojo.vo.AiConfirmResultVO;
import com.slz.crm.pojo.vo.AiPendingActionVO;

public interface PendingActionService {
  AiDraftResult submitDraft(Long sessionId, Long userId, String actionType, String payloadJson);

  AiDraftResult mergeDraft(String pendingId, Long userId, String incrementJson);

  AiConfirmResultVO confirm(String pendingId, Long userId);

  void cancel(String pendingId, Long userId);

  void markExpired(String pendingId);

  void markFailed(String pendingId, String resultJson);

  AiPendingActionVO edit(String pendingId, Long userId, String payloadJson);

  AiPendingActionVO getStatus(String pendingId, Long userId);

  AiPendingActionEntity getByPendingId(String pendingId);

  void cancelBySessionId(Long sessionId);

  int expireOverdue();
}
