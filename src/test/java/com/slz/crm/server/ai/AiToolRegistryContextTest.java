package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AiToolCallLogEntity;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.server.mapper.AiToolCallLogMapper;
import com.slz.crm.server.properties.AiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

/** 用户上下文传播单测：异步注册不崩、工具执行线程身份恢复与清理、低权数据面收窄 */
class AiToolRegistryContextTest {

  private AiToolRegistry registry;

  private AiToolCallbackFactory callbackFactory;

  private AiToolCallLogMapper aiToolCallLogMapper;

  /** 独立内存指标注册表，用于验证工具执行埋点 */
  private io.micrometer.core.instrument.MeterRegistry meterRegistry;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    java.util.concurrent.Executor directExecutor = command -> command.run();
    callbackFactory = new AiToolCallbackFactory();
    // 工具指标使用独立 SimpleMeterRegistry，与异步审计日志分开验证
    meterRegistry = new SimpleMeterRegistry();
    ReflectionTestUtils.setField(
        callbackFactory, "metrics", new AiChatMetrics(meterRegistry, new AiStreamRegistry()));
    ReflectionTestUtils.setField(callbackFactory, "aiProperties", new AiProperties());
    ReflectionTestUtils.setField(callbackFactory, "aiAuditExecutor", directExecutor);
    aiToolCallLogMapper = Mockito.mock(AiToolCallLogMapper.class);
    ReflectionTestUtils.setField(callbackFactory, "aiToolCallLogMapper", aiToolCallLogMapper);
    registry =
        new AiToolRegistry(
            callbackFactory,
            Mockito.mock(AiQueryToolExecutors.class),
            Mockito.mock(AiDraftToolExecutors.class));
    BaseUnit.removeCurrentId();
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  void nullUser_registersPermissionFreeToolsOnly_withoutNpe() {
    List<ToolCallback> callbacks = registry.getPermittedToolCallbacks(null);

    List<String> names = callbacks.stream().map(cb -> cb.getToolDefinition().name()).toList();

    assertThat(names)
        .contains(
            "getChartData", "getStatisticsSummary", "createCustomerDraft", "createContractDraft");
    assertThat(names)
        .doesNotContain(
            "getOpportunityStageDistribution",
            "queryOrder",
            "queryContract",
            "queryCustomerCompany",
            "queryOpportunity",
            "queryContact",
            "queryPayment",
            "queryInvoice",
            "queryCompanyGroup");
    assertThat(callbacks).hasSize(9);
  }

  @Test
  void toolExecution_recoversInitiatorId_andCleansUp() throws Exception {
    RoleAO user = buildUser(42L, null);
    AtomicReference<Long> seenId = new AtomicReference<>();
    AtomicReference<RoleAO> seenRole = new AtomicReference<>();
    AiToolExecutor executor =
        (args, toolContext) -> {
          seenId.set(BaseUnit.getCurrentId());
          seenRole.set(BaseUnit.getCurrentRole());
          return Map.of("ok", true);
        };

    ToolCallback callback = buildCallback(callbackFactory, "probeTool", null, executor, user);
    String result = callback.call("{}", new ToolContext(Map.of()));

    assertThat(seenId.get()).isEqualTo(42L);
    assertThat(seenRole.get()).isSameAs(user);
    assertThat(result).contains("ok");
    assertThat(BaseUnit.getCurrentRole()).isNull();
  }

  @Test
  void lowPrivilegeUser_toolSurfaceIsNarrowed() {
    RoleAO user = buildUser(7L, PermissionOperates.CUSTOMER_QUERY_COMPANY);

    List<ToolCallback> callbacks = registry.getPermittedToolCallbacks(user);

    List<String> names = callbacks.stream().map(cb -> cb.getToolDefinition().name()).toList();
    assertThat(names).contains("queryCustomerCompany", "queryCompanyGroup");
    assertThat(names)
        .doesNotContain(
            "queryContract", "getOpportunityStageDistribution", "queryPayment", "queryInvoice");
    assertThat(callbacks).hasSize(11);
  }

