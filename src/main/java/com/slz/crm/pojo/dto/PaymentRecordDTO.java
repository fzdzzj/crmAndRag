package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回款记录数据传输对象
 */
@Data
public class PaymentRecordDTO {
    /**
     * 回款ID
     */
    private Long id;

    /**
     * 关联合同ID
     */
    private Long contractId;

    /**
     * 关联订单明细ID（可选）
     */
    private Long orderItemId;

    /**
     * 回款单号
     */
    private String paymentNo;

    /**
     * 回款金额
     */
    private BigDecimal paymentAmount;

    /**
     * 回款日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime paymentDate;

    /**
     * 回款方式（如：银行转账、支票等）
     */
    private String paymentMethod;

    /**
     * 回款状态（0已确认/1待确认/2已作废）
     */
    private Integer paymentStatus;

    /**
     * 回款状态字符串（已确认/待确认/已作废）
     * 如果传入此字段，将自动转换为数字状态
     */
    private String paymentStatusStr;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建人ID
     */
    private Long creatorId;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 回款日期范围-开始
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime paymentDateStart;

    /**
     * 回款日期范围-结束
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime paymentDateEnd;
}
