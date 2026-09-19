package com.slz.crm.common.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产配置守卫（任务 3：生产 fail-fast，避免带病上线）。
 *
 * <p>职责：只在 {@code prod} profile 生效，启动期逐项校验安全相关配置， 任一项不满足直接抛 {@link IllegalStateException} 阻断启动。
 * 这类问题（弱密钥、默认口令、裸 Actuator、auto-table 改表）在测试环境往往不暴露， 只能靠启动期硬闸兜底。
 *
 * <p>为什么不做成“告警不阻断”：安全项的代价是“上线后被扫出/被脱库”， 修复成本远高于本次启动失败，所以选择 fail-fast（任务 3 原文口径）。
 */
@Component
@Profile("prod")
public class ProductionConfigurationGuard implements InitializingBean {

  /** HS256 密钥安全下限（字节）；32 字节即 256 bit，低于该值可被字典/彩虹表攻破 */
  private static final int MIN_SECRET_LENGTH = 32;

  /** CRM 原始默认口令；生产继续沿用即视为未加固 */
  private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

  private final Environment environment;

  /**
   * @param environment Spring 环境属性（含 application.yml 与环境变量解析结果）
   */
  public ProductionConfigurationGuard(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() {
    // 1) JWT 密钥：长度与“非默认值”双校验
    String jwtSecret = required("slz.jwt.secret-key");
    if (jwtSecret.length() < MIN_SECRET_LENGTH) {
      fail("slz.jwt.secret-key 长度不足 " + MIN_SECRET_LENGTH + " 字节（当前 " + jwtSecret.length() + "）");
    }
    // 2) 附件下载令牌：AES-256 需要 32 字节
    String attachToken = required("slz.attach-token.secret-key");
    if (attachToken.length() < MIN_SECRET_LENGTH) {
      fail("slz.attach-token.secret-key 长度不足 " + MIN_SECRET_LENGTH + " 字节");
    }
    // 3) 数据库凭据
    required("spring.datasource.username");
    required("spring.datasource.password");
    // 4) 表结构只能由 Flyway 管理（D2 / 任务 4）
    String autoTableMode = environment.getProperty("auto-table.mode", "none");
    if (!"none".equalsIgnoreCase(autoTableMode)) {
      fail("生产 profile 必须 auto-table.mode=none（当前 " + autoTableMode + "），表结构以 Flyway 为唯一真相源");
    }
    if (!"true".equalsIgnoreCase(environment.getProperty("spring.flyway.enabled", "true"))) {
      fail("生产 profile 必须 spring.flyway.enabled=true");
    }
    // 5) Actuator 必须受保护（移除 Security 后不留裸奔）
    if (!"true"
        .equalsIgnoreCase(environment.getProperty("platform.actuator.protected-enabled", "true"))) {
      fail("生产 profile 必须 platform.actuator.protected-enabled=true");
    }
    // 6) 向量库：生产必须真 Qdrant（D9：内存实现仅供本地/测试）
    String vectorProvider = environment.getProperty("rag.vector-store.provider", "qdrant");
    if (!"qdrant".equalsIgnoreCase(vectorProvider)) {
      fail("生产 profile 必须 rag.vector-store.provider=qdrant（当前 " + vectorProvider + "）");
    }
    // 7) 初始管理员口令不得是默认值
    String adminPassword = environment.getProperty("data.init.admin.password", "");
    if (DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
      fail("生产 profile 必须通过 SLZ_ADMIN_PASSWORD 覆盖默认管理员口令");
    }
  }

  /**
   * 读取必填配置。
   *
   * @param key 配置键
   * @return 非空配置值
   */
  private String required(String key) {
    String value = environment.getProperty(key, "");
    if (value.isBlank() || value.contains("${")) {
      // contains("${") 兜底未解析的占位符，避免把占位符本身当密钥用
      fail(key + " 未配置（请通过环境变量或 .env 注入）");
    }
    return value;
  }

  /**
   * 统一失败出口。
   *
   * @param reason 具体原因（直接透出到启动日志）
   */
  private void fail(String reason) {
    throw new IllegalStateException("[ProductionConfigurationGuard] 生产配置校验失败: " + reason);
  }
}
