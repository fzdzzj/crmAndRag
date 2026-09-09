package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商机详情视图对象
 * 包含商机基本信息、业务活动（按商机状态分组）、商机状态变更记录
 */
@Data
public class OpportunityDetailVO implements Privacy {
    /**
     * 商机ID
     */
    private Long id;

    /**
     * 商机名称
     */
    private String opportunityName;

    /**
     * 公司ID
     */
    private Long companyId;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 联系人ID
     */
    private Long contactId;

    /**
     * 联系人姓名
     */
    private String contactName;

    /**
     * 商机阶段
     */
    private Integer stage;

    /**
     * 预计金额
     */
    private BigDecimal amount;

    /**
     * 预计关闭日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDateTime expectedCloseDate;

    /**
     * 销售机会来源
     */
    private String source;

    /**
     * 商机描述
     */
    private String description;

    /**
     * 负责人ID
     */
    private Long ownerId;

    /**
     * 负责人姓名
     */
    private String ownerName;

    /**
     * 审批人ID
     */
    private Long approverId;

    /**
     * 审批人姓名
     */
    private String approverName;

    /**
     * 创建人ID
     */
    private Long creatorId;

    /**
     * 创建人姓名
     */
    private String creatorName;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /**
     * 业务活动列表（按商机状态分组）
     * Key: 商机阶段名称
     * Value: 该阶段的业务活动列表
     */
    private Map<String, List<BusinessActivityVO>> activitiesByStage;

    /**
     * 商机状态变更记录列表
     */
    private List<SalesStageApprovalVO> stageChangeRecords;

    /**
     * 视同本人的关联用户ID（协助人/参与人），用于隐私脱敏放行
     */
    private Set<Long> relatedUserIds = Collections.emptySet();

    @Override
    public Set<Long> relatedUserIds() {
        return relatedUserIds;
    }

    /**
     * 获取商机阶段名称（仅用于后端内部逻辑，不返回给前端）
     * @param stage 阶段编号
     * @return 阶段名称
     */
    public static String getStageName(Integer stage) {
        if (stage == null) {
            return "未知阶段";
        }
        return switch (stage) {
            case 0 -> "种子商机";
            case 1 -> "潜在商机";
            case 2 -> "确认商机";
            case 3 -> "储备项目";
            case 4 -> "立项签约";
            case 5 -> "关闭";
            default -> "未知阶段";
        };
    }

    /**
     * 从商机实体创建VO
     * @param entity 商机实体
     * @param companyName 公司名称
     * @param contactName 联系人姓名
     * @param ownerName 负责人姓名
     * @param creatorName 创建人姓名
     * @param approverName 审批人姓名
     * @return OpportunityDetailVO
     */
    public static OpportunityDetailVO fromEntity(SalesOpportunityEntity entity,
                                                 String companyName,
                                                 String contactName,
                                                 String ownerName,
                                                 String creatorName,
                                                 String approverName) {
        if (entity == null) {
            return null;
        }

        OpportunityDetailVO vo = new OpportunityDetailVO();
        vo.setId(entity.getId());
        vo.setOpportunityName(entity.getOpportunityName());
        vo.setCompanyId(entity.getCompanyId());
        vo.setCompanyName(companyName);
        vo.setContactId(entity.getContactId());
        vo.setContactName(contactName);
        vo.setStage(entity.getStage());
        vo.setAmount(entity.getAmount());
        vo.setExpectedCloseDate(entity.getExpectedCloseDate());
        vo.setSource(entity.getSource());
        vo.setDescription(entity.getDescription());
        vo.setOwnerId(entity.getOwnerId());
        vo.setOwnerName(ownerName);
        vo.setApproverId(entity.getApproverId());
        vo.setApproverName(approverName);
        vo.setCreatorId(entity.getCreatorId());
        vo.setCreatorName(creatorName);
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());

        return vo;
    }
}
