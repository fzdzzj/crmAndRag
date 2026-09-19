package com.slz.crm.platform.trace;

import java.lang.reflect.Field;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 给既有线程池统一补齐异步上下文装饰器。
 *
 * <p>B/C 业务线程池不要求 Lane D 修改实现；这里只在池未配置装饰器时补默认装饰器。 若某个线程池已有更完整的安全上下文装饰器，保持其实现不覆盖。
 */
@Configuration
public class AsyncContextDecoratorConfig {

  /** 通过 BeanPostProcessor 覆盖所有 ThreadPoolTaskExecutor，业务代码无需感知。 */
  // test-hygiene 任务 4.1：@Bean 工厂方法 static 化（Spring 6.2+ 建议），匿名类体与 MDC 装饰逻辑不变
  @Bean
  public static BeanPostProcessor asyncContextDecoratorPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ThreadPoolTaskExecutor executor && decoratorIsAbsent(executor)) {
          executor.setTaskDecorator(MdcTaskDecorator::wrap);
        }
        return bean;
      }
    };
  }

  private static boolean decoratorIsAbsent(ThreadPoolTaskExecutor executor) {
    try {
      Field decoratorField = ThreadPoolTaskExecutor.class.getDeclaredField("taskDecorator");
      decoratorField.setAccessible(true);
      return decoratorField.get(executor) == null;
    } catch (ReflectiveOperationException | SecurityException exception) {
      // Spring 版本变更时宁可保持业务装饰器原样，也不静默覆盖已有上下文语义。
      return false;
    }
  }
}
