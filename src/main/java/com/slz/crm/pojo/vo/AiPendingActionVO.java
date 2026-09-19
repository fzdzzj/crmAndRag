package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/** AI 待确认操作 VO（API 响应，不返回数据库实体） */
@Data
public class AiPendingActionVO {
  private String pendingId;
  private String status;
  private String actionType;
  private String payload;
  private List<String> missingFields;
  private String preview;
  private LocalDateTime expireTime;
}
