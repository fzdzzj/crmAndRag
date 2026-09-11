package com.slz.crm.platform.lifecycle;

import com.slz.crm.platform.mapper.PlatformLifecycleEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 生命周期事件幂等与崩溃恢复测试。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleEventServiceTest {

    @Mock
    private PlatformLifecycleEventMapper mapper;

    private SimpleMeterRegistry registry;

    private LifecycleEventService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new LifecycleEventService(mapper, registry);
    }

    @Test
    void shouldCreateEvent() {
        when(mapper.insert(any(PlatformLifecycleEventEntity.class))).thenReturn(1);
        LifecycleEventRequest request = request();

        LifecycleEventResult result = service.record(request);

        assertThat(result.created()).isTrue();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.event().getIdempotencyKey()).isEqualTo("doc-1:INGEST:1:test");
        assertThat(registry.get("platform.lifecycle.recorded").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldSkipDuplicateIdempotencyKey() {
        PlatformLifecycleEventEntity existing = new PlatformLifecycleEventEntity();
        existing.setId(9L);
        existing.setIdempotencyKey("doc-1:INGEST:1:test");
        when(mapper.insert(any(PlatformLifecycleEventEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectOne(any())).thenReturn(existing);

        LifecycleEventResult result = service.record(request());

        assertThat(result.created()).isFalse();
        assertThat(result.duplicate()).isTrue();
        assertThat(result.event().getId()).isEqualTo(9L);
    }

    @Test
    void shouldRejectBlankIdempotencyKey() {
        LifecycleEventRequest request = new LifecycleEventRequest(
                "doc-1", LifecycleEventType.INGEST, 1, " ", null);

        assertThatThrownBy(() -> service.record(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LifecycleEventRequest request() {
        return new LifecycleEventRequest("doc-1", LifecycleEventType.INGEST, 1,
                "doc-1:INGEST:1:test", "{\"key\":\"value\"}");
    }
}
