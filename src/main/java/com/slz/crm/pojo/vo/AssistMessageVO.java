package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/** 协助过程消息展示对象。 */
@Data
public class AssistMessageVO {
    private Long id;
    private Long assistId;
    private Long senderId;
    private String senderName;
    private String content;
    private String messageType;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}
