package com.slz.crm.server.ai.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 客户 360° 诊断服务单测（add-ai-customer-360-insight 任务 5.2/5.3）。
 *
 * <p>覆盖：ModelProvider Mock 结构化输出、模型异常/未装配/非法输出安全回退零 500、DataScope 反向拦截 12002、 客户不存在 20001。
 */
class CustomerInsightServiceTest {

  private CustomerCompanyMapper companyMapper;
  private BusinessRecordAccessService recordAccessService;
  private CustomerInsightCollectorSupport collector;
  private ModelProvider modelProvider;
  private ObjectProvider<ModelProvider> modelProviderProvider;
  private ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;
  private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private CustomerInsightService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    companyMapper = Mockito.mock(CustomerCompanyMapper.class);
    recordAccessService = Mockito.mock(BusinessRecordAccessService.class);
    collector = Mockito.mock(CustomerInsightCollectorSupport.class);
    modelProvider = Mockito.mock(ModelProvider.class);
    modelProviderProvider = Mockito.mock(ObjectProvider.class);
    tokenUsageRecorderProvider = Mockito.mock(ObjectProvider.class);
    dynamicConfigProvider = Mockito.mock(ObjectProvider.class);
    Executor directExecutor = Runnable::run;
    service =
        new CustomerInsightService(
            collector,
            new CustomerInsightRuleEngine(),
            companyMapper,
            recordAccessService,
            modelProviderProvider,
            tokenUsageRecorderProvider,
            dynamicConfigProvider,
            directExecutor);
    lenient()
        .when(collector.collect(any(CustomerCompanyEntity.class)))
        .thenReturn(healthySnapshot());
  }

  /** 无风险客户快照：规则评分恒 90（EXCELLENT），便于聚焦模型/降级/权限路径断言。 */
  private CustomerInsightSnapshot healthySnapshot() {
    return new CustomerInsightSnapshot(
        1001L,
        "北京测试科技有限公司",
        "软件",
        "正常",
        7L,
        9L,
        new CustomerInsightSnapshot.ActivityFacts(
            2, 2, LocalDateTime.now().minusDays(3), 3, "产品演示会"),
        new CustomerInsightSnapshot.OpportunityFacts(0, BigDecimal.ZERO, 0, 0),
        new CustomerInsightSnapshot.ContractPaymentFacts(
            0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
        new CustomerInsightSnapshot.TaskContactFacts(0, 0, 2, 1));
  }

  @Test
  void diagnose_withMockModel_parsesStructuredLlmOutput() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
    String llmJson =
        "{\"summary\":\"客户整体健康，健康分 90，需保持跟进\","
            + "\"riskAlerts\":[\"存在待确认回款\"],"
            + "\"actionSuggestions\":[\"本周安排拜访\",\"核对回款进度\"]}";
    when(modelProvider.chat(any(Prompt.class), any()))
        .thenReturn(ModelCallResult.ofText(llmJson, "qwen-plus", 100L, 50L, 150L));

    CustomerInsightResult result = service.diagnose(1001L);

    assertThat(result.healthScore()).isEqualTo(90);
    assertThat(result.healthLevel()).isEqualTo(CustomerInsightHealthLevel.EXCELLENT);
    assertThat(result.llmEnhanced()).isTrue();
    assertThat(result.summary()).contains("客户整体健康");
    assertThat(result.riskAlerts()).containsExactly("存在待确认回款");
    assertThat(result.actionSuggestions()).containsExactly("本周安排拜访", "核对回款进度");
  }

  @Test
  void diagnose_modelThrows_fallsBackToRuleTextWithout500() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
    when(modelProvider.chat(any(Prompt.class), any()))
        .thenThrow(new RuntimeException("provider timeout"));

    CustomerInsightResult result = service.diagnose(1001L);

    assertThat(result.llmEnhanced()).isFalse();
    assertThat(result.healthScore()).isEqualTo(90);
    assertThat(result.summary()).contains("健康分 90");
    assertThat(result.riskAlerts()).isEmpty();
    assertThat(result.actionSuggestions()).contains("建议保持定期跟进，持续巩固客户关系");
  }

  @Test
  void diagnose_modelNotAssembled_fallsBackToRuleText() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    when(modelProviderProvider.getIfAvailable()).thenReturn(null);

    CustomerInsightResult result = service.diagnose(1001L);

    assertThat(result.llmEnhanced()).isFalse();
    assertThat(result.healthScore()).isEqualTo(90);
  }

  @Test
  void diagnose_modelOutputInvalidJson_fallsBackToRuleText() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
    when(modelProvider.chat(any(Prompt.class), any()))
        .thenReturn(ModelCallResult.ofText("not-a-json", "qwen-plus", 10L, 5L, 15L));

    CustomerInsightResult result = service.diagnose(1001L);

    assertThat(result.llmEnhanced()).isFalse();
    assertThat(result.healthScore()).isEqualTo(90);
  }

  @Test
  void diagnose_modelOutputMissingFields_fallsBackToRuleText() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
    when(modelProvider.chat(any(Prompt.class), any()))
        .thenReturn(ModelCallResult.ofText("{\"summary\":\"只有摘要\"}", "qwen-plus", 5L, 2L, 7L));

    CustomerInsightResult result = service.diagnose(1001L);

    assertThat(result.llmEnhanced()).isFalse();
  }

  @Test
  void diagnose_outOfScopeCompany_returnsPermissionDenied12002() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    Mockito.doThrow(new BaseException(ErrorCode.PERMISSION_DENIED, "无权查看该业务记录"))
        .when(recordAccessService)
        .assertCanReadCompany(company);

    BaseException exception =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> service.diagnose(1001L), BaseException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.getCode()).isEqualTo(ErrorCode.PERMISSION_DENIED.getCode());
  }

  @Test
  void diagnose_companyNotExists_returns20001() {
    when(companyMapper.selectById(9999L)).thenReturn(null);

    BaseException exception =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> service.diagnose(9999L), BaseException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.getCode()).isEqualTo(ErrorCode.COMPANY_NOT_EXISTS.getCode());
  }

  @Test
  void diagnose_nullCompanyId_throwsParamRequired() {
    BaseException exception =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> service.diagnose(null), BaseException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.getCode()).isEqualTo(ErrorCode.PARAM_REQUIRED.getCode());
  }

  @Test
  void diagnose_guardAllows_ownerAndSupervisorCanRead() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    when(modelProviderProvider.getIfAvailable()).thenReturn(null);
    Mockito.doReturn(false).when(recordAccessService).assertCanReadCompany(company);

    CustomerInsightResult result = service.diagnose(1001L);

    assertThat(result.companyId()).isEqualTo(1001L);
    verify(recordAccessService).assertCanReadCompany(company);
  }

  @Test
  void diagnose_successfulModelCall_recordsTokenUsage() {
    CustomerCompanyEntity company = company(1001L);
    when(companyMapper.selectById(1001L)).thenReturn(company);
    when(modelProviderProvider.getIfAvailable()).thenReturn(modelProvider);
    TokenUsageRecorder recorder = mock(TokenUsageRecorder.class);
    when(tokenUsageRecorderProvider.getIfAvailable()).thenReturn(recorder);
    when(modelProvider.chat(any(Prompt.class), any()))
        .thenReturn(
            ModelCallResult.ofText(
                "{\"summary\":\"客户健康\",\"riskAlerts\":[\"风险\"],\"actionSuggestions\":[\"建议\"]}",
                "qwen-plus",
                100L,
                50L,
                150L));

    service.diagnose(1001L);

    ArgumentCaptor<com.slz.crm.platform.contract.TokenUsageRecord> captor =
        ArgumentCaptor.forClass(com.slz.crm.platform.contract.TokenUsageRecord.class);
    verify(recorder).record(captor.capture());
    assertThat(captor.getValue().model()).isEqualTo("qwen-plus");
    assertThat(captor.getValue().success()).isTrue();
  }

  private CustomerCompanyEntity company(Long id) {
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setId(id);
    company.setCompanyName("北京测试科技有限公司");
    company.setIndustry("软件");
    company.setStatus("正常");
    company.setOwnerId(7L);
    company.setCreatorId(9L);
    return company;
  }
}
