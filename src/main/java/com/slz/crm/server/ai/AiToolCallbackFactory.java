package com.slz.crm.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AiToolCallLogEntity;
import com.slz.crm.server.mapper.AiToolCallLogMapper;
import com.slz.crm.server.properties.AiProperties;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiToolCallbackFactory {

  @Autowired private AiToolCallLogMapper aiToolCallLogMapper;

  @Autowired private AiProperties aiProperties;

  @Autowired
  @Qualifier("aiAuditExecutor")
  private Executor aiAuditExecutor;

  /** AI 工具调用运行指标，独立于异步审计日志 */
  @Autowired private AiChatMetrics metrics;

  private final ObjectMapper objectMapper = new ObjectMapper();

  public ToolCallback build(
      String name,
      String description,
      String inputSchema,
      PermissionOperates requiredPermission,
      AiToolExecutor executor,
      RoleAO user) {
    ToolCallback result;
    if (requiredPermission != null && (user == null || !user.hasPermission(requiredPermission))) {
      result = null;
    } else {
      result =
          FunctionToolCallback.<Map<String, Object>, Object>builder(
                  name,
                  (args, toolContext) -> {
                    // 纳秒时间源用于指标耗时；毫秒时间源继续用于审计日志 costMs
                    long startNanos = System.nanoTime();
                    long startMillis = System.currentTimeMillis();
                    Object innerResult = null;
                    boolean success = true;
                    BaseUnit.setCurrentRole(user);
                    try {
                      innerResult = executor.execute(args, toolContext);
                      collectReferences(innerResult, toolContext);
                      return innerResult;
                    } catch (Exception e) {
                      success = false;
                      log.error("{} 执行失败", name, e);
                      return handleToolError(name, e, toolContext);
                    } finally {
                      logToolCall(
                          name,
                          args,
                          innerResult,
                          success,
                          System.currentTimeMillis() - startMillis);
                      if (metrics != null) {
                        // 标签只含工具名和结果，不写入业务参数或返回内容
                        metrics.recordToolCall(name, success, startNanos);
                      }
                      BaseUnit.removeCurrentId();
                    }
                  })
              .description(description)
              .inputSchema(inputSchema)
              .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
              .build();
    }
    return result;
  }

  private void collectReferences(Object result, ToolContext toolContext) {
    if (result == null
        || toolContext == null
        || toolContext.getContext() == null
        || !(toolContext.getContext().get("references")
            instanceof AiReferenceCollector collector)) {
      return;
    }
    if (result instanceof java.util.Collection<?> collection) {
      collection.forEach(collector::collect);
    } else {
      collector.collect(result);
    }
  }

  private Object handleToolError(String name, Exception e, ToolContext toolContext) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    Object result;
    if (!isArgumentError(e)) {
      result = Map.of("error", name + " 执行失败: " + message);
    } else {
      AtomicInteger repairCounter = null;
      if (toolContext != null
          && toolContext.getContext() != null
          && toolContext.getContext().get("repairCounter") instanceof AtomicInteger counter) {
        repairCounter = counter;
      }
      int maxFixRounds =
          aiProperties.getMaxFixRounds() == null ? 2 : aiProperties.getMaxFixRounds();
      if (repairCounter != null && repairCounter.incrementAndGet() > maxFixRounds) {
        result = Map.of("error", name + " 多次执行失败，请换一种方式向用户询问或请用户提供具体参数，不要继续重试");
      } else {
        result = Map.of("error", name + " 执行失败: " + message, "hint", "参数可能不合法，请根据错误信息修正参数后重试");
      }
    }
    return result;
  }

  private boolean isArgumentError(Throwable e) {
    Throwable current = e;
    boolean result = false;
    for (int i = 0; current != null && i < 5; i++) {
      if (current instanceof IllegalArgumentException
          || current instanceof ClassCastException
          || current instanceof NumberFormatException
          || current instanceof com.fasterxml.jackson.core.JsonProcessingException
          || current instanceof java.time.format.DateTimeParseException) {
        result = true;
        break;
      }
      current = current.getCause();
    }
    return result;
  }

  private void logToolCall(
      String toolName, Map<String, Object> args, Object result, boolean success, long costMs) {
    RoleAO currentUser = BaseUnit.getCurrentRole();
    Long userId = currentUser == null ? null : currentUser.getId();
    CompletableFuture.runAsync(
        () -> {
          try {
            AiToolCallLogEntity logEntity = new AiToolCallLogEntity();
            logEntity.setUserId(userId);
            logEntity.setToolName(toolName);
            logEntity.setArgs(objectMapper.writeValueAsString(args));
            String resultStr = objectMapper.writeValueAsString(result);
            logEntity.setResult(resultStr.length() > 500 ? resultStr.substring(0, 500) : resultStr);
            logEntity.setSuccess(success ? 1 : 0);
            logEntity.setCostMs((int) costMs);
            logEntity.setCreatedTime(LocalDateTime.now());
            aiToolCallLogMapper.insert(logEntity);
          } catch (Exception e) {
            log.warn("记录AI工具调用日志失败, toolName={}", toolName, e);
          }
        },
        aiAuditExecutor);
  }
}
