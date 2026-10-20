package com.slz.crm.platform.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Token 预算配置并发安全（fix-minor-debt-family 任务 6.1；harden-test-backlog-batch1 ③ 加固区别力）。
 *
 * <p>盘点结论：{@link TokenBudgetProperties} 是单例 bean，{@code enabled} / {@code dailyLimit} / {@code
 * monthlyLimit} 三个字段被全部请求线程经 {@code TokenBudgetService#check} 高频共读，而配置绑定（以及后续可能的动态刷新）
 * 在另一线程写同一批字段。改造前三个字段是普通可变字段——非 volatile 非原子，写后可见性无保证，且 JLS 17.7 允许非 volatile 的 {@code long}
 * 读写撕裂。故按「确认存在无锁共享可变状态 → 修」路径统一加 {@code volatile}，读侧只做单次 字段读取、无需锁。
 *
 * <p><b>区别力依据（本卡 ③）</b>：<br>
 * 1) 结构性断言 {@link #sharedMutableFieldsAreVolatile()} 逐字段读 {@code Modifier.isVolatile}——移除任一
 * volatile 该字段即判 false → 立刻红，这是本类「移除任一 volatile 即红」的确定性判据（反射读修饰符，与机器/时序/仲裁器无关）。<br>
 * 2) 自证 {@link #volatilityProbeRejectsNonVolatileField()} 用同包内一个非 volatile 字段证明探针确实会判 false，
 * 排除「探针恒真导致结构断言永远绿」的假绿。 <br>
 * 3) 可见性用例 {@link #writtenValuesVisibleToReaderThreadWithinBoundedSpin()} 是语义冒烟：读线程先确认读到灰度默认值、
 * 再等写线程写满三个字段，随后在有界自旋（{@code Thread.onSpinWait} + 纳秒截止时间，无 sleep）内必须读到新值。JMM 上 volatile 写建立
 * happens-before，读线程最迟下一轮读取即可见；反之为非 volatile 时 JIT 可合法把字段读提升出循环导致永不可见 → 截止时间到达判红。 该用例不承担唯一判据（可见性还受
 * CPU 缓存一致性影响，x86 下非 volatile 也可能偶然可见），故与 1) 结构断言组合成「确定判据 + 语义冒烟」。
 */
@DisplayName("Token 预算配置的并发安全契约")
class TokenBudgetPropertiesConcurrencyTest {

  /** 有界自旋上限：写线程在 latch 放行后立即写三字段，正常一轮即见；2s 只作病态情况兜底。 */
  private static final long SPIN_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(2);

  /** 写线程 join 上限：自旋截止后读线程必然自行退出，此处再留余量。 */
  private static final long READER_JOIN_MILLIS = 10_000L;

  @Test
  @DisplayName("缺陷点已修：三个共享可变字段全部声明 volatile（移除任一个即红）")
  void sharedMutableFieldsAreVolatile() throws NoSuchFieldException {
    for (String fieldName : List.of("enabled", "dailyLimit", "monthlyLimit")) {
      Field field = TokenBudgetProperties.class.getDeclaredField(fieldName);
      assertThat(isVolatile(field))
          .as("%s 必须 volatile：单例共享、多线程共读，非 volatile 写后可见性无保证", fieldName)
          .isTrue();
      assertThat(Modifier.isFinal(field.getModifiers()))
          .as("%s 不得是 final：配置绑定/动态刷新需要写该字段", fieldName)
          .isFalse();
    }
  }

  @Test
  @DisplayName("自证：volatile 探针对非 volatile 字段判 false（结构断言的负向样本）")
  void volatilityProbeRejectsNonVolatileField() throws NoSuchFieldException {
    assertThat(isVolatile(ProbeFixture.class.getDeclaredField("plainField")))
        .as("非 volatile 字段必须被探针判 false，否则移除 volatile 后结构断言假绿")
        .isFalse();
    assertThat(isVolatile(ProbeFixture.class.getDeclaredField("volatileField")))
        .as("volatile 字段必须被探针判 true，否则结构断言变成无关字段的恒假")
        .isTrue();
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
  @DisplayName("跨线程写入对读线程可见：latch 排序 + 有界自旋（无 sleep）读到三个新值")
  void writtenValuesVisibleToReaderThreadWithinBoundedSpin() throws InterruptedException {
    TokenBudgetProperties properties = new TokenBudgetProperties();
    CountDownLatch readerStarted = new CountDownLatch(1);
    CountDownLatch valuesSawDefaults = new CountDownLatch(1);
    AtomicBoolean readerSawNewValues = new AtomicBoolean(false);
    AtomicLong spinRounds = new AtomicLong();

    Thread reader =
        new Thread(
            () -> {
              readerStarted.countDown();
              // 先确认读到的是灰度默认值，保证读线程确实在看字段而不是别的分支
              if (properties.isEnabled()
                  || properties.getDailyLimit() != 100000L
                  || properties.getMonthlyLimit() != 1000000L) {
                return;
              }
              valuesSawDefaults.countDown();
              long deadline = System.nanoTime() + SPIN_DEADLINE_NANOS;
              while (System.nanoTime() < deadline) {
                spinRounds.incrementAndGet();
                if (properties.isEnabled()
                    && properties.getDailyLimit() == 7L
                    && properties.getMonthlyLimit() == 11L) {
                  readerSawNewValues.set(true);
                  return;
                }
                Thread.onSpinWait();
              }
            },
            "token-budget-reader");

    reader.start();
    assertThat(readerStarted.await(READER_JOIN_MILLIS, TimeUnit.MILLISECONDS))
        .as("读线程必须及时启动")
        .isTrue();
    assertThat(valuesSawDefaults.await(READER_JOIN_MILLIS, TimeUnit.MILLISECONDS))
        .as("读线程必须先观察到灰度默认值（否则说明读侧分支/默认值已变）")
        .isTrue();

    properties.setEnabled(true);
    properties.setDailyLimit(7L);
    properties.setMonthlyLimit(11L);

    reader.join(READER_JOIN_MILLIS);
    assertThat(reader.isAlive()).as("有界自旋必须在 %d ms 内退出", READER_JOIN_MILLIS).isFalse();
    assertThat(readerSawNewValues.get())
        .as("volatile 写后读线程必须在有界自旋内看到三个新值（自旋 %d 轮）", spinRounds.get())
        .isTrue();
    assertThat(spinRounds.get()).as("读线程确实执行过自旋（不是零轮直接退出）").isGreaterThan(0L);
  }

  /** 与 {@link TokenBudgetProperties} 同包的负向样本：一个非 volatile、一个 volatile，供探针自证。 */
  static final class ProbeFixture {
    @SuppressWarnings("unused")
    private long plainField;

    @SuppressWarnings("unused")
    private volatile long volatileField;
  }

  private static boolean isVolatile(Field field) {
    return Modifier.isVolatile(field.getModifiers());
  }
}
