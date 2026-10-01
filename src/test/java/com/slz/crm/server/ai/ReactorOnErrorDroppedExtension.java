package com.slz.crm.server.ai;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import reactor.core.publisher.Hooks;

/**
 * Reactor {@code onErrorDropped} 错误捕获与 fail-fast 断言扩展（永久锁，卡 I 建、卡 P-e 收紧为零豁免）。
 *
 * <p>背景与原理：响应式链中任何未被流内操作符吸收的错误（如裸 {@code .subscribe()} 未指定 error consumer，或错误处理逻辑自身抛出异常），Reactor
 * 会将其交由 {@link Hooks#onErrorDropped(java.util.function.Consumer)}
 * 静默丢弃仅打日志。这会导致测试静默全绿而业务链路早已断裂（丢弃终态帧、SSE 挂起、注册表残留僵尸流）。
 *
 * <p>本扩展在 {@code @BeforeEach} 安装 hook 捕获所有被丢弃的异常，在 {@code @AfterEach} 无条件断言列表为空并逐条打印详情——自卡 P-e
 * 起流末端已由 {@code onErrorResume} 在流内吸收错误，因此不存在任何需要豁免的合法丢弃：只要捕获到任何一个被丢弃的异常即判败，零白名单、零死角。 最后在 {@code
 * finally} 块中必定执行 {@link Hooks#resetOnErrorDropped()} 复位全局钩子，防止污染后续测试。
 */
public class ReactorOnErrorDroppedExtension implements BeforeEachCallback, AfterEachCallback {

  private static final ThreadLocal<List<Throwable>> CURRENT_ERRORS = new ThreadLocal<>();
  private final List<Throwable> droppedErrors = new CopyOnWriteArrayList<>();

  @Override
  public void beforeEach(ExtensionContext context) {
    droppedErrors.clear();
    CURRENT_ERRORS.set(droppedErrors);
    Hooks.onErrorDropped(droppedErrors::add);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    try {
      if (!droppedErrors.isEmpty()) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reactor 丢弃了未处理的异常（流末端必须在响应式链内吸收错误，零豁免，卡 P-e）。检测到 ")
            .append(droppedErrors.size())
            .append(" 个被丢弃的异常:\n");
        for (int i = 0; i < droppedErrors.size(); i++) {
          Throwable t = droppedErrors.get(i);
          sb.append("[")
              .append(i + 1)
              .append("] ")
              .append(t.getClass().getName())
              .append(": ")
              .append(t.getMessage())
              .append("\n");
        }
        Assertions.fail(sb.toString(), droppedErrors.get(0));
      }
    } finally {
      CURRENT_ERRORS.remove();
      Hooks.resetOnErrorDropped();
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
