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
import static org.mockito.Mockito.when;

/**
 * OrderActionValidator 单测：正反例 + 实体解析四态 + 金额一致性规则
 */
@ExtendWith(MockitoExtension.class)
class OrderActionValidatorTest {

    private static final Validator JSR_VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private AiEntityResolver entityResolver;

    private OrderActionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OrderActionValidator();
        ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
        ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
    }

    @Test
    void actionType_isCreateOrder() {
        assertThat(validator.actionType()).isEqualTo("CREATE_ORDER");
    }

    @Test
    void validOrder_passes() {
        when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));
        String payload = "{\"orders\":[{\"contractId\":5,\"productName\":\"产品A\",\"quantity\":2,\"unitPrice\":50,\"amount\":100}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolvedPayload()).isNull();
    }

    @Test
    void nameResolved_writesBackPayload() {
        when(entityResolver.resolveContract(any(), eq("某合同"))).thenReturn(resolved(7L));
        String payload = "{\"orders\":[{\"contractName\":\"某合同\",\"productName\":\"产品A\",\"quantity\":2,\"unitPrice\":50,\"amount\":100}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolvedPayload()).isNotNull();
        assertThat(result.getResolvedPayload()).contains("\"contractId\":7");
    }

    @Test
    void missingFields_triggersQuestions() {
        when(entityResolver.resolveContract(any(), any())).thenReturn(resolved(5L));
        String payload = "{\"orders\":[{\"contractId\":5}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("productName", "quantity", "amount");
        assertThat(result.getQuestions()).isNotEmpty();
    }

    @Test
    void amountMismatch_fails() {
        when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));
        String payload = "{\"orders\":[{\"contractId\":5,\"productName\":\"产品A\",\"quantity\":2,\"unitPrice\":50,\"amount\":999}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("amount");
    }

    @Test
    void amountDerivable_passes() {
        when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));
        String payload = "{\"orders\":[{\"contractId\":5,\"productName\":\"产品A\",\"quantity\":2,\"unitPrice\":50}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void invalidJson_fails() {
        AiValidationResult result = validator.validate("not-json");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("orders");
    }

    @Test
    void emptyOrders_fails() {
        AiValidationResult result = validator.validate("{\"orders\":[]}");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("orders");
    }

    @Test
    void ambiguousContract_asksCandidates() {
        when(entityResolver.resolveContract(any(), eq("模糊")))
                .thenReturn(new AiEntityResolver.Resolution(AiEntityResolver.Status.AMBIGUOUS, null, List.of("合同A", "合同B")));
        String payload = "{\"orders\":[{\"contractName\":\"模糊\",\"productName\":\"产品A\",\"quantity\":1,\"amount\":10}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("contractId");
        assertThat(result.getQuestions().get(0)).contains("合同A");
    }

    @Test
    void notFoundContract_fails() {
        when(entityResolver.resolveContract(eq(999L), any()))
                .thenReturn(new AiEntityResolver.Resolution(AiEntityResolver.Status.NOT_FOUND, null, List.of()));
        String payload = "{\"orders\":[{\"contractId\":999,\"productName\":\"产品A\",\"quantity\":1,\"amount\":10}]}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("contractId");
    }

    private AiEntityResolver.Resolution resolved(Long id) {
        return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
    }
}
