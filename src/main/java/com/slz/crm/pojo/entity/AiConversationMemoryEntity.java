package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话持久记忆加工品。
 *
 * <p>对话原文只保存在 {@code ai_message}；本表保存摘要、事实和意图，供短问改写与 prompt 注入。</p>
 */
@Data
@AutoTable
@Table(value = "ai_conversation_memory", comment = "AI会话持久记忆")
@TableName("ai_conversation_memory")
@TableIndex(name = "uk_ai_memory_session", fields = {"sessionId"}, type = IndexTypeEnum.UNIQUE)
public class AiConversationMemoryEntity {
    @TableId(type = IdType.AUTO)
    @Column(comment = "记忆ID")
    private Long id;

    @Column(comment = "所属AI会话", notNull = true)
    private Long sessionId;

    @Column(comment = "CRM域归属用户", notNull = true)
    private Long userId;

    @Column(comment = "历史摘要", type = "text")
    private String summary;

    @Column(comment = "已确认事实JSON数组", type = "text")
    private String facts;

    @Column(comment = "当前单值意图")
    private String intent;

    /**
     * 乐观锁版本号；旁路任务并发更新冲突时调用方应拒绝并降级，不能静默覆盖。
     */
    @Version
    @Column(comment = "乐观锁版本号", notNull = true, defaultValue = "0")
    private Integer version;

    @Column(comment = "创建时间", type = "datetime", notNull = true)
    private LocalDateTime createdTime;

    @Column(comment = "更新时间", type = "datetime", notNull = true)
    private LocalDateTime updatedTime;
}
