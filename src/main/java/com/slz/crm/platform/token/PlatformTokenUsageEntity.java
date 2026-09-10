package com.slz.crm.platform.token;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.slz.crm.platform.contract.TokenUsageType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型调用 token 计量明细。
 *
 * <p>B/C 产数，Lane D 持久化与聚合。失败调用也要落库，便于核算重试成本。</p>
 */
@Data
@TableName("platform_token_usage")
public class PlatformTokenUsageEntity {

    /** 计量明细主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 实际模型名，可能是降级后的备用模型。 */
    private String model;

    /** 用户跨域引用 {@code user:<id>}；系统旁路任务为 {@code user:system}。 */
    private String userIdRef;

    /** 会话 ID；非对话类调用可传业务批次 ID。 */
    private String sessionId;

    /** 知识库主键；非知识库调用为空。 */
    private Long knowledgeBaseId;

    /** 计量类型。 */
    @TableField("usage_type")
    private TokenUsageType usageType;

    /** 输入 token 数。 */
    private Long promptTokens;

    /** 输出 token 数。 */
    private Long completionTokens;

    /** 总 token 数。 */
    private Long totalTokens;

    /** 调用是否成功；失败也要计量。 */
    private boolean success;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
