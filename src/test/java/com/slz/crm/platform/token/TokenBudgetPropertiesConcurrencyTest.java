package com.slz.crm.platform.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Token 预算配置并发安全（fix-minor-debt-family 任务 6.1）。
 *
 * <p>盘点结论：{@link TokenBudgetProperties} 是单例 bean，{@code enabled} / {@code dailyLimit} / {@code
 * monthlyLimit} 三个字段被全部请求线程经 {@code TokenBudgetService#check} 高频共读，而配置绑定（以及后续可能的动态刷新）
 * 在另一线程写同一批字段。改造前三个字段是普通可变字段——非 volatile 非原子，写后可见性无保证，且 JLS 17.7 允许非 volatile 的 {@code long}
 * 读写撕裂。故按「确认存在无锁共享可变状态 → 修」路径统一加 {@code volatile}，读侧只做单次 字段读取、无需锁。
 *
 * <p>本测试把该并发契约锁死为结构断言：字段必须 volatile（回归时立刻红），默认值仍是灰度关闭口径。
 */
@DisplayName("Token 预算配置的并发安全契约")
class TokenBudgetPropertiesConcurrencyTest {

  @Test
  @DisplayName("缺陷点已修：三个共享可变字段全部声明 volatile")
  void sharedMutableFieldsAreVolatile() throws NoSuchFieldException {
    for (String fieldName : List.of("enabled", "dailyLimit", "monthlyLimit")) {
      Field field = TokenBudgetProperties.class.getDeclaredField(fieldName);
      assertThat(Modifier.isVolatile(field.getModifiers()))
          .as("%s 必须 volatile：单例共享、多线程共读，非 volatile 写后可见性无保证", fieldName)
          .isTrue();
    }
  }

  @Test
  @DisplayName("灰度默认值不变：enabled=false，日 100000 / 月 1000000")
  void defaultsKeepGrayReleaseSemantics() {
    TokenBudgetProperties properties = new TokenBudgetProperties();
    assertThat(properties.isEnabled()).isFalse();
    assertThat(properties.getDailyLimit()).isEqualTo(100000L);
    assertThat(properties.getMonthlyLimit()).isEqualTo(1000000L);
  }

  @Test
  @DisplayName("跨线程写入对读线程可见（volatile 语义冒烟）")
  void writtenValueVisibleToOtherThread() throws InterruptedException {
    TokenBudgetProperties properties = new TokenBudgetProperties();
    assertThat(properties.isEnabled()).isFalse();

    Thread writer = new Thread(() -> properties.setEnabled(true), "token-budget-writer");
    writer.start();
    writer.join();

    assertThat(properties.isEnabled()).isTrue();
  }
}
