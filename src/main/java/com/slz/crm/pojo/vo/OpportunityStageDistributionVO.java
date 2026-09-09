package com.slz.crm.pojo.vo;

import lombok.Data;

/**
 * 商机阶段分布视图对象
 * 用于返回各阶段商机的数量和占比
 */
@Data
public class OpportunityStageDistributionVO {
    /**
     * 阶段0：种子商机数量
     */
    private Long stage0Count;

    /**
     * 阶段0：种子商机占比
     */
    private Double stage0Percentage;

    /**
     * 阶段1：潜在商机数量
     */
    private Long stage1Count;

    /**
     * 阶段1：潜在商机占比
     */
    private Double stage1Percentage;

    /**
     * 阶段2：确认商机数量
     */
    private Long stage2Count;

    /**
     * 阶段2：确认商机占比
     */
    private Double stage2Percentage;

    /**
     * 阶段3：储备项目数量
     */
    private Long stage3Count;

    /**
     * 阶段3：储备项目占比
     */
    private Double stage3Percentage;

    /**
     * 阶段4：立项签约数量
     */
    private Long stage4Count;

    /**
     * 阶段4：立项签约占比
     */
    private Double stage4Percentage;

    /**
     * 阶段5：关闭数量
     */
    private Long stage5Count;

    /**
     * 阶���5：关闭占比
     */
    private Double stage5Percentage;

    /**
     * 商机总数（用于计算占比的分母）
     */
    private Long totalCount;
}
