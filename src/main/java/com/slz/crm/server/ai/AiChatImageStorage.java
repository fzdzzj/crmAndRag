package com.slz.crm.server.ai;

/**
 * 聊天图片字节存储接口；后续 MinIO 实现可无缝替换本地实现。
 */
public interface AiChatImageStorage {

    /**
     * @return 持久化到 ai_chat_image.storage_backend 的稳定标识
     */
    String backend();

    /**
     * 保存图片字节。
     *
     * @param sessionId   会话隔离前缀
     * @param imageHash   SHA-256
     * @param content     图片字节
     * @param contentType MIME 类型
     * @return 供重理解/重嵌入恢复字节用的存储键
     */
    String store(Long sessionId, String imageHash, byte[] content, String contentType);

    byte[] read(String storageKey);
}
