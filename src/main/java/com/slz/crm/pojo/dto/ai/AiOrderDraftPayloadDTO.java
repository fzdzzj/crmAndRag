package com.slz.crm.pojo.dto.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 追加订单草稿 payload（actionType = CREATE_ORDER）
 */
@Data
public class AiOrderDraftPayloadDTO {
    @Valid @NotEmpty
    private List<AiOrderItemDraftDTO> orders;
}