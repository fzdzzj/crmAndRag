package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.PromptTemplateRenderDTO;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.vo.PromptTemplateRenderVO;
import com.slz.crm.server.ai.AiChatPromptService.PromptSource;
import com.slz.crm.server.service.PromptTemplateService;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StreamUtils;

/**
 * AI Prompt 服务单测（aichat-prompt-template-integration 任务 3.1/3.2）。
 *
 * <p>覆盖两类：既有启动期静态文本校验，以及运行时模板态——模板态正常渲染 / 模板缺失回退 / 停用回退 / 渲染异常回退 / 等价锁（种子 == 静态文件）/ promptVersion
 * 两态一致 / 标题提示词同构。
 */
@DisplayName("AI Prompt 启动期校验与运行时模板态")
class AiChatPromptServiceTest {

  private static final String SYSTEM_PROMPT_PATH = "prompts/system-prompt.txt";
  private static final String TITLE_PROMPT_PATH = "prompts/session-title.txt";
  private static final String V35_SQL_PATH = "db/migration/V35__aichat_prompt_seed.sql";
  private static final String SYSTEM_TEMPLATE_CODE = "aichat-system-prompt";
  private static final String TITLE_TEMPLATE_CODE = "aichat-session-title";

  // ===== 既有启动期静态文本校验（保持不变） =====

  @Test
  void loadPrompts_acceptsRequiredFiles() {
    AiChatPromptService promptService = new AiChatPromptService();
    promptService.loadPrompts(SYSTEM_PROMPT_PATH, TITLE_PROMPT_PATH);

    assertThat(promptService.getPromptVersion()).isNotBlank();
  }

  @Test
  void loadPrompts_failsWhenSystemPromptMissing() {
    AiChatPromptService promptService = new AiChatPromptService();
    assertThatThrownBy(
            () -> promptService.loadPrompts("prompts/missing-system-prompt.txt", TITLE_PROMPT_PATH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("prompts/missing-system-prompt.txt");
  }

  @Test
  void loadPrompts_failsWhenTitlePromptBlank() {
    AiChatPromptService promptService = new AiChatPromptService();
    assertThatThrownBy(
            () -> promptService.loadPrompts(SYSTEM_PROMPT_PATH, "prompts/blank-prompt.txt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("prompts/blank-prompt.txt");
  }

  // ===== 运行时模板态（任务 3.1/3.2） =====

  @Test
  @DisplayName("模板态：buildSystemPrompt 经 aichat-system-prompt render 取骨架")
  void buildSystemPrompt_usesTemplateRendering() {
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenReturn(renderVo("模板骨架内容"));
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);

    String skeleton = buildSystemSkeleton(service);

    assertThat(skeleton).isEqualTo("模板骨架内容");
    assertThat(service.getPromptSource()).isEqualTo(PromptSource.TEMPLATE);
    ArgumentCaptor<PromptTemplateRenderDTO> captor =
        ArgumentCaptor.forClass(PromptTemplateRenderDTO.class);
    verify(templateService).render(captor.capture());
    assertThat(captor.getValue().getCode()).isEqualTo(SYSTEM_TEMPLATE_CODE);
    assertThat(captor.getValue().getVariables()).isEmpty();
  }

  @Test
  @DisplayName("回退：模板不存在 → 静态文本 + FALLBACK")
  void buildSystemPrompt_fallsBackWhenTemplateMissing() {
    assertFallback(new BaseException(ErrorCode.ID_NOT_EXISTS, "提示词模板不存在"));
  }

  @Test
  @DisplayName("回退：模板停用 → 静态文本 + FALLBACK")
  void buildSystemPrompt_fallsBackWhenTemplateDisabled() {
    assertFallback(new BaseException(ErrorCode.NOT_ENABLED, "提示词模板已停用"));
  }

  @Test
  @DisplayName("回退：render 抛任意异常 → 静态文本 + FALLBACK")
  void buildSystemPrompt_fallsBackOnRenderException() {
    assertFallback(new IllegalStateException("render 探针异常"));
  }

  @Test
  @DisplayName("等价锁：V35 种子 content 与 classpath 静态文件内容逐字节一致（行尾归一）")
  void seedContent_matchesStaticPromptFiles() {
    assertThat(normalizeEol(seedContent(SYSTEM_TEMPLATE_CODE)))
        .isEqualTo(normalizeEol(readClasspath(SYSTEM_PROMPT_PATH)));
    assertThat(normalizeEol(seedContent(TITLE_TEMPLATE_CODE)))
        .isEqualTo(normalizeEol(readClasspath(TITLE_PROMPT_PATH)));
  }

  @Test
  @DisplayName("等价锁：模板态输出 == 种子 content == 静态文件内容")
  void templateStateOutput_equalsSeedAndStaticFile() {
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenReturn(renderVo(seedContent(SYSTEM_TEMPLATE_CODE)));
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);

    String skeleton = buildSystemSkeleton(service);

    assertThat(skeleton).isEqualTo(seedContent(SYSTEM_TEMPLATE_CODE));
    assertThat(normalizeEol(skeleton)).isEqualTo(normalizeEol(readClasspath(SYSTEM_PROMPT_PATH)));
  }

  @Test
  @DisplayName("promptVersion 两态一致：内容相同则 sha256 版本相同")
  void promptVersion_sameAcrossTemplateAndFallbackStates() {
    String fileContent = readClasspath(SYSTEM_PROMPT_PATH);

    PromptTemplateService okTemplate = mock(PromptTemplateService.class);
    when(okTemplate.render(any())).thenReturn(renderVo(fileContent));
    AiChatPromptService templateState = newLoadedService();
    wireTemplate(templateState, okTemplate);
    buildSystemSkeleton(templateState);

    PromptTemplateService failingTemplate = mock(PromptTemplateService.class);
    when(failingTemplate.render(any())).thenThrow(new IllegalStateException("fallback"));
    AiChatPromptService fallbackState = newLoadedService();
    wireTemplate(fallbackState, failingTemplate);
    buildSystemSkeleton(fallbackState);

    assertThat(templateState.getPromptVersion()).isNotBlank();
    assertThat(templateState.getPromptVersion()).isEqualTo(fallbackState.getPromptVersion());
  }

  @Test
  @DisplayName("标题同构：generateTitle 经 aichat-session-title render 取提示词")
  void generateTitle_usesTemplateRendering() {
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenReturn(renderVo(seedContent(TITLE_TEMPLATE_CODE)));
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    TitleChain chain = mockTitleChain("生成的标题");

    String title = service.generateTitle(chain.builder, "你好");

    assertThat(title).isEqualTo("生成的标题");
    ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
    verify(chain.spec).system(systemCaptor.capture());
    assertThat(systemCaptor.getValue()).isEqualTo(seedContent(TITLE_TEMPLATE_CODE));
    ArgumentCaptor<PromptTemplateRenderDTO> dtoCaptor =
        ArgumentCaptor.forClass(PromptTemplateRenderDTO.class);
    verify(templateService).render(dtoCaptor.capture());
    assertThat(dtoCaptor.getValue().getCode()).isEqualTo(TITLE_TEMPLATE_CODE);
  }

  @Test
  @DisplayName("标题回退：模板缺失 → 静态标题提示词")
  void generateTitle_fallsBackWhenTemplateMissing() {
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any()))
        .thenThrow(new BaseException(ErrorCode.ID_NOT_EXISTS, "提示词模板不存在"));
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    TitleChain chain = mockTitleChain("生成的标题");

    service.generateTitle(chain.builder, "你好");

    ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
    verify(chain.spec).system(systemCaptor.capture());
    assertThat(systemCaptor.getValue()).isEqualTo(readClasspath(TITLE_PROMPT_PATH));
  }

