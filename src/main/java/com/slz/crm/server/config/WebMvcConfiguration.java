package com.slz.crm.server.config;

import com.slz.crm.server.interceptor.JWTInterceptor;
import com.slz.crm.server.interceptor.PermissionsInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private JWTInterceptor jwtInterceptor;
    @Autowired
    private PermissionsInterceptor permissionsInterceptor;
    @Autowired
    private Environment environment;

    /**
     * OpenAPI/Swagger 路径仅在开发环境放行（本地契约导出用），
     * 生产环境不匿名暴露完整接口结构，需登录后访问。
     */
    private boolean exposesOpenApi() {
        return environment.acceptsProfiles("dev", "local");
    }

    /**
     * 公共排除路径：登录/健康检查/公开接口通用，OpenAPI 路径仅 dev 环境放行。
     */
    private String[] publicPaths() {
        List<String> paths = new ArrayList<>(List.of("/login", "/health", "/public/**"));
        if (exposesOpenApi()) {
            paths.addAll(List.of("/v3/api-docs", "/v3/api-docs.*", "/swagger-ui/**", "/swagger-ui.html"));
        }
        return paths.toArray(new String[0]);
    }

    @Override
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/");
        super.addResourceHandlers(registry);
    }

    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(publicPaths());
        registry.addInterceptor(permissionsInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(publicPaths());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许 OPTIONS 方法
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
