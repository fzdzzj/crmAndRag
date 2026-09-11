package com.slz.crm.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 注册平台治理域 MyBatis Mapper。
 *
 * <p>与基座 {@code server.mapper} 分包，避免治理表 Mapper 混入业务模块。</p>
 */
@Configuration
@MapperScan("com.slz.crm.platform.mapper")
public class PlatformMybatisScanConfig {
}
