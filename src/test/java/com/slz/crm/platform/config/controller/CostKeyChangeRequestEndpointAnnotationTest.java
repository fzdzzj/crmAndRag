package com.slz.crm.platform.config.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** 成本键申请-审批控制器 5 端点权限注解扫描测试（add-cost-key-approval-workflow 任务 3.1）。 */
class CostKeyChangeRequestEndpointAnnotationTest {

  @Test
  void allFiveEndpointsMustHavePlatformDynamicConfigManagePermission() {
    List<Method> endpointMethods =
        Arrays.stream(CostKeyChangeRequestController.class.getDeclaredMethods())
            .filter(
                m ->
                    m.isAnnotationPresent(PostMapping.class)
                        || m.isAnnotationPresent(GetMapping.class))
            .toList();

    assertEquals(5, endpointMethods.size(), "必须恰好包含 5 个端点方法");

    for (Method method : endpointMethods) {
      RequirePermission annotation = method.getAnnotation(RequirePermission.class);
      assertNotNull(annotation, "端点方法 " + method.getName() + " 缺失 @RequirePermission 注解");
      assertEquals(
          PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE,
          annotation.value(),
          "端点方法 " + method.getName() + " 必须挂 PLATFORM_DYNAMIC_CONFIG_MANAGE(608)");
    }
  }
}
