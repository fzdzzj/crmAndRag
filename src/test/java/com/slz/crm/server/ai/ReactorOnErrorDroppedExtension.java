package com.slz.crm.server.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import reactor.core.publisher.Hooks;

/**
 * Reactor {@code onErrorDropped} 错误捕获与测试断言扩展（永久锁，卡 I）。
 *
 * <p>背景与原理：在响应式流式链（如 {@code .doOnError(...).subscribe()}）中，若错误处理逻辑内部自身抛出异常， 且终态订阅者未指定 error
 * consumer，Reactor 会将未处理的异常交由 {@link Hooks#onErrorDropped(java.util.function.Consumer)}
 * 静默丢弃仅打日志。这会导致测试静默全绿而业务链路早已断裂（丢弃终态帧、SSE挂起、注册表残留僵尸流）。
 *
 * <p>本扩展在 {@code @BeforeEach} 安装 hook 捕获所有被丢弃的异常，在 {@code @AfterEach} 断言列表为空并逐条打印详情， 最后在 {@code
 * finally} 块中必定执行 {@link Hooks#resetOnErrorDropped()} 复位全局钩子，防止污染后续测试。
 */
public class ReactorOnErrorDroppedExtension implements BeforeEachCallback, AfterEachCallback {

  private static final ThreadLocal<List<Throwable>> CURRENT_ERRORS = new ThreadLocal<>();
  private static final ThreadLocal<List<Class<? extends Throwable>>> EXPECTED_TYPES =
      new ThreadLocal<>();
  private final List<Throwable> droppedErrors = new CopyOnWriteArrayList<>();
  private final List<Class<? extends Throwable>> expectedTypes = new CopyOnWriteArrayList<>();

  @Override
  public void beforeEach(ExtensionContext context) {
    droppedErrors.clear();
    expectedTypes.clear();
    CURRENT_ERRORS.set(droppedErrors);
    EXPECTED_TYPES.set(expectedTypes);
    Hooks.onErrorDropped(droppedErrors::add);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    try {
      List<Throwable> unhandledErrors = new ArrayList<>();
      for (Throwable t : droppedErrors) {
        Throwable rootOrCause = unwrap(t);
        boolean expected = false;
        for (Class<? extends Throwable> expectedType : expectedTypes) {
          if (expectedType.isInstance(t) || expectedType.isInstance(rootOrCause)) {
            expected = true;
            break;
          }
        }
        if (!expected) {
          unhandledErrors.add(t);
        }
      }

      if (!unhandledErrors.isEmpty()) {
        StringBuilder sb = new StringBuilder();
        sb.append("流内错误处理器抛出的异常会被 reactor 丢弃，本锁让它变红。检测到 ")
            .append(unhandledErrors.size())
            .append(" 个被丢弃的异常:\n");
        for (int i = 0; i < unhandledErrors.size(); i++) {
          Throwable t = unhandledErrors.get(i);
          sb.append("[")
              .append(i + 1)
              .append("] ")
              .append(t.getClass().getName())
              .append(": ")
              .append(t.getMessage())
              .append("\n");
        }
        Assertions.fail(sb.toString(), unhandledErrors.get(0));
      }
    } finally {
      CURRENT_ERRORS.remove();
      EXPECTED_TYPES.remove();
      Hooks.resetOnErrorDropped();
    }
  }

  private static Throwable unwrap(Throwable t) {
    Throwable current = t;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  /**
   * 登记当前用例中由测试主动构造且已知会被裸 subscribe 丢弃的模型上游异常类型。 仅用于既有用例中显式构造 Flux.error 的场景（档 3 未改动裸 subscribe
   * 时的过渡支持）， 其它未登记异常（尤其是处理器内部抛出的异常）仍必定让测试变红。
   */
  public static void expectDropped(Class<? extends Throwable> type) {
    List<Class<? extends Throwable>> list = EXPECTED_TYPES.get();
    if (list != null) {
      list.add(type);
    }
  }

  public List<Throwable> getDroppedErrors() {
    return Collections.unmodifiableList(droppedErrors);
  }

  public static List<Throwable> getCapturedErrors() {
    List<Throwable> list = CURRENT_ERRORS.get();
    return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
  }
}
