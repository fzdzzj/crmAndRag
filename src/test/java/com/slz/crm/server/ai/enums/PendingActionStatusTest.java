package com.slz.crm.server.ai.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PendingActionStatusTest {

  @Test
  void failedIsRetryableAndNotTerminal() {
    assertThat(PendingActionStatus.FAILED.isTerminal()).isFalse();
    assertThat(PendingActionStatus.PENDING.isTerminal()).isFalse();
    assertThat(PendingActionStatus.DRAFTING.isTerminal()).isFalse();
    assertThat(PendingActionStatus.CONFIRMED.isTerminal()).isTrue();
    assertThat(PendingActionStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(PendingActionStatus.EXPIRED.isTerminal()).isTrue();
  }
}
