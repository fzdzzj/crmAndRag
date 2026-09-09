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
 * InvoiceActionValidator 单测：合同双通道解析 + 必填发票号与金额
 */
@ExtendWith(MockitoExtension.class)
class InvoiceActionValidatorTest {

    private static final Validator JSR_VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private AiEntityResolver entityResolver;

    private InvoiceActionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new InvoiceActionValidator();
        ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
        ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
    }

    @Test
    void validInvoice_passes() {
        when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));
        String payload = "{\"contractId\":5,\"invoiceNo\":\"INV001\",\"invoiceAmount\":1000}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void missingInvoiceNo_fails() {
        when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));

        AiValidationResult result = validator.validate("{\"contractId\":5,\"invoiceAmount\":1000}");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("invoiceNo");
    }

    @Test
    void missingAmount_fails() {
        when(entityResolver.resolveContract(eq(5L), any())).thenReturn(resolved(5L));

        AiValidationResult result = validator.validate("{\"contractId\":5,\"invoiceNo\":\"INV001\"}");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("invoiceAmount");
    }

    private AiEntityResolver.Resolution resolved(Long id) {
        return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
    }
}
