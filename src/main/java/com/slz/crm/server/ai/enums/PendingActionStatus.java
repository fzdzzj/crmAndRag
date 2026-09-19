package com.slz.crm.server.ai.enums;

import lombok.Getter;

/** AI 待确认操作状态枚举 */
@Getter
public enum PendingActionStatus {
  DRAFTING("DRAFTING"),
  PENDING("PENDING"),
  CONFIRMED("CONFIRMED"),
  CANCELLED("CANCELLED"),
  EXPIRED("EXPIRED"),
  FAILED("FAILED");

  private final String value;

  PendingActionStatus(String value) {
    this.value = value;
  }

  /** 是否终态（CONFIRMED/CANCELLED/EXPIRED；FAILED 可重试，不是终态） */
  public boolean isTerminal() {
    return this == CONFIRMED || this == CANCELLED || this == EXPIRED;
  }

  /** 是否可取消（DRAFTING/PENDING） */
  public boolean isCancellable() {
    return this == DRAFTING || this == PENDING;
  }

  /** 按字符串值查找枚举（不存在时返回 null） */
  public static PendingActionStatus fromValue(String value) {
    if (value == null) return null;
    for (PendingActionStatus s : values()) {
      if (s.value.equals(value)) return s;
    }
    return null;
  }
}
