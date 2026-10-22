package com.slz.crm.unit.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.common.enumeration.PermissionModule;
import com.slz.crm.common.enumeration.PermissionOperates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 权限字面量类型生成物校验（reserve-sweep-1 S2）。
 *
 * <p>生成物 {@code frontend/src/types/permission.gen.ts} 的两条联合必须与后端枚举逐字一致：{@link PermissionOperates}
 * 的枚举名（顺序 = 声明顺序）→ {@code PermissionName}；{@link PermissionModule#displayName()}（顺序 = 声明顺序）→ {@code
 * PermissionModuleName}。
 *
 * <p>默认（surefire）<b>校验</b>：不一致即失败并打印首个差异行，防止「忘了重跑生成器」的类型漂移； {@code -Dpermission.types.update=true}
 * 时<b>重写</b>该文件（与定标锚「只允许由测试重写」同惯用法）。
 *
 * <p>只生成 {@code type}，零运行时代码：前端包体预算（{@code frontend/bundle-budget.json}）不受影响。
 */
@DisplayName("权限字面量类型生成物校验（permission.gen.ts）")
class PermissionTypeCatalogGenTest {

  /** 重写开关：只有显式 -Dpermission.types.update=true 才写文件。 */
  private static final String UPDATE_PROP = "permission.types.update";

  /** 生成物路径（相对项目根；surefire 的工作目录 = 模块根）。 */
  private static final Path GENERATED = Path.of("frontend", "src", "types", "permission.gen.ts");

  @Test
  @DisplayName("生成物与 PermissionOperates / PermissionModule 逐字一致（或按开关重写）")
  void generatedFileMatchesEnums() throws IOException {
    String expected = render();
    boolean update = "true".equalsIgnoreCase(System.getProperty(UPDATE_PROP, "false").strip());
    if (update) {
      Files.createDirectories(GENERATED.getParent());
      Files.writeString(GENERATED, expected, StandardCharsets.UTF_8);
      System.out.println("[permission-types] 已重写 " + GENERATED.toAbsolutePath());
      return;
    }
    assertTrue(
        Files.isRegularFile(GENERATED),
        "生成物缺失：" + GENERATED + "（用 -Dpermission.types.update=true 生成）");
    String actual = Files.readString(GENERATED, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertEquals(expected, actual, () -> firstDifference(expected, actual));
  }

  @Test
  @DisplayName("生成物只含 type，零运行时代码")
  void generatedFileHasNoRuntimeCode() throws IOException {
    String actual = Files.readString(GENERATED, StandardCharsets.UTF_8).replace("\r\n", "\n");
    List<String> offenders = new ArrayList<>();
    for (String line : actual.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()
          || trimmed.startsWith("//")
          || trimmed.startsWith("*")
          || trimmed.startsWith("/*")) {
        continue;
      }
      if (!trimmed.startsWith("export type ") && !trimmed.startsWith("| '")) {
        offenders.add(trimmed);
      }
    }
    assertTrue(offenders.isEmpty(), "生成物含非 type 内容（违反零运行时代码）：" + offenders);
  }

  /** 按枚举渲染生成物全文（末尾带换行）。 */
  private static String render() {
    List<String> names = new ArrayList<>();
    for (PermissionOperates operates : PermissionOperates.values()) {
      names.add(operates.name());
    }
    List<String> modules = new ArrayList<>();
    for (PermissionModule module : PermissionModule.values()) {
      modules.add(module.displayName());
    }

    StringBuilder out = new StringBuilder();
    out.append("// 本文件由 PermissionTypeCatalogGenTest 从后端枚举生成，请勿手改。\n");
    out.append("// 来源：src/main/java/com/slz/crm/common/enumeration/PermissionOperates.java（权限名）\n");
    out.append(
        "//       src/main/java/com/slz/crm/common/enumeration/PermissionModule.java（模块展示名）\n");
    out.append("// 默认（mvn verify 内的 surefire）逐字校验：不一致即失败并打印首个差异。\n");
    out.append("// 重写只由测试触发：\n");
    out.append(
        "//   mvn -B -ntp test -Dtest=PermissionTypeCatalogGenTest -Dpermission.types.update=true\n");
    out.append("// 只生成 type，零运行时代码（不增加前端包体预算）。\n");
    out.append('\n');
    appendUnion(out, "PermissionName", "权限操作枚举名联合（PermissionOperates", names);
    out.append('\n');
    appendUnion(out, "PermissionModuleName", "权限模块展示名联合（PermissionModule#displayName", modules);
    return out.toString();
  }

  /** 追加一条「export type X = 'a' | 'b'」联合，顺序即入参顺序。 */
  private static void appendUnion(
      StringBuilder out, String typeName, String docPrefix, List<String> values) {
    out.append("/** ")
        .append(docPrefix)
        .append("，共 ")
        .append(values.size())
        .append(" 项；顺序 = 枚举声明顺序）。 */\n");
    out.append("export type ").append(typeName).append(" =\n");
    for (int i = 0; i < values.size(); i++) {
      out.append("  | '").append(values.get(i)).append("'");
      if (i == values.size() - 1) {
        out.append(';');
      }
      out.append('\n');
    }
  }

  /** 首个差异行的可读描述（含行号与实际/期望内容）。 */
  private static String firstDifference(String expected, String actual) {
    String[] wantLines = expected.split("\n", -1);
    String[] gotLines = actual.split("\n", -1);
    int max = Math.max(wantLines.length, gotLines.length);
    for (int i = 0; i < max; i++) {
      String want = i < wantLines.length ? wantLines[i] : "<文件已结束>";
      String got = i < gotLines.length ? gotLines[i] : "<文件已结束>";
      if (!want.equals(got)) {
        return "生成物与枚举不一致，首个差异在第 "
            + (i + 1)
            + " 行：\n  期望: "
            + want
            + "\n  实际: "
            + got
            + "\n（重写：-Dpermission.types.update=true）";
      }
    }
    return "生成物与枚举不一致（行内容全同但字节不同，检查行尾/编码）";
  }
}
