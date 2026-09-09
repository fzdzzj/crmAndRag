package com.slz.crm.platform.contract;

/**
 * 助手 SSE 统一事件名（冻结契约，Lane C 实现，Lane B/D 遵守）。
 *
 * <p>口径来源：CRM 原用 {@code text/done/stopped/title}，RAG 原用 {@code start/sources/delta/complete/cancelled/error}，
 * 两套并存会导致前端双实现；本枚举把事件名收敛为一份。</p>
 *
 * <p>顺序约定：</p>
 * <pre>start → meta → sources? → thinking* → delta* → references? → done | cancelled | stopped | error</pre>
 *
 * <p>{@link #PING} 是注释级心跳，不属于业务事件，前端 MUST 忽略其 payload。</p>
 */
public enum SseEventName {

    /** 流开始；payload 含 sessionId（首次由后端生成）、assistantMessageId */
    START("start"),
    /** 会话/模型/开关元信息；payload 含 provider、model、useKnowledgeBase、thinking */
    META("meta"),
    /** 检索到的知识库片段（答案生成前发出，先于 delta）；payload 为 SourceReference 列表 */
    SOURCES("sources"),
    /** 思考过程增量；可多次出现，仅在 thinking=true 时出现 */
    THINKING("thinking"),
    /** 答案正文增量；前端按序拼接即为完整回答 */
    DELTA("delta"),
    /** 实际被引用的来源编号集（档 B 高亮只高亮承重来源） */
    REFERENCES("references"),
    /** 正常结束；payload 含 usage（token 计量） */
    DONE("done"),
    /** 客户端主动取消（订阅取消即触发，服务端需停止生成） */
    CANCELLED("cancelled"),
    /** 被新会话接管（同一 sessionId 重新发起流式请求，旧流让位） */
    STOPPED("stopped"),
    /** 服务端异常；payload 含错误码与用户可读消息（不透出堆栈） */
    ERROR("error"),
    /** 心跳注释帧（SSE comment，用于穿透代理与保活） */
    PING("ping");

    private final String wireName;

    SseEventName(String wireName) {
        this.wireName = wireName;
    }

    /** @return 写到 SSE {@code event:} 后的名字（小写，前端以此分发） */
    public String wireName() {
        return wireName;
    }
}
