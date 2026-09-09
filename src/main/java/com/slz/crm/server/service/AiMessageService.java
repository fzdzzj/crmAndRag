package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.vo.AiMessageVO;

import java.util.List;

public interface AiMessageService {
    AiMessageEntity saveMessage(Long sessionId, String role, String msgType, String content, String payload);

    /**
     * 保存消息并写入 token 统计；无模型用量时传 0。
     */
    AiMessageEntity saveMessage(Long sessionId, String role, String msgType, String content, String payload,
                                Integer tokenCount);

    List<AiMessageVO> scrollMessages(Long sessionId, Long afterId, int limit);
    List<AiMessageEntity> listRecentContextMessages(Long sessionId, int limit);
}
