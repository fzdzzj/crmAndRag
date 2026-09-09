package com.slz.crm.server.ai;

import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class AiChatPromptService {

    private static final String SYSTEM_PROMPT_PATH = "prompts/system-prompt.txt";
    private static final String TITLE_PROMPT_PATH = "prompts/session-title.txt";

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private AiMessageService aiMessageService;

    /** 提示词总字符闸门；历史超限时按最旧优先丢弃，必要 system/user 不裁剪。 */
    @Value("${ai.prompt-max-chars:3400}")
    private int promptMaxChars = 3400;

    private String systemPrompt;
    private String titlePrompt;
    private String promptVersion;

    @PostConstruct
    void loadPrompts() {
        loadPrompts(SYSTEM_PROMPT_PATH, TITLE_PROMPT_PATH);
    }

    void loadPrompts(String systemPromptPath, String titlePromptPath) {
        systemPrompt = loadPromptFile(systemPromptPath);
        validatePrompt(systemPromptPath, systemPrompt);
        titlePrompt = loadPromptFile(titlePromptPath);
        validatePrompt(titlePromptPath, titlePrompt);
        promptVersion = sha256Prefix(systemPrompt);
    }

    public List<Message> buildMessages(Long sessionId, String message) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(loadHistory(sessionId));
        messages.add(new UserMessage(message));
        return applyPromptBudget(messages);
    }

    public String generateTitle(ChatClient.Builder chatClientBuilder, String userMessage) {
        try {
            String title = chatClientBuilder.build().prompt()
                    .system(titlePrompt)
                    .user(userMessage)
                    .call()
                    .content();
            return title == null ? null : AiThinkTagStripper.strip(title).trim();
        } catch (Exception e) {
            log.warn("生成会话标题失败", e);
            return null;
        }
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    private List<Message> loadHistory(Long sessionId) {
        int maxRounds = aiProperties.getMaxHistoryRounds() == null ? 10 : aiProperties.getMaxHistoryRounds();
        List<AiMessageEntity> recent = aiMessageService.listRecentContextMessages(sessionId, maxRounds * 2);
        List<Message> history = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            AiMessageEntity entity = recent.get(i);
            String content = entity.getContent() == null ? "" : entity.getContent();
            if ("user".equals(entity.getRole())) {
                history.add(new UserMessage(content));
            } else {
                history.add(new AssistantMessage(AiThinkTagStripper.strip(content)));
            }
        }
        return history;
    }

    /**
     * 保障 prompt 总量不超过配置上限；超限只牺牲最旧历史，不破坏本轮必要输入。
     */
    private List<Message> applyPromptBudget(List<Message> messages) {
        int totalLength = messages.stream()
                .mapToInt(item -> item.getText() == null ? 0 : item.getText().length()).sum();
        while (totalLength > promptMaxChars && messages.size() > 2) {
            String removedText = messages.remove(1).getText();
            totalLength -= removedText == null ? 0 : removedText.length();
            log.info("Prompt 超出预算，移除最旧历史: promptMaxChars={}, totalLength={}", promptMaxChars, totalLength);
        }
        return messages;
    }

    private String loadPromptFile(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载提示词文件失败: " + path, e);
        }
    }

    private void validatePrompt(String path, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("提示词文件内容不能为空白: " + path);
        }
    }

    private String sha256Prefix(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
            StringBuilder version = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) {
                version.append(String.format("%02x", hash[i]));
            }
            return version.toString();
        } catch (Exception e) {
            log.warn("计算 prompt 版本哈希失败", e);
            return "unknown";
        }
    }
}
