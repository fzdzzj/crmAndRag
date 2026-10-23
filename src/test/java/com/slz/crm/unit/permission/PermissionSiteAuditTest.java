package com.slz.crm.unit.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3 站点清零门禁（evidence-to-operations G2 任务 2.4.1）：扫描 {@code frontend/src} 下全部 {@code *.vue}，
 * `<Permission>` 上的字面量 {@code :allow=} 绑定必须清零——按钮权限一律走 {@code :endpoint}（从契约派生）。
 *
 * <p>例外 = 受控逃生口：{@code sale/list.page.vue} 的 {@code SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY}
 * 不在契约任何端点上（生成物反查 0 候选），保留手抄 {@code allow} 并在下表白名单登记理由；白名单文件数 ≤ 3、 白名单内站点总数 ≤ 3（proposal §2.3）。本测试纯
 * JVM 文件扫描，不依赖 Node。
 */
@DisplayName("P3：前端 <Permission> 字面量 :allow= 站点清零（白名单 ≤3 处）")
class PermissionSiteAuditTest {

  /** 扫描根（相对模块根）。 */
  private static final Path FRONTEND_SRC = Path.of("frontend", "src");

  /** 例外白名单：文件路径（POSIX 形式）→ 保留 allow 的理由（禁凑数）。 */
  private static final Map<String, String> ALLOW_WHITELIST = buildWhitelist();

  private static Map<String, String> buildWhitelist() {
    Map<String, String> whitelist = new LinkedHashMap<>();
    whitelist.put(
        "frontend/src/pages/(dashboard)/sale/list.page.vue",
        "SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY 不在契约任何端点上（endpointPermission.gen.ts 反查 0 候选）；"
            + "该按钮只做前端路由跳转到 /sale/detail/{id}（详情页数据 GET /sales/detail/{opportunityId} 携带 "
            + "SALES_VIEW_SALE_OPPORTUNITY），按端点派生会把门控从 ONLY_MY 放大到 VIEW，属行为变更，超出本卡"
            + "「不新增生产行为」边界——保留手抄 allow，待 ONLY_MY 语义进契约后另卡迁移");
    return whitelist;
  }

  /** 例外总数上限（proposal §2.3：动态 allow 站点 ≤3 处）。 */
  private static final int MAX_WHITELIST_SITES = 3;

  @Test
  @DisplayName("白名单外 .vue 不允许出现 :allow=（含动态绑定）；白名单内总数 ≤3 且逐条有理由")
  void literalAllowBindingsAreExtinctOutsideWhitelist() throws IOException {
    assertTrue(Files.isDirectory(FRONTEND_SRC), "frontend/src 不存在，工作目录错了？");
    List<String> violations = new ArrayList<>();
    Map<String, Integer> whitelistHits = new LinkedHashMap<>();
    int filesScanned = 0;
    try (Stream<Path> stream = Files.walk(FRONTEND_SRC)) {
      for (Path file : stream.filter(paths -> paths.toString().endsWith(".vue")).toList()) {
        filesScanned++;
        String content = Files.readString(file).replace("\r\n", "\n");
        int hits = countAllowBindings(content);
        if (hits == 0) {
          continue;
        }
        String posix = file.toString().replace('\\', '/');
        String reason = ALLOW_WHITELIST.get(posix);
        if (reason == null) {
          violations.add(posix + " 出现 " + hits + " 处 :allow=（必须改 :endpoint 或登记白名单）");
        } else {
          whitelistHits.merge(posix, hits, Integer::sum);
        }
      }
    }
    assertTrue(filesScanned > 0, "没扫到任何 .vue 文件");
    assertEquals(List.of(), violations, "白名单外仍存在 :allow= 绑定");
    for (Map.Entry<String, String> entry : ALLOW_WHITELIST.entrySet()) {
      assertTrue(
          whitelistHits.containsKey(entry.getKey()),
          "白名单登记了但文件里已没有 :allow=（例外过期，应删除登记）: " + entry.getKey());
    }
    int total = whitelistHits.values().stream().mapToInt(Integer::intValue).sum();
    assertTrue(
        total <= MAX_WHITELIST_SITES, "白名单内 :allow= 站点数 " + total + " 超过上限 " + MAX_WHITELIST_SITES);
    System.out.println(
        "[permission-site-audit] files=" + filesScanned + " whitelistSites=" + total + "（P3 清零）");
  }

  /** 绑定形态计数：`:allow="`（无论字面量还是动态绑定都算站点；字面量与动态都被本门禁管住）。 */
  private static int countAllowBindings(String content) {
    int count = 0;
    int index = 0;
    while ((index = content.indexOf(":allow=\"", index)) >= 0) {
      count++;
      index += 1;
    }
    return count;
  }
}
