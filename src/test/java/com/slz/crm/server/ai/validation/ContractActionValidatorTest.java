package com.slz.crm.server.ai.validation;

import com.slz.crm.server.ai.AiEntityResolver;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * ContractActionValidator 单测：商机双通道解析 + 合同字段与订单明细校验
 */
@ExtendWith(MockitoExtension.class)
class ContractActionValidatorTest {

    private static final Validator JSR_VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private AiEntityResolver entityResolver;

    private ContractActionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ContractActionValidator();
        ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
        ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
    }

    @Test
    void validContract_passes() {
        when(entityResolver.resolveOpportunity(eq(9L), isNull(), isNull(), isNull())).thenReturn(resolved(9L));
        String payload = "{\"contractName\":\"合同A\",\"opportunityId\":9,\"totalAmount\":1000,"
                + "\"orders\":[{\"productName\":\"产品A\",\"quantity\":2,\"unitPrice\":500,\"amount\":1000}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void opportunityNameResolved_writesBack() {
        when(entityResolver.resolveOpportunity(isNull(), eq("某商机"), isNull(), isNull())).thenReturn(resolved(11L));
        String payload = "{\"contractName\":\"合同A\",\"opportunityName\":\"某商机\",\"totalAmount\":1000,"
                + "\"orders\":[{\"productName\":\"产品A\",\"quantity\":1,\"amount\":1000}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolvedPayload()).contains("\"opportunityId\":11");
    }

    @Test
    void missingContractFields_fails() {
        when(entityResolver.resolveOpportunity(any(), any(), any(), any())).thenReturn(resolved(9L));

        AiValidationResult result = validator.validate("{\"opportunityId\":9}");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("contractName", "totalAmount", "orders");
    }

    @Test
    void orderItemMissingProduct_fails() {
        when(entityResolver.resolveOpportunity(eq(9L), isNull(), isNull(), isNull())).thenReturn(resolved(9L));
        String payload = "{\"contractName\":\"合同A\",\"opportunityId\":9,\"totalAmount\":1000,"
                + "\"orders\":[{\"quantity\":2,\"amount\":1000}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("productName");
    }

    @Test
    void ambiguousOpportunity_asksCandidates() {
        when(entityResolver.resolveOpportunity(any(), eq("模糊"), isNull(), isNull()))
                .thenReturn(new AiEntityResolver.Resolution(AiEntityResolver.Status.AMBIGUOUS, null, List.of("商机A", "商机B")));
        String payload = "{\"contractName\":\"合同A\",\"opportunityName\":\"模糊\",\"totalAmount\":1000,"
                + "\"orders\":[{\"productName\":\"产品A\",\"quantity\":1,\"amount\":1000}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("opportunityId");
        assertThat(result.getQuestions().get(0)).contains("商机A");
    }

    private AiEntityResolver.Resolution resolved(Long id) {
        return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
    }
}
