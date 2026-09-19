package com.slz.crm.platform.contract;

/**
 * 助手 SSE 统一事件名与 payload 契约（冻结契约，Lane C 实现，Lane B/D 遵守）。
 *
 * <p>口径来源：CRM 原用 {@code text/done/stopped/title}，RAG 原用 {@code
 * start/sources/delta/complete/cancelled/error}， 两套并存导致前端双实现；本枚举把事件名与 payload
 * 字段收敛为一份（contracts-frozen.md §4）。
 *
 * <p>顺序冻结：{@code start → meta → sources? → thinking* → delta* → references? → title? →} {@code done
 * | cancelled | stopped | error}；{@link #PING} 为注释帧，可穿插，不计入顺序。
 *
 * <p>断线续传约定（§5）：每个<b>业务事件</b>须带 SSE {@code id:}，格式 {@code <generationId>:<seq>} （generation
 * 内单调递增）；{@link #PING} <b>不带 id</b>、不进有界缓冲、不参与重放。 重放语义 = 只重放缓冲输出，<b>绝不</b>重跑 LLM/检索、不重复计 token、不重复写
 * ai_message。
 *
 * <p>线程安全：枚举天然线程安全。
 */
public enum SseEventName {

  /**
   * 流开始。payload 字段冻结：{@code sessionId}、{@code assistantMessageId}、{@code generationId}。 {@code
   * generationId} 是断线续传与事件 {@code id:} 的根，客户端须保存。
   */
  START("start"),

  /**
   * 会话/模型/开关元信息。payload 字段冻结：{@code provider}、{@code model}、 {@code useKnowledgeBase}、{@code
   * thinking}。
   */
  META("meta"),

  /**
   * 检索到的知识库片段（答案生成前先发）。payload = {@code [SourceReference]}， 必须用冻结的 {@link SourceReference}（含 {@code
   * chunkIndex/pageNo/chunkId}）。
   */
  SOURCES("sources"),

  /** 思考过程增量。payload 字段冻结：{@code text}、{@code finished}（B2）。 仅在请求 {@code thinking=true} 时出现，可多次。 */
  THINKING("thinking"),

  /** 答案正文增量。payload 字段冻结：{@code content}。前端按序拼接即为完整回答。 */
  DELTA("delta"),

  /**
   * 实际被引用的来源与业务实体。payload 字段冻结：{@code citations:[n]}（答案内联引用编号）、 {@code
   * items:[{type,id,name}]}（业务实体）。档 B 高亮只高亮承重来源。
   */
  REFERENCES("references"),

  /**
   * 异步会话标题（C7）。payload 字段冻结：{@code text}。 注意：它出现在 {@code references} 之后、终态事件之前，也可能在 {@code done}
   * 之后单独补发—— 因此客户端不得把它当作流结束信号。
   */
  TITLE("title"),

  /**
   * 正常结束。payload 字段冻结：{@code sessionId}、{@code cancelled:false}、 {@code
   * usage:{input,output,total}}。usage 用于 token 计量核对，不得省略。
   */
  DONE("done"),

  /** 客户端主动取消。payload 字段冻结：{@code reason}。 */
  CANCELLED("cancelled"),

  /** 被同会话新请求接管（旧流让位）。payload 字段冻结：{@code reason}。 */
  STOPPED("stopped"),

  /**
   * 服务端异常。payload 字段冻结：{@code code}、{@code msg}、可选 {@code retryAfterSeconds}。 脱敏：不透堆栈/SQL；断线续传失效时
   * {@code code} 用 {@link PlatformErrorCode#RESUME_UNAVAILABLE}。
   */
  ERROR("error"),

  /** 心跳注释帧（SSE comment {@code :ping}）。<b>无 id、不缓冲、不重放</b>，前端忽略 payload。 */
  PING("ping");

  private final String wireName;

  SseEventName(String wireName) {
    this.wireName = wireName;
  }

  /**
   * @return 写到 SSE {@code event:} 后的名字（小写，前端以此分发）
   */
  public String wireName() {
    return wireName;
  }
}
