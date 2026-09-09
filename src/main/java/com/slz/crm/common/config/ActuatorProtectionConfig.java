package com.slz.crm.common.config;

import com.slz.crm.common.filter.ActuatorProtectionFilter;
import com.slz.crm.common.properties.ActuatorProtectionProperties;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Actuator 保护装配（任务 3）。
 *
 * <p>为什么显式注册 Filter 而不是用 {@code @Component}：
 * 用 {@link FilterRegistrationBean} 才能把 {@code url-pattern} 限定为 {@code /actuator/*}，
 * 避免过滤器误伤业务请求；同时显式设置 order，保证它在编码/字符集过滤器之后、业务逻辑之前执行。</p>
 */
@Configuration
@EnableConfigurationProperties(ActuatorProtectionProperties.class)
public class ActuatorProtectionConfig {

    /**
     * 注册 /actuator/** 保护过滤器。
     *
     * @param properties    保护配置（开关/白名单/超管端点）
     * @param jwtProperties JWT 密钥与 token 头名
     * @param userMapper    用户查询（用于判定超管）
     * @return 已配置的过滤器注册器
     */
    @Bean
    public FilterRegistrationBean<ActuatorProtectionFilter> actuatorProtectionFilter(
            ActuatorProtectionProperties properties,
            JwtProperties jwtProperties,
            UserMapper userMapper) {
        FilterRegistrationBean<ActuatorProtectionFilter> registration =
                new FilterRegistrationBean<>(new ActuatorProtectionFilter(properties, jwtProperties, userMapper));
        // 只作用于 Actuator：业务路径仍由 JWTInterceptor/PermissionsInterceptor 负责鉴权
        registration.addUrlPatterns("/actuator/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        // 名字固定，便于运维在日志里定位过滤器
        registration.setName("actuatorProtectionFilter");
        return registration;
    }
}
