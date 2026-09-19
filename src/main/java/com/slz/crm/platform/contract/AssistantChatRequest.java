package com.slz.crm.platform.contract;

import java.util.List;

/**
 * 助手流式对话请求契约（冻结契约，Lane C 拥有 SSE 实现，Lane B 提供检索能力）。
 *
 * <p>路径约定：{@code POST /ai/chat/stream}。
 *
 * <p>字段语义（与 assistant-decision-tree.md 对齐）：
 *
 * <ul>
 *   <li>{@code sessionId}：会话标识；首次对话可由前端传 null 由后端生成； 同一 sessionId 的流式请求会被 AiStreamRegistry 串行化（D14
 *       记忆并发约束）；
 *   <li>{@code message}：本轮用户输入，必填；
 *   <li>{@code useKnowledgeBase}：知识库开关（D12：**手动开关**，不由 LLM 自动决定）；
 *   <li>{@code thinking}：是否需要思考过程（{@code thinking} 事件）；
 *   <li>{@code imageRef}：<b>助手会话内聊天图片</b>的引用（D13：不再“回退最近一张图”，必须显式指明）；
 *   <li>{@code attachments}：本轮携带的附件（业务文件 id / 知识库文档 id），允许为空。
 * </ul>
 *
 * @param sessionId 会话 id，允许为 null（后端生成后经 {@code start} 事件回传）
 * @param message 用户输入，不能为空串
 * @param useKnowledgeBase 是否启用知识库检索
 * @param thinking 是否需要思考流
 * @param imageRef 聊天图片引用（C 域：聊天图片存储键，或已持久化 L1 理解的 hash），允许为 null。
 *     语义澄清（§9/C4）：这是<b>助手会话内聊天图片</b>的引用， <b>不是</b> CRM 业务附件，也<b>不是</b> Lane B 的 {@code
 *     uploaded_file}； C 需自建聊天图片存储并按 hash 持久化 L1 理解（对齐 D13 可恢复语义）。
 * @param attachments 附件引用集合，允许为 null
 */
public record AssistantChatRequest(
    String sessionId,
    String message,
    Boolean useKnowledgeBase,
    Boolean thinking,
    String imageRef,
    List<String> attachments) {

  /**
   * 归一化构造：把 Boolean 统一成默认语义，避免上层到处判 null。
   *
   * @throws IllegalArgumentException message 为空时抛出
   */
  public AssistantChatRequest {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message 不能为空");
    }
    useKnowledgeBase = useKnowledgeBase != null && useKnowledgeBase;
    thinking = thinking != null && thinking;
    if (attachments == null) {
      attachments = List.of();
    } else {
      attachments = List.copyOf(attachments);
    }
  }
}
