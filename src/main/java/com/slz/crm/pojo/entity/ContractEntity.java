package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合同实体类
 */
@Data
@AutoTable
@Table(value = "contract", comment = "合同表")
@TableName("contract")
@TableIndex(name = "uk_contract_no", fields = {"contractNo"}, type = IndexTypeEnum.UNIQUE)
@TableIndex(name = "idx_contract_company", fields = {"companyId"})
@TableIndex(name = "idx_contract_status", fields = {"contractStatus"})
@TableIndex(name = "fk_contract_owner", fields = {"ownerId"})
@TableIndex(name = "fk_contract_creator", fields = {"creatorId"})
public class ContractEntity {
    @TableId(type = IdType.AUTO)
    @Column(comment = "合同ID")
    private Long id;
    /**
     * 合同编号
     */
    @Column(comment = "合同编号（系统自动生成或手动录入，唯一）", type = "varchar(50)", notNull = true)
    private String contractNo;
    /**
     * 商机id
     */
    @Column(comment = "关联销售机会ID(来源商机)", type = "bigint")
    private Long opportunityId;
    /**
     * 公司客户id
     */
    @Column(comment = "客户公司ID", type = "bigint", notNull = true)
    private Long companyId;
    /**
     * 合同名称
     */
    @Column(comment = "合同名称", type = "varchar(200)", notNull = true)
    private String contractName;
    /**
     * 合同金额
     */
    @Column(comment = "合同总金额", type = "decimal(15,2)", notNull = true)
    private BigDecimal totalAmount;
    /**
     * 签约日期
     */
    @Column(comment = "签约日期", type = "datetime", notNull = true)
    private LocalDateTime signDate;
    /**
     * 合同生效日期
     */
    @Column(comment = "合同开始日期", type = "datetime")
    private LocalDateTime startDate;
    /**
     * 合同失效日期
     */
    @Column(comment = "合同结束日期", type = "datetime")
    private LocalDateTime endDate;
    /**
     * 合同状态(0预签约/1已生效/2已终止/3已完成/4已弃用)
     */
    @Column(comment = "合同状态（0预签约/1已生效/2已终止/3已完成/4已弃用）", type = "int", notNull = true)
    private Integer contractStatus;
    /**
     * 负责人id
     */
    @Column(comment = "负责人ID", type = "bigint", notNull = true)
    private Long ownerId;
    /**
     * 创建人id
     */
    @Column(comment = "创建人ID", type = "bigint", notNull = true)
    private Long creatorId;
    /**
     * 创建时间
     */
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    @Column(comment = "最后更新时间", type = "datetime")
    private LocalDateTime updateTime;

}
