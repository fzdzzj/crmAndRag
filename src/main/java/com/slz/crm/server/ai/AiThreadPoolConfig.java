package com.slz.crm.server.ai;

import com.slz.crm.server.properties.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 模块线程池配置
 * 按职责隔离，避免互相影响：
 * - aiChatExecutor: 主对话流（LLM 调用，耗时较长）
 * - aiTitleExecutor: 标题生成（轻量 LLM 调用）
 * - aiAuditExecutor: 审计日志入库（IO 密集，低优先级）
 *
 * 线程池参数通过 application.yml crm.ai.thread-pool.* 配置
 */
@Configuration
public class AiThreadPoolConfig {


    @Autowired
    private AiProperties aiProperties;

    /**
     * AI 对话主线程池（流式输出 + Function Calling）
     * 拒绝策略 CallerRunsPolicy：队列满时退化为同步执行，保证不丢请求
     */
    @Bean("aiChatExecutor")
    public Executor aiChatExecutor() {
        AiProperties.PoolConfig config = aiProperties.getThreadPool().getChat();

        return buildExecutor(config, "ai-chat-", new ThreadPoolExecutor.CallerRunsPolicy(), 30);

    }

    /**
     * AI 标题生成线程池（轻量级 LLM 调用）
     * 拒绝策略 DiscardPolicy：标题丢了不影响主对话，静默丢弃
     */
    @Bean("aiTitleExecutor")
    public Executor aiTitleExecutor() {
        AiProperties.PoolConfig config = aiProperties.getThreadPool().getTitle();

        return buildExecutor(config, "ai-title-", new ThreadPoolExecutor.DiscardPolicy(), 10);

    }

    /**
     * AI 审计日志线程池（IO 密集，异步写入不阻塞主流程）
     * 拒绝策略 DiscardOldestPolicy：日志堆积时丢旧的保新的
     */
    @Bean("aiAuditExecutor")
    public Executor aiAuditExecutor() {
        AiProperties.PoolConfig config = aiProperties.getThreadPool().getAudit();

        return buildExecutor(config, "ai-audit-", new ThreadPoolExecutor.DiscardOldestPolicy(), 5);

    }

    private Executor buildExecutor(AiProperties.PoolConfig config, String prefix,
                                   RejectedExecutionHandler rejectedHandler, int awaitSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(config.getCoreSize());

        executor.setMaxPoolSize(config.getMaxSize());

        executor.setQueueCapacity(config.getQueueCapacity());

        executor.setThreadNamePrefix(prefix);

        executor.setRejectedExecutionHandler(rejectedHandler);

        executor.setWaitForTasksToCompleteOnShutdown(true);

        executor.setAwaitTerminationSeconds(awaitSeconds);

        executor.initialize();

        return executor;

    }
}
