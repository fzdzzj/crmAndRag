package com.slz.crm.platform.contract;

/**
 * 记忆旁路执行器契约（冻结契约，contracts-frozen.md §7）。
 *
 * <p>归属：接口由 base 冻结，实现 = <b>Lane D</b>（bean 名 {@code memoryBypassExecutor}，
 * pool=2 / 有界队列=64 / AbortPolicy），消费 = <b>Lane C</b>（意图/摘要等记忆加工任务）。</p>
 *
 * <p>为什么是 {@code tryExecute} 而不是 {@code execute}：
 * 记忆加工是“锦上添花”，队列满时宁可丢弃也不允许阻塞主答链路。
 * 返回 {@code false} = 提交被拒（队列满/关闭中），调用方据此<b>降级跳过</b>该轮加工。</p>
 *
 * <p>实现职责（D）：通过 {@code MdcTaskDecorator.wrap} 传播 traceId 与 UserContext（异步快照传播），
 * 保证旁路任务里能取到当前用户与链路标识。</p>
 *
 * <p>边界（§7 明确）：<b>单飞去重由调用方（C）按会话 CAS 完成</b>，
 * 本接口只提供“可拒绝的执行器”语义，不感知业务状态。</p>
 */
public interface BypassTaskExecutor {

    /**
     * 尝试提交旁路任务，不阻塞调用线程。
     *
     * @param task 任务体（通常包装了记忆加工逻辑）
     * @return true=已入队；false=队列满或执行器已关闭，调用方应降级跳过
     */
    boolean tryExecute(Runnable task);
}
