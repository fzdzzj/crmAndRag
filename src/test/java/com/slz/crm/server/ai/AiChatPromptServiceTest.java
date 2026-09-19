package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI Prompt 启动期校验")
class AiChatPromptServiceTest {

  private final AiChatPromptService promptService = new AiChatPromptService();

  @Test
  void loadPrompts_acceptsRequiredFiles() {
    promptService.loadPrompts("prompts/system-prompt.txt", "prompts/session-title.txt");

    assertThat(promptService.getPromptVersion()).isNotBlank();
  }

  @Test
  void loadPrompts_failsWhenSystemPromptMissing() {
    assertThatThrownBy(
            () ->
                promptService.loadPrompts(
                    "prompts/missing-system-prompt.txt", "prompts/session-title.txt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("prompts/missing-system-prompt.txt");
  }

  @Test
  void loadPrompts_failsWhenTitlePromptBlank() {
    assertThatThrownBy(
            () ->
                promptService.loadPrompts("prompts/system-prompt.txt", "prompts/blank-prompt.txt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("prompts/blank-prompt.txt");
  }
}
