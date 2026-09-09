package com.slz.crm.unit.service;

import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.server.mapper.AiConversationMemoryMapper;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.impl.AiConversationMemoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 会话持久记忆")
class AiConversationMemoryServiceImplTest {

    @Mock
    private AiConversationMemoryMapper mapper;

    @Mock
    private AiMessageService aiMessageService;

    private AiConversationMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new AiConversationMemoryServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "aiMessageService", aiMessageService);
    }

    @Test
    void ensureMemory_createsEmptyRowForNewSession() {
        doReturn(null).when(service).findBySessionId(9L);

        AiConversationMemoryEntity created = service.ensureMemory(9L, 42L);

        ArgumentCaptor<AiConversationMemoryEntity> captor =
                ArgumentCaptor.forClass(AiConversationMemoryEntity.class);
        verify(mapper).insert(captor.capture());
        AiConversationMemoryEntity inserted = captor.getValue();
        assertThat(created).isSameAs(inserted);
        assertThat(inserted.getSessionId()).isEqualTo(9L);
        assertThat(inserted.getUserId()).isEqualTo(42L);
        assertThat(inserted.getFacts()).isEqualTo("[]");
        assertThat(inserted.getVersion()).isZero();
    }

    @Test
    void updateMemory_requiresPersistedOptimisticRow() {
        AiConversationMemoryEntity invalid = new AiConversationMemoryEntity();
        assertThat(service.updateMemory(invalid)).isFalse();

        AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
        memory.setId(1L);
        memory.setVersion(3);
        memory.setIntent("查询合同");
        when(mapper.updateById(memory)).thenReturn(1);

        assertThat(service.updateMemory(memory)).isTrue();
        assertThat(memory.getUpdatedTime()).isNotNull();
    }

    @Test
    void restoreRecentProjection_reversesMapperOrderToChronological() {
        AiMessageEntity newest = new AiMessageEntity();
        newest.setId(12L);
        AiMessageEntity oldest = new AiMessageEntity();
        oldest.setId(8L);
        when(aiMessageService.listRecentContextMessages(9L, 2)).thenReturn(List.of(newest, oldest));

        List<AiMessageEntity> projection = service.restoreRecentProjection(9L, 2);

        assertThat(projection).extracting(AiMessageEntity::getId).containsExactly(8L, 12L);
    }
}
