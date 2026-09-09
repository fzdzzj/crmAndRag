package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.entity.UserHandoverEntity;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;

/**
 * 用户交接记录VO
 */
@Data
public class UserHandoverVO {

    /**
     * 交接记录ID
     */
    private Long id;

    /**
     * 原用户ID（离职用户）
     */
    private Long fromUserId;

    /**
     * 原用户姓名
     */
    private String fromUserName;

    /**
     * 交接用户ID（接收人）
     */
    private Long toUserId;

    /**
     * 接收用户姓名
     */
    private String toUserName;

    /**
     * 交接任务数量
     */
    private Integer taskCount;

    /**
     * 交接客户数量
     */
    private Integer customerCount;

    /**
     * 交接销售机会数量
     */
    private Integer opportunityCount;

    /**
     * 交接时间
     */
    private LocalDateTime handoverTime;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作人姓名
     */
    private String operatorName;

    /**
     * 交接备注
     */
    private String remark;

    /**
     * 从实体类转换为VO
     *
     * @param entity       交接记录实体
     * @param fromUserName 离职用户姓名
     * @param toUserName   接收用户姓名
     * @param operatorName 操作人姓名
     * @return UserHandoverVO
     */
    public static UserHandoverVO fromEntity(UserHandoverEntity entity,
                                            String fromUserName,
                                            String toUserName,
                                            String operatorName) {
        UserHandoverVO vo = new UserHandoverVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setFromUserName(fromUserName);
        vo.setToUserName(toUserName);
        vo.setOperatorName(operatorName);
        return vo;
    }
}
