package com.slz.crm.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 动态配置模块自身参数（前缀 {@code platform.dynamic-config}）。
 *
 * <p>全部带代码默认值，无需改 application.yml 即可工作；集成方如需调整，
 * 可在 yml 中按前缀覆盖（不属“application.yml 核心”改动）。</p>
 *
 * <p>热生效机制（对应 spec「缓存与失效」场景）：</p>
 * <ul>
 *   <li><b>本地写后失效</b>：超管写操作提交后立即逐键失效缓存，同实例下次读取即新值；</li>
 *   <li><b>有界刷新</b>：读取侧以 {@link #refreshIntervalMs()} 为界做全量重载，
 *       兜底多实例/旁路改库场景——陈旧窗口上界 = 刷新间隔，超界必收敛到最新值；</li>
 *   <li>稳态读取全部命中内存缓存，不击穿数据库。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "platform.dynamic-config")
public class DynamicConfigProperties {

    /**
     * 缓存全量刷新间隔（毫秒），也是“陈旧配置可接受窗口”的上界。
     * 默认 5000ms：牺牲最多 5 秒一致性换取读取零 DB 开销；写后失效使同实例即时生效。
     */
    private long refreshIntervalMs = 5000L;

    /** 缓存总开关；false 时读取直接查库（仅排障用，生产建议保持 true） */
    private boolean cacheEnabled = true;
}
