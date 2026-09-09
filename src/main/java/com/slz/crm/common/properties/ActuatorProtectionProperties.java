package com.slz.crm.common.properties;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Actuator 端点保护配置（任务 3）。
 *
 * <p>为什么用 Filter 而不是 HandlerInterceptor：
 * Actuator 端点由独立的 {@code EndpointHandlerMapping} 承载，
 * 不经过 {@code WebMvcConfiguration#addInterceptors} 注册的拦截器；
 * 若只挂拦截器，{@code /actuator/**} 实际处于“看似有保护、实则裸奔”的状态。
 * 因此保护逻辑落在 Servlet Filter（{@code url-pattern=/actuator/*}）。</p>
 *
 * <p>线程安全：本类只承载配置，启动后由 Spring 管理为不可变单例（实现侧不得改字段）。</p>
 */
@ConfigurationProperties(prefix = "platform.actuator")
public class ActuatorProtectionProperties {

    /** 是否启用保护；生产 profile MUST 为 true（见 ProductionConfigurationGuard） */
    private boolean protectedEnabled = true;

    /** 免鉴权白名单：只放行 K8s 存活/就绪探针（不泄露组件明细） */
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/actuator/health/liveness",
            "/actuator/health/readiness"));

    /** 需要超级管理员（roleId=1）的敏感端点前缀 */
    private List<String> adminOnlyPaths = new ArrayList<>(List.of(
            "/actuator/metrics",
            "/actuator/prometheus",
            "/actuator/loggers",
            "/actuator/info"));

    public boolean isProtectedEnabled() {
        return protectedEnabled;
    }

    public void setProtectedEnabled(boolean protectedEnabled) {
        this.protectedEnabled = protectedEnabled;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<String> getAdminOnlyPaths() {
        return adminOnlyPaths;
    }

    public void setAdminOnlyPaths(List<String> adminOnlyPaths) {
        this.adminOnlyPaths = adminOnlyPaths;
    }
}
