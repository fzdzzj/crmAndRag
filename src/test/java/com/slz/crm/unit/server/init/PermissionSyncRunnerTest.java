package com.slz.crm.unit.server.init;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.server.init.PermissionSyncRunner;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 权限同步器分组构建回归测试：历史上 AI_/KNOWLEDGE_ 前缀不在模块映射内， buildAllRequiredPermissions 因 Map.get 返回 null 整体
 * NPE，导致 prod 每次启动同步失败。
 */
class PermissionSyncRunnerTest {

  @Test
  @SuppressWarnings("unchecked")
  void buildAllRequiredPermissionsShouldCoverEveryEnumWithoutNpe() throws Exception {
    Method builder = PermissionSyncRunner.class.getDeclaredMethod("buildAllRequiredPermissions");
    builder.setAccessible(true);
    Map<String, List<PermissionsEntity>> byModule =
        (Map<String, List<PermissionsEntity>>) builder.invoke(new PermissionSyncRunner());

    // 每个枚举 id 恰好出现一次
    List<Long> ids =
        byModule.values().stream()
            .flatMap(List::stream)
            .map(PermissionsEntity::getId)
            .collect(Collectors.toList());
    assertEquals(PermissionOperates.values().length, ids.size());
    assertEquals(PermissionOperates.values().length, ids.stream().distinct().count());

    // 未知前缀归入「其他模块」；所有权限名均为枚举裸名，与迁移种子（V21/V26/V27）一致
    List<PermissionsEntity> others = byModule.getOrDefault("其他模块", new ArrayList<>());
    assertTrue(others.size() >= 9, "AI_ 8 个 + KNOWLEDGE_ 应归入其他模块");
    Map<Long, String> otherNames =
        others.stream()
            .collect(
                Collectors.toMap(PermissionsEntity::getId, PermissionsEntity::getPermissionsName));
    assertEquals("AI_ASSIST_VIEW", otherNames.get(800L));
    assertTrue(otherNames.containsValue("KNOWLEDGE_ADMIN_MANAGE"));

    // 全量裸名断言：名字不带任何模块前缀（历史上 sales: + 长名 52 字符超出 varchar(50)）
    Map<Long, String> idToEnumName =
        java.util.Arrays.stream(PermissionOperates.values())
            .collect(Collectors.toMap(PermissionOperates::getId, PermissionOperates::name));
    byModule.values().stream()
        .flatMap(List::stream)
        .forEach(perm -> assertEquals(idToEnumName.get(perm.getId()), perm.getPermissionsName()));
  }
}
