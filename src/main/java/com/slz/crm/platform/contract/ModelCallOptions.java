package com.slz.crm.platform.contract;

import java.util.Map;

/**
 * 中立模型调用选项（契约修正轮2，解 C 的 thinking 丢失 bug）。
 *
 * <p>为什么需要本类：{@code ModelProviderImpl.withStreamUsage/withModel} 的
 * else 分支对非 {@code OpenAiChatOptions} 入参只保留 model、丢弃其余，
 * 且 compatible-mode 的 {@code enable_thinking} 无承载位。
 * Lane C 改用本类传递 thinking/temperature 等参数，
 * 实现（base）负责翻译到当前 provider 的对应协议字段。</p>
 *
 * <p>调用方不应传 provider 专有 options（如 {@code DashScopeChatOptions}），
 * 一律通过本类 + {@code extra} 传递，provider 差异由实现封装。</p>
 *
 * @param model       覆盖默认模型名（null = 用配置默认）
 * @param thinking    是否开启思维链（DashScope compatible-mode 映射为 enable_thinking）
 * @param temperature 采样温度（null = 用模型默认）
 * @param maxTokens   最大输出 token 数（null = 不限制）
 * @param extra       透传到 provider 的额外键值对（provider 专有，实现方自行映射）
 */
public record ModelCallOptions(
        String model,
        boolean thinking,
        Double temperature,
        Integer maxTokens,
        Map<String, Object> extra
) {

    /** 无额外参数的默认选项（走配置默认值）。 */
    public static ModelCallOptions defaults() {
        return new ModelCallOptions(null, false, null, null, Map.of());
    }
}