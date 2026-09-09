package com.slz.crm.platform.contract;

/**
 * 运行期动态配置读取契约（冻结契约，Lane E 实现，B/C/D 消费，D10）。
 *
 * <p>边界：动态配置只负责“可调策略参数”，
 * 不承载密钥/凭据（那些仍走环境变量，见 ProductionConfigurationGuard）。</p>
 *
 * <p>典型键域：</p>
 * <ul>
 *   <li>{@code ai.prompt.system} 系统提示词</li>
 *   <li>{@code ai.model.temperature} / {@code ai.model.maxTokens}</li>
 *   <li>{@code rag.retrieval.topK} / {@code rag.retrieval.minScore} / {@code rag.retrieval.strictKb}</li>
 *   <li>{@code assistant.imageCacheMaxEntries} 图片理解缓存上限（D13）</li>
 *   <li>{@code assistant.intent.categories} 意图/类目（D17）</li>
 * </ul>
 *
 * <p>线程安全：实现需支持并发读；热生效由实现内部缓存保证（有界刷新）。</p>
 */
public interface DynamicConfigService {

    /**
     * 读取配置值；未配置时返回默认值（不抛异常）。
     *
     * @param key          配置键（命名空间用点分，如 {@code rag.retrieval.topK}）
     * @param type         期望类型（String/Integer/Long/Boolean/Double/List&lt;String&gt;）
     * @param defaultValue 未配置或类型不匹配时返回的默认值
     * @param <T>          值类型
     * @return 当前生效值
     */
    <T> T get(String key, Class<T> type, T defaultValue);
}
