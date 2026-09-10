package com.slz.crm.platform.contract;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

/**
 * 中立模型调用选项（修正轮2+3：thinking/temperature/maxTokens/toolCallbacks）。
 *
 * <p>为什么需要本类：{@code ModelProviderImpl.withStreamUsage/withModel} 的
 * else 分支对非 {@code OpenAiChatOptions} 入参只保留 model、丢弃其余，
 * 且 compatible-mode 的 {@code enable_thinking} 无承载位。
 * Lane C 改用本类传递 thinking/temperature/toolCallbacks 等参数，
 * 实现（base）负责翻译到当前 provider 的对应协议字段。</p>
 *
 * <p>修正轮3：新增 toolCallbacks/toolContext，工具调用走 Spring AI 执行环
 * （{@code ChatModel.call/stream} 自带 tool loop，不再绕过 ModelProvider）。</p>
 *
 * <p>调用方不应传 provider 专有 options（如 {@code DashScopeChatOptions}），
 * 一律通过本类 + {@code extra} 传递，provider 差异由实现封装。</p>
 *
 * @param model         覆盖默认模型名（null = 用配置默认）
 * @param thinking      是否开启思维链（DashScope compatible-mode 映射为 enable_thinking）
 * @param temperature   采样温度（null = 用模型默认）
 * @param maxTokens     最大输出 token 数（null = 不限制）
 * @param toolCallbacks 工具回调列表（Spring AI ToolCallback，修正轮3；null/空 = 无工具）
 * @param toolContext   工具执行上下文（Spring AI toolContext，修正轮3；null = 无）
 * @param extra         透传到 provider 的额外键值对（provider 专有，实现方自行映射）
 */
public record ModelCallOptions(
        String model,
        boolean thinking,
        Double temperature,
        Integer maxTokens,
        List<ToolCallback> toolCallbacks,
        Map<String, Object> toolContext,
        Map<String, Object> extra
) {

    /** 无额外参数的默认选项（走配置默认值）。 */
    public static ModelCallOptions defaults() {
        return new ModelCallOptions(null, false, null, null, null, null, Map.of());
    }

    /**
     * 便捷工厂：带工具回调的选项（其他字段用默认）。
     *
     * @param callbacks 工具回调列表
     * @param context   工具执行上下文（可 null）
     * @return 带 tools 的 ModelCallOptions
     */
    public static ModelCallOptions withTools(List<ToolCallback> callbacks, Map<String, Object> context) {
        return new ModelCallOptions(null, false, null, null, callbacks, context, Map.of());
    }

    /** @return 是否有工具回调（非 null 且非空） */
    public boolean hasTools() {
        return toolCallbacks != null && !toolCallbacks.isEmpty();
    }
}