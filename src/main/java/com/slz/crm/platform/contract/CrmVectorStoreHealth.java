package com.slz.crm.platform.contract;

/**
 * 向量库健康探测契约（冻结契约，contracts-frozen.md §2）。
 *
 * <p>归属：接口由 base 冻结，实现 = <b>Lane B</b>（{@code QdrantVectorStore} / {@code InMemoryVectorStore} 各自实现），
 * 消费 = <b>Lane D</b>（health indicator：{@code inMemoryFallback()} 为 true 时告警降级，
 * 否则 {@code probe()} 为 false 时把 readiness 置 DOWN）。</p>
 *
 * <p>为什么单独抽接口而不塞进 {@link CrmVectorStore}：向量读写契约应保持“纯能力”，
 * 健康探测是运维关切；混在一起会让 B 的实现被迫依赖 actuator 语义。</p>
 *
 * <p>Qdrant 探测实现约束：走现有 gRPC 6334 原生客户端，<b>不得</b>另开 http-port 依赖。</p>
 *
 * <p>线程安全：实现须支持并发探测（health 端点可能被多个请求同时调用）。</p>
 */
public interface CrmVectorStoreHealth {

    /**
     * @return 组件名，用于 health 指示器命名（如 {@code vectorStoreQdrant} / {@code vectorStoreInMemory}）
     */
    String componentName();

    /**
     * @return true 表示当前运行在内存回退实现（D9）；生产 MUST 为 false，否则 health 降级为 WARN/降级态
     */
    boolean inMemoryFallback();

    /**
     * @return 当前使用的集合名（与配置 {@code knowledge.qdrant.collection} 或内存实现占位名一致）
     */
    String collectionName();

    /**
     * @return true 表示当前可用（Qdrant 可连通且集合存在；内存实现恒 true）
     */
    boolean probe();
}
