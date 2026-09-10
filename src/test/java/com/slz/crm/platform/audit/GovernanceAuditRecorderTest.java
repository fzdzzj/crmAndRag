package com.slz.crm.platform.audit;

import com.slz.crm.platform.mapper.PlatformGovernanceAuditMapper;
import com.slz.crm.platform.trace.RequestTraceKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 治理审计记录与失败容错测试。
 */
@ExtendWith(MockitoExtension.class)
class GovernanceAuditRecorderTest {

    @Mock
    private PlatformGovernanceAuditMapper mapper;

    @Mock
    private ObjectProvider<PlatformGovernanceAuditMapper> mapperProvider;

    private SimpleMeterRegistry registry;

    private GovernanceAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new GovernanceAuditRecorder(mapperProvider, registry);
        MDC.put(RequestTraceKey.TRACE_ID, "trace-123456");
    }

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void shouldPersistAuditEventWithTraceId() {
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);
        GovernanceAuditEvent event = new GovernanceAuditEvent("QUOTA_ADJUSTED", "user:1",
                "QUOTA", "user:1", "UPDATE_DAILY_LIMIT", GovernanceAuditResult.SUCCESS, "{}");

        recorder.record(event);

        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat(entity -> {
            assertThat(entity.getTraceId()).isEqualTo("trace-123456");
            assertThat(entity.getActorUserRef()).isEqualTo("user:1");
            return true;
        }));
        assertThat(registry.get("platform.governance.audit").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldSwallowAuditFailure() {
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);
        when(mapper.insert(org.mockito.ArgumentMatchers.any(PlatformGovernanceAuditEntity.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        GovernanceAuditEvent event = new GovernanceAuditEvent("CLEANUP_TRIGGERED", null,
                "REPORT", "report-1", "RUN", GovernanceAuditResult.FAILED, null);

        assertThatCode(() -> recorder.record(event)).doesNotThrowAnyException();
        assertThat(registry.get("platform.governance.audit.failure").counter().count()).isEqualTo(1.0);
    }
}
