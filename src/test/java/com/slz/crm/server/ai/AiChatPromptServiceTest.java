package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import com.slz.crm.server.service.PromptTemplateService;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

  // ===== 槽位双模式注入（add-prompt-template-slots 任务 2.1/2.3） =====

  /** V37 标记骨架 = 静态骨架尾部附四槽位标记；静态骨架复用 classpath 文件（其尾部含换行符）。 */
  private static final String SLOT_MARKER_TAIL =
      "{{#slot:currentIntent}}{{#slot:confirmedFacts}}{{#slot:historySummary}}{{#slot:clarify}}";

  @Test
  @DisplayName("注入模式：标记态骨架 → 组装 slots 交 render 替换，返回注入成品")
  void buildSystemPrompt_injectsSlotValuesOnMarkerTemplate() {
    String skeleton = readClasspath(SYSTEM_PROMPT_PATH);
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    // 首次 render（骨架检测，slots=null）返回含标记骨架；二次 render（注入，slots 非 null）返回注入成品
    when(templateService.render(any()))
        .thenAnswer(
            invocation -> {
              PromptTemplateRenderDTO dto = invocation.getArgument(0);
              Map<String, String> slots = dto.getSlots();
              if (slots == null) {
                return renderVo(skeleton + SLOT_MARKER_TAIL);
              }
              String injected = skeleton + SLOT_MARKER_TAIL;
              for (Map.Entry<String, String> e : slots.entrySet()) {
                injected = injected.replace("{{#slot:" + e.getKey() + "}}", e.getValue());
              }
              return renderVo(injected);
            });
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    wireShortQuestionRewriter(service);

    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    memory.setIntent("跟进A");
    memory.setFacts("已确认XYZ");
    memory.setSummary("历史B");
    String prompt = invokeBuildSystemPrompt(service, memory, true);

    String expect =
        skeleton
            + "\n\n【当前意图】跟进A\n\n【已确认事实】已确认XYZ\n\n【历史摘要】历史B"
            + "\n\n【澄清要求】用户问题缺少必要锚点，回答前必须先列出候选选项请用户确认，不要猜测业务对象。";
    assertThat(prompt).isEqualTo(expect);
    assertThat(service.getPromptSource()).isEqualTo(PromptSource.TEMPLATE);
    // 注入 render 确实携 slots（复用同一 DTO）
    ArgumentCaptor<PromptTemplateRenderDTO> captor =
        ArgumentCaptor.forClass(PromptTemplateRenderDTO.class);
    verify(templateService, org.mockito.Mockito.times(2)).render(captor.capture());
    assertThat(captor.getAllValues().get(1).getSlots())
        .containsEntry("currentIntent", "\n\n【当前意图】跟进A");
  }

  @Test
  @DisplayName("注入模式：marker 骨架但动态段全空 → 槽位替换为空串，输出即静态骨架")
  void buildSystemPrompt_injectionWithAllSectionsEmpty() {
    String skeleton = readClasspath(SYSTEM_PROMPT_PATH);
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any()))
        .thenAnswer(
            invocation -> {
              PromptTemplateRenderDTO dto = invocation.getArgument(0);
              if (dto.getSlots() == null) {
                return renderVo(skeleton + SLOT_MARKER_TAIL); // 骨架检测：标记保留
              }
              return renderVo(skeleton); // 注入（slots 空表非 null）→ 标记全部替换为空串 → 静态骨架
            });
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    wireShortQuestionRewriter(service);

    AiConversationMemoryEntity memory = new AiConversationMemoryEntity(); // 全空
    String prompt = invokeBuildSystemPrompt(service, memory, false);

    assertThat(prompt).isEqualTo(skeleton);
  }

  @Test
  @DisplayName("回归锁：无标记模板（V35/静态）走旧追加路径逐字节不变（memory 分支 + clarify 分支）")
  void buildSystemPrompt_legacyAppendWithoutMarkers() {
    String skeleton = readClasspath(SYSTEM_PROMPT_PATH);
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenReturn(renderVo(skeleton)); // 无标记（slots=null）
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    wireShortQuestionRewriter(service);

    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    memory.setIntent("意图A");
    memory.setSummary("历史B");
    String prompt = invokeBuildSystemPrompt(service, memory, true);

    // 旧追加路径逐字节：骨架 + 段（facts 空不追加）+ clarify
    String expect =
        skeleton
            + "\n\n【当前意图】意图A\n\n【历史摘要】历史B"
            + "\n\n【澄清要求】用户问题缺少必要锚点，回答前必须先列出候选选项请用户确认，不要猜测业务对象。";
    assertThat(prompt).isEqualTo(expect);
    // 只 render 一次（无标记不进注入二次渲染）
    verify(templateService, org.mockito.Mockito.times(1)).render(any());
  }

  @Test
  @DisplayName("回退链：无标记静态回退态走旧追加路径（恒不入注入模式）")
  void buildSystemPrompt_fallbackStaticStillAppendsLegacy() {
    // render 抛异常 → resolveSystemSkeleton 回退静态文本（无标记），旧追加路径逐字节
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenThrow(new IllegalStateException("probe"));
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);

    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    memory.setIntent("意图A");
    String prompt = invokeBuildSystemPrompt(service, memory, false);

    String expect = readClasspath(SYSTEM_PROMPT_PATH) + "\n\n【当前意图】意图A";
    assertThat(prompt).isEqualTo(expect);
    assertThat(service.getPromptSource()).isEqualTo(PromptSource.FALLBACK);
  }

  // ===== 检索上下文槽位注入（add-customer-semantic-retrieval 任务 3.3） =====

  @Test
  @DisplayName("任务 3.3：槽位模式检索上下文随系统提示词注入，injected=true")
  void buildMessages_slotModeInjectsRetrievalContext() {
    String skeleton = readClasspath(SYSTEM_PROMPT_PATH);
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any()))
        .thenAnswer(
            invocation -> {
              PromptTemplateRenderDTO dto = invocation.getArgument(0);
              if (dto.getSlots() == null) {
                return renderVo(skeleton + SLOT_MARKER_TAIL + "{{#slot:retrievedContext}}");
              }
              String injected = skeleton + SLOT_MARKER_TAIL + "{{#slot:retrievedContext}}";
              for (Map.Entry<String, String> e : dto.getSlots().entrySet()) {
                injected = injected.replace("{{#slot:" + e.getKey() + "}}", e.getValue());
              }
              return renderVo(injected);
            });
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    wireShortQuestionRewriter(service);
    wireMessageInfrastructure(service);

    AiChatPromptService.PreparedTurn prepared = service.prepareTurn(9L, "客户问题");
    String retrievalContext = "【业务数据检索】……\n\n[1] 公司名称： 甲公司";

    AiChatPromptService.PreparedMessages preparedMessages =
        service.buildMessages(9L, prepared, retrievalContext);

    assertThat(preparedMessages.retrievalContextInjected()).isTrue();
    assertThat(preparedMessages.messages().get(0).getText()).contains(retrievalContext);
    ArgumentCaptor<PromptTemplateRenderDTO> captor =
        ArgumentCaptor.forClass(PromptTemplateRenderDTO.class);
    verify(templateService, org.mockito.Mockito.times(2)).render(captor.capture());
    assertThat(captor.getAllValues().get(1).getSlots())
        .containsEntry("retrievedContext", retrievalContext);
  }

  @Test
  @DisplayName("任务 3.3 回归锁：无标记模板（旧追加路径）不注入检索上下文，injected=false")
  void buildMessages_legacyAppendDoesNotInjectRetrievalContext() {
    String skeleton = readClasspath(SYSTEM_PROMPT_PATH);
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenReturn(renderVo(skeleton)); // 无标记（slots=null）
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    wireShortQuestionRewriter(service);
    wireMessageInfrastructure(service);

    AiChatPromptService.PreparedTurn prepared = service.prepareTurn(9L, "客户问题");

    AiChatPromptService.PreparedMessages preparedMessages =
        service.buildMessages(9L, prepared, "【业务数据检索】片段");

    assertThat(preparedMessages.retrievalContextInjected()).isFalse();
    assertThat(preparedMessages.messages().get(0).getText()).doesNotContain("【业务数据检索】");
    assertThat(preparedMessages.messages().get(preparedMessages.messages().size() - 1).getText())
        .isEqualTo("改写后问题");
  }

  @Test
  @DisplayName("任务 3.3 回归锁：回退态（render 异常）不注入检索上下文，injected=false")
  void buildMessages_fallbackStateDoesNotInjectRetrievalContext() {
    PromptTemplateService templateService = mock(PromptTemplateService.class);
    when(templateService.render(any())).thenThrow(new IllegalStateException("probe"));
    AiChatPromptService service = newLoadedService();
    wireTemplate(service, templateService);
    wireShortQuestionRewriter(service);
    wireMessageInfrastructure(service);

    AiChatPromptService.PreparedTurn prepared = service.prepareTurn(9L, "客户问题");

    AiChatPromptService.PreparedMessages preparedMessages =
        service.buildMessages(9L, prepared, "【业务数据检索】片段");

    assertThat(preparedMessages.retrievalContextInjected()).isFalse();
    assertThat(preparedMessages.messages().get(0).getText()).doesNotContain("【业务数据检索】");
  }

  // ===== 测试工具 =====

  /** 经反射调用私有 buildSystemPrompt 并携带 memory/clarify（槽位双模式测试用；任务 3.3 起三参签名取 prompt 段）。 */
  private String invokeBuildSystemPrompt(
      AiChatPromptService service, AiConversationMemoryEntity memory, boolean clarifyRequired) {
    Method method =
        ReflectionUtils.findMethod(
            AiChatPromptService.class,
            "buildSystemPrompt",
            AiConversationMemoryEntity.class,
            boolean.class,
            String.class);
    ReflectionUtils.makeAccessible(method);
    return ((AiChatPromptService.SystemPromptBuild)
            ReflectionUtils.invokeMethod(method, service, memory, clarifyRequired, null))
        .prompt();
  }

  private AiChatPromptService newLoadedService() {
    AiChatPromptService service = new AiChatPromptService();
    service.loadPrompts(SYSTEM_PROMPT_PATH, TITLE_PROMPT_PATH);
    return service;
  }

  private void wireTemplate(AiChatPromptService service, PromptTemplateService templateService) {
    ReflectionTestUtils.setField(service, "promptTemplateService", templateService);
  }

  /** 装配 shortQuestionRewriter mock（clarify 常量 + rewrite 桩，供槽位注入与 prepareTurn 链路）。 */
  private void wireShortQuestionRewriter(AiChatPromptService service) {
    AiShortQuestionRewriter rewriter = mock(AiShortQuestionRewriter.class);
    when(rewriter.clarifyPrompt()).thenReturn("\n\n【澄清要求】用户问题缺少必要锚点，回答前必须先列出候选选项请用户确认，不要猜测业务对象。");
    when(rewriter.rewrite(any(), any(), any()))
        .thenReturn(new AiShortQuestionRewriter.RewrittenQuestion("改写后问题", false));
    ReflectionTestUtils.setField(service, "shortQuestionRewriter", rewriter);
  }

  /** 装配消息基建 mock：findLastUserQuestion/loadHistory 走空历史（槽位注入链路测试用）。 */
  private void wireMessageInfrastructure(AiChatPromptService service) {
    AiMessageService aiMessageService = mock(AiMessageService.class);
    when(aiMessageService.listRecentContextMessages(anyLong(), anyInt())).thenReturn(List.of());
    ReflectionTestUtils.setField(service, "aiMessageService", aiMessageService);
    ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
  }

  /** 经反射调用私有 buildSystemPrompt；memory 全字段为空 → 返回值即静态骨架（不含动态追加段）。 */
  private String buildSystemSkeleton(AiChatPromptService service) {
    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    Method method =
        ReflectionUtils.findMethod(
            AiChatPromptService.class,
            "buildSystemPrompt",
            AiConversationMemoryEntity.class,
            boolean.class,
            String.class);
    ReflectionUtils.makeAccessible(method);
    return ((AiChatPromptService.SystemPromptBuild)
            ReflectionUtils.invokeMethod(method, service, memory, false, null))
        .prompt();
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
