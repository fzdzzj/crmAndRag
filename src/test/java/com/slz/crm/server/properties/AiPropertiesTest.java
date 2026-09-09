package com.slz.crm.server.properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 配置默认值验证。 */
class AiPropertiesTest {

    @Test
    void shouldUseHeartbeatDefaults() {
        // 心跳默认开启，保证生产环境未配置时连接仍不会被静默断开
        AiProperties properties = new AiProperties();

        assertThat(properties.getHeartbeatEnabled()).isTrue();
        assertThat(properties.getHeartbeatIntervalSeconds()).isEqualTo(15);
        assertThat(properties.getHeartbeatPoolSize()).isEqualTo(4);
        assertThat(properties.getSseTimeoutSeconds()).isEqualTo(300);
    }
}
