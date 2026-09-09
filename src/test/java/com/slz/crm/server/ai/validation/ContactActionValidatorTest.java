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
 * ContactActionValidator 单测：客户双通道解析 + 必填姓名
 */
@ExtendWith(MockitoExtension.class)
class ContactActionValidatorTest {

    private static final Validator JSR_VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private AiEntityResolver entityResolver;

    private ContactActionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ContactActionValidator();
        ReflectionTestUtils.setField(validator, "validator", JSR_VALIDATOR);
        ReflectionTestUtils.setField(validator, "entityResolver", entityResolver);
    }

    @Test
    void validContact_passes() {
        when(entityResolver.resolveCustomerCompany(eq(5L), any())).thenReturn(resolved(5L));
        String payload = "{\"companyId\":5,\"name\":\"张三\",\"position\":\"采购\"}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void companyNameResolved_writesBack() {
        when(entityResolver.resolveCustomerCompany(any(), eq("测试公司"))).thenReturn(resolved(9L));
        String payload = "{\"companyName\":\"测试公司\",\"name\":\"张三\"}";

        AiValidationResult result = validator.validate(payload);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolvedPayload()).contains("\"companyId\":9");
    }

    @Test
    void missingCompanyAndName_fails() {
        when(entityResolver.resolveCustomerCompany(any(), any()))
                .thenReturn(new AiEntityResolver.Resolution(AiEntityResolver.Status.MISSING, null, List.of()));

        AiValidationResult result = validator.validate("{\"position\":\"采购\"}");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("companyId", "name");
    }

    @Test
    void companyNotFound_fails() {
        when(entityResolver.resolveCustomerCompany(any(), eq("不存在")))
                .thenReturn(new AiEntityResolver.Resolution(AiEntityResolver.Status.NOT_FOUND, null, List.of()));

        AiValidationResult result = validator.validate("{\"companyName\":\"不存在\",\"name\":\"张三\"}");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingFields()).contains("companyId");
    }

    private AiEntityResolver.Resolution resolved(Long id) {
        return new AiEntityResolver.Resolution(AiEntityResolver.Status.RESOLVED, id, List.of());
    }
}
