package com.slz.crm.server.ai;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 流式对话与工具调用的运行指标。
 * 指标只记录低基数维度和耗时，不携带业务参数或返回内容。
 */
@Component
public class AiChatMetrics {

    /** 工具名标签；取值来自工具注册表 */
    private static final String TOOL_LABEL = "tool";

    /** 执行结果标签；只允许 success/failure 两种低基数取值 */
    private static final String STATUS_LABEL = "status";

    private final MeterRegistry registry;

    /** 注册活跃流 Gauge，值实时读取 AiStreamRegistry。 */
    public AiChatMetrics(MeterRegistry registry, AiStreamRegistry streamRegistry) {
        this.registry = registry;
        Gauge.builder("ai.chat.stream.active", streamRegistry, AiStreamRegistry::activeCount)
                .register(registry);
    }

    /** 每次向 SSE 客户端发送一次心跳时累加。 */
    public void recordHeartbeat() {
        registry.counter("ai.chat.stream.heartbeats").increment();
    }

    /** 记录模型流正常完成，并写入带模型维度的总耗时。 */
    public void recordCompleted(AiStreamRegistry.ActiveStream activeStream, String model, boolean fallback) {
        registry.counter("ai.chat.stream.completed", "model", model, "fallback", String.valueOf(fallback))
                .increment();
        recordStreamDuration(activeStream, model, fallback, "completed");
    }

    /** 记录主备模型均失败或 SSE 发送失败导致的异常终止。 */
    public void recordFailed(AiStreamRegistry.ActiveStream activeStream, String model, boolean fallback) {
        registry.counter("ai.chat.stream.failed", "model", model, "fallback", String.valueOf(fallback))
                .increment();
        recordStreamDuration(activeStream, model, fallback, "failed");
    }

    /** 记录用户主动取消、生成中替换或客户端断开清理。 */
    public void recordCancelled(AiStreamRegistry.ActiveStream activeStream, String model, boolean fallback) {
        registry.counter("ai.chat.stream.cancelled", "model", model, "fallback", String.valueOf(fallback))
                .increment();
        recordStreamDuration(activeStream, model, fallback, "cancelled");
    }

    /** 只在 ActiveStream 标记出首个回答片段后记录一次。 */
    public void recordFirstToken(AiStreamRegistry.ActiveStream activeStream, String model, boolean fallback) {
        Long firstTokenNanos = activeStream.getFirstTokenNanos();
        if (firstTokenNanos != null) {
            registry.timer("ai.chat.stream.first-token", "model", model, "fallback", String.valueOf(fallback))
                    .record(Duration.ofNanos(firstTokenNanos - activeStream.getStartNanos()));
        }
    }

    /** 按工具名和执行结果累计调用次数与耗时，结果只使用 success/failure 低基数标签。 */
    public void recordToolCall(String toolName, boolean success, long startNanos) {
        Tags tags = Tags.of(TOOL_LABEL, toolName, STATUS_LABEL, success ? "success" : "failure");
        registry.counter("ai.tool.call.total", tags).increment();
        registry.timer("ai.tool.call.duration", tags)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /** ActiveStream 的开始时间基于单调时钟，可安全计算耗时。 */
    private void recordStreamDuration(AiStreamRegistry.ActiveStream activeStream, String model, boolean fallback,
                                      String status) {
        registry.timer("ai.chat.stream.duration", "model", model,
                        "fallback", String.valueOf(fallback), "status", status)
                .record(Duration.ofNanos(System.nanoTime() - activeStream.getStartNanos()));
    }
}
