package com.slz.crm.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审批员VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditorVO {
    /**
     * 用户ID
     */
    private Long id;

    /**
     * 用户名（真实姓名）
     */
    private String username;
}
