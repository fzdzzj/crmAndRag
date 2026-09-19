package com.slz.crm.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 注册平台域 MyBatis Mapper（治理 D + 动态配置 E）。
 *
 * <p>与基座 {@code server.mapper} 分包，避免平台表 Mapper 混入业务模块。
 *
 * <p>集成接线：E 的动态配置 Mapper 在 {@code platform.config.mapper} 子包， 与 D 的 {@code platform.mapper}
 * 一并扫描——否则 DynamicConfigItemMapper 无 bean， DynamicConfigAdminService 装配失败（H2 上下文冒烟测出的集成缺口）。
 */
@Configuration
@MapperScan({"com.slz.crm.platform.mapper", "com.slz.crm.platform.config.mapper"})
public class PlatformMybatisScanConfig {}