  @Test
  void toolExecution_writesAsyncAuditWithResultAndStatus() throws Exception {
    ToolCallback callback =
        buildCallback(
            callbackFactory, "auditProbe", null, (args, toolContext) -> Map.of("ok", true), null);

    callback.call("{\"value\":1}", new ToolContext(Map.of()));

    ArgumentCaptor<AiToolCallLogEntity> captor = ArgumentCaptor.forClass(AiToolCallLogEntity.class);
    Mockito.verify(aiToolCallLogMapper).insert(captor.capture());
    AiToolCallLogEntity log = captor.getValue();
    assertThat(log.getToolName()).isEqualTo("auditProbe");
    assertThat(log.getArgs()).contains("\"value\":1");
    assertThat(log.getResult()).contains("ok");
    assertThat(log.getSuccess()).isEqualTo(1);
    // 成功执行应同时产生次数与耗时样本
    assertThat(
            meterRegistry
                .get("ai.tool.call.total")
                .tag("tool", "auditProbe")
                .tag("status", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("ai.tool.call.duration")
                .tag("tool", "auditProbe")
                .tag("status", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void argumentError_underRepairLimitReturnsRetryHint() throws Exception {
    ToolCallback callback =
        buildCallback(
            callbackFactory,
            "repairProbe",
            null,
            (args, toolContext) -> {
              throw new IllegalArgumentException("参数不合法");
            },
            null);
    AtomicInteger repairCounter = new AtomicInteger();

    String result = callback.call("{}", new ToolContext(Map.of("repairCounter", repairCounter)));
    JsonNode json = objectMapper.readTree(result);

    assertThat(json.has("hint")).isTrue();
    assertThat(repairCounter.get()).isEqualTo(1);
    // 参数修复路径属于失败调用，也要进入失败指标
    assertThat(
            meterRegistry
                .get("ai.tool.call.total")
                .tag("tool", "repairProbe")
                .tag("status", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void argumentError_overRepairLimitReturnsTerminalMessage() throws Exception {
    ToolCallback callback =
        buildCallback(
            callbackFactory,
            "repairProbe",
            null,
            (args, toolContext) -> {
              throw new IllegalArgumentException("参数不合法");
            },
            null);
    AtomicInteger repairCounter = new AtomicInteger(2);

    String result = callback.call("{}", new ToolContext(Map.of("repairCounter", repairCounter)));
    JsonNode json = objectMapper.readTree(result);

    assertThat(json.get("error").asText()).contains("多次执行失败");
    assertThat(json.has("hint")).isFalse();
    assertThat(repairCounter.get()).isEqualTo(3);
  }

  @Test
  void toolResult_businessVoIsCollectedAsReference() throws Exception {
    CustomerCompanyVO company = new CustomerCompanyVO();
    company.setId(9L);
    company.setCompanyName("公司A");
    AiReferenceCollector collector = new AiReferenceCollector();
    ToolCallback callback =
        buildCallback(
            callbackFactory, "referenceProbe", null, (args, toolContext) -> company, null);

    callback.call("{}", new ToolContext(Map.of("references", collector)));

    assertThat(collector.getReferences())
        .containsExactly(new AiReferenceCollector.Reference("customerCompany", 9L, "公司A"));
  }

  private RoleAO buildUser(Long id, PermissionOperates permission) {
    RoleAO user = new RoleAO();
    user.setId(id);
    user.setRoleId(3L);
    if (permission != null) {
      PermissionsEntity entity = new PermissionsEntity();
      entity.setId(permission.getId());
      user.setPermissions(List.of(entity));
    }
    return user;
  }

  private ToolCallback buildCallback(
      AiToolCallbackFactory target,
      String name,
      PermissionOperates permission,
      AiToolExecutor executor,
      RoleAO user)
      throws Exception {
    return target.build(name, "test tool", "{}", permission, executor, user);
  }
}
