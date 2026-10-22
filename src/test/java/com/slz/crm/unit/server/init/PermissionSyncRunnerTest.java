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

    // close-permission-module-gap：分组改由 PermissionModule 号段单一事实源决定（不再按枚举名前缀）——
    // AI/知识库各归各桶，「其他模块权限」桶保留但枚举内权限不得落入。
    List<PermissionsEntity> ai = byModule.getOrDefault("AI 模块权限", new ArrayList<>());
    Map<Long, String> aiNames =
        ai.stream()
            .collect(
                Collectors.toMap(PermissionsEntity::getId, PermissionsEntity::getPermissionsName));
    assertEquals("AI_ASSIST_VIEW", aiNames.get(800L));
    assertEquals("AI_TOKEN_USAGE_VIEW", aiNames.get(810L));

    List<PermissionsEntity> knowledge = byModule.getOrDefault("知识库管理模块权限", new ArrayList<>());
    assertEquals(1, knowledge.size());
    assertEquals("KNOWLEDGE_ADMIN_MANAGE", knowledge.get(0).getPermissionsName());

    assertTrue(
        byModule.getOrDefault("其他模块权限", new ArrayList<>()).isEmpty(),
        "PermissionOperates 内的权限一律有专属号段，不得落「其他模块权限」");

    // 全量裸名断言：名字不带任何模块前缀（历史上 sales: + 长名 52 字符超出 varchar(50)）
    Map<Long, String> idToEnumName =
        java.util.Arrays.stream(PermissionOperates.values())
            .collect(Collectors.toMap(PermissionOperates::getId, PermissionOperates::name));
    byModule.values().stream()
        .flatMap(List::stream)
        .forEach(perm -> assertEquals(idToEnumName.get(perm.getId()), perm.getPermissionsName()));
  }
}
