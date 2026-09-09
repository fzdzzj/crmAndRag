package com.slz.crm.pojo.dto;

import lombok.Data;

import java.util.List;

/**
 * 在同一业务记录上追加新的协助人。
 * 原有协助记录保持不变，列表中的每一项代表一位新的协助人。
 */
@Data
public class AssistAppendDTO {
    /** 作为追加目标的原协助记录ID。 */
    private Long originalAssistId;

    /** 新增协助人及其独立目的/要求。 */
    private List<AssistApplyItem> assistApplyList;
}
