package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;

import java.util.List;

/**
 * AI 会话持久记忆服务。
 */
public interface AiConversationMemoryService {

    /**
     * 查询记忆加工品；不存在返回 null。
     */
    AiConversationMemoryEntity findBySessionId(Long sessionId);

    /**
     * 确保记忆行存在；并发首建撞唯一键时回读已建行。
     */
    AiConversationMemoryEntity ensureMemory(Long sessionId, Long userId);

    /**
     * 使用乐观锁更新加工品；版本冲突返回 false，由调用方降级跳过。
     */
    boolean updateMemory(AiConversationMemoryEntity memory);

    /**
     * recentMessages 不是独立双写，而是每次从 ai_message 还原的时间正序投影。
     */
    List<AiMessageEntity> restoreRecentProjection(Long sessionId, int limit);
}
