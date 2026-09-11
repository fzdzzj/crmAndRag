package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import com.tangzc.autotable.annotation.TableIndex;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 助手会话聊天图片。
 *
 * <p>该表只属于 C 的聊天图域；imageRef 可使用本表 ID 或 image_hash。
 * OCR/摘要/实体是问题无关的 L1 结果，按会话内 hash 去重持久化。</p>
 */
@Data
@AutoTable
@Table(value = "ai_chat_image", comment = "AI会话聊天图片")
@TableName("ai_chat_image")
@TableIndex(name = "uk_ai_chat_image_session_hash", fields = {"sessionId", "imageHash"},
        type = IndexTypeEnum.UNIQUE)
public class AiChatImageEntity {
    @TableId(type = IdType.AUTO)
    @Column(comment = "图片ID")
    private Long id;

    @Column(comment = "所属AI会话", notNull = true)
    private Long sessionId;

    @Column(comment = "CRM域归属用户", notNull = true)
    private Long userId;

    @Column(comment = "图片SHA-256", notNull = true, length = 64)
    private String imageHash;

    @Column(comment = "存储后端", notNull = true, length = 16)
    private String storageBackend;

    @Column(comment = "存储键", notNull = true, length = 255)
    private String storageKey;

    @Column(comment = "OCR文本", type = "text")
    private String ocrText;

    @Column(comment = "图片摘要（<=300字符）", length = 300)
    private String imageSummary;

    @Column(comment = "关键实体JSON数组", type = "json")
    private String keyEntities;

    @Column(comment = "创建时间", notNull = true)
    private LocalDateTime createTime;

    @Column(comment = "更新时间", notNull = true)
    private LocalDateTime updateTime;

    @TableLogic
    @Column(comment = "是否删除", notNull = true, defaultValue = "0")
    private Boolean isDeleted;
}