  // ===== 测试工具 =====

  private AiChatPromptService newLoadedService() {
    AiChatPromptService service = new AiChatPromptService();
    service.loadPrompts(SYSTEM_PROMPT_PATH, TITLE_PROMPT_PATH);
    return service;
  }

  private void wireTemplate(AiChatPromptService service, PromptTemplateService templateService) {
    ReflectionTestUtils.setField(service, "promptTemplateService", templateService);
  }

  /** 经反射调用私有 buildSystemPrompt；memory 全字段为空 → 返回值即静态骨架（不含动态追加段）。 */
  private String buildSystemSkeleton(AiChatPromptService service) {
    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    Method method =
        ReflectionUtils.findMethod(
            AiChatPromptService.class,
            "buildSystemPrompt",
            AiConversationMemoryEntity.class,
            boolean.class);
    ReflectionUtils.makeAccessible(method);
    return (String) ReflectionUtils.invokeMethod(method, service, memory, false);
  }

  private void assertFallback(RuntimeException renderFailure) {
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenThrow(renderFailure);
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);

    String skeleton = buildSystemSkeleton(service);

    assertThat(normalizeEol(skeleton)).isEqualTo(normalizeEol(readClasspath(SYSTEM_PROMPT_PATH)));
    assertThat(service.getPromptSource()).isEqualTo(PromptSource.FALLBACK);
  }

  /** generateTitle 的 mock 链路（builder + request spec），供 verify 断言 system 入参。 */
  private static final class TitleChain {
    private final ChatClient.Builder builder;
    private final ChatClient.ChatClientRequestSpec spec;

    private TitleChain(ChatClient.Builder builder, ChatClient.ChatClientRequestSpec spec) {
      this.builder = builder;
      this.spec = spec;
    }
  }

  private TitleChain mockTitleChain(String title) {
    ChatClient.Builder builder = mock(ChatClient.Builder.class);
    ChatClient client = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(builder.build()).thenReturn(client);
    when(client.prompt()).thenReturn(promptSpec);
    when(promptSpec.system(anyString())).thenReturn(promptSpec);
    when(promptSpec.user(anyString())).thenReturn(promptSpec);
    when(promptSpec.call()).thenReturn(callSpec);
    when(callSpec.content()).thenReturn(title);
    return new TitleChain(builder, promptSpec);
  }

  private PromptTemplateRenderVO renderVo(String content) {
    PromptTemplateRenderVO vo = new PromptTemplateRenderVO();
    vo.setContent(content);
    vo.setCode("stub");
    vo.setVersion(1);
    return vo;
  }

  private String readClasspath(String path) {
    try (InputStream is = new ClassPathResource(path).getInputStream()) {
      return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 classpath 资源失败: " + path, e);
    }
  }

  /** 从 V35 迁移中抽取指定 code 的 content 字面量并还原 MySQL 转义（种子内容一致性锁）。 */
  private String seedContent(String code) {
    String sql = readClasspath(V35_SQL_PATH);
    Matcher matcher =
        Pattern.compile("VALUES \\('" + Pattern.quote(code) + "',[^\\n]*\\n\\s*'([^']*)'")
            .matcher(sql);
    assertThat(matcher.find()).as("V35 应含 code=%s 的种子模板", code).isTrue();
    return matcher.group(1).replace("\\r\\n", "\r\n").replace("\\n", "\n");
  }

  /** 行尾归一：仓库 canonical 为 LF（.gitattributes eol=lf），Windows 检出可能为 CRLF，比对前统一到 LF。 */
  private String normalizeEol(String text) {
    return text.replace("\r\n", "\n");
  }
}
