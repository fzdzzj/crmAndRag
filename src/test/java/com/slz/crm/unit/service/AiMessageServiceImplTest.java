package com.slz.crm.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.server.mapper.AiMessageMapper;
import com.slz.crm.server.service.impl.AiMessageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 消息保存与 token 统计")
class AiMessageServiceImplTest {

  @Mock private AiMessageMapper mapper;

  private AiMessageServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AiMessageServiceImpl();
    ReflectionTestUtils.setField(service, "baseMapper", mapper);
  }

  @Test
  void saveMessage_withoutUsage_writesZeroTokenCount() {
    service.saveMessage(9L, "assistant", "text", "回答", "{}");

    ArgumentCaptor<AiMessageEntity> captor = ArgumentCaptor.forClass(AiMessageEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getTokenCount()).isZero();
  }

  @Test
  void saveMessage_withUsage_writesTokenCount() {
    service.saveMessage(9L, "assistant", "text", "回答", "{}", 128);

    ArgumentCaptor<AiMessageEntity> captor = ArgumentCaptor.forClass(AiMessageEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getTokenCount()).isEqualTo(128);
  }

  @Test
  void saveMessage_withInvalidUsage_writesZeroTokenCount() {
    service.saveMessage(9L, "assistant", "text", "回答", "{}", null);

    ArgumentCaptor<AiMessageEntity> captor = ArgumentCaptor.forClass(AiMessageEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getTokenCount()).isZero();
  }
}
