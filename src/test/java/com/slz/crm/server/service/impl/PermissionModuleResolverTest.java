package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.slz.crm.common.enumeration.PermissionModule;
import com.slz.crm.common.enumeration.PermissionOperates;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 权限模块归属单一事实源的防漂移门禁（close-permission-module-gap 任务 2.1）。
 *
 * <p>号段表见 {@code docs/permission-module-catalog.md}。核心不变式：{@link PermissionOperates} 的每一个常量都必须落到
 * 非「其他模块权限」的模块——以后新增权限忘了归段，本测试第一个红。
 */
@DisplayName("权限模块号段：10 桶顺序固定 + 全枚举防漂移")
class PermissionModuleResolverTest {

  /** 10 个模块展示名，顺序 = PermissionModule 声明顺序，「其他模块权限」恒末位。 */
  private static final List<String> EXPECTED_MODULES =
      List.of(
          "客户管理模块权限",
          "销售管理模块权限",
          "财务管理模块权限",
          "联络任务模块权限",
          "统计报表模块权限",
          "权限管理模块权限",
          "隐私信息查看权限",
          "AI 模块权限",
          "知识库管理模块权限",
          "其他模块权限");

  @Test
  @DisplayName("moduleNames() = 10 项，顺序固定且「其他模块权限」在末位")
  void moduleNamesAreOrdered() {
    assertEquals(EXPECTED_MODULES, PermissionModuleResolver.moduleNames());
  }

  @Test
  @DisplayName("防漂移：PermissionOperates 每个常量都落非「其他模块权限」的模块")
  void everyPermissionOperatesHasDedicatedModule() {
    for (PermissionOperates operates : PermissionOperates.values()) {
      PermissionModule module = PermissionModule.fromOperates(operates);
      assertNotEquals(
          PermissionModule.OTHER, module, operates.name() + "（id=" + operates.getId() + "）未归段");
      assertEquals(
          module,
          PermissionModule.fromPermissionId(operates.getId()),
          operates.name() + " 按 ID 与按枚举解析结果必须一致");
      assertEquals(
          module.displayName(),
          PermissionModuleResolver.resolve(operates.getId()),
          operates.name() + " 的展示名必须与枚举一致");
    }
  }

  @ParameterizedTest(name = "id {0} → {1}")
  @CsvSource({
    "101, 客户管理模块权限",
    "1171, 客户管理模块权限",
    "2041, 销售管理模块权限",
    "2295, 销售管理模块权限",
    "3045, 财务管理模块权限",
    "4044, 联络任务模块权限",
    "501, 统计报表模块权限",
    "608, 权限管理模块权限",
    "701, 隐私信息查看权限",
    "800, AI 模块权限",
    "810, AI 模块权限",
    "900, 知识库管理模块权限"
  })
  @DisplayName("抽查各号段代表 ID 的模块归属")
  void representativeIdsMapToExpectedModule(long id, String expectedModule) {
    assertEquals(expectedModule, PermissionModuleResolver.resolve(id));
  }

  @Test
  @DisplayName("id 为 null 或不在枚举中（含伪 9xx）→ 其他模块权限")
  void nullAndUnknownIdsFallBackToOtherModule() {
    assertEquals("其他模块权限", PermissionModuleResolver.resolve(null));
    assertEquals("其他模块权限", PermissionModuleResolver.resolve(9999L));
    assertEquals("其他模块权限", PermissionModuleResolver.resolve(910L));
    assertEquals(PermissionModule.OTHER, PermissionModule.fromPermissionId(null));
  }
}
