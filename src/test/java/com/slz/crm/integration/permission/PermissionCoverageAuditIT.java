package com.slz.crm.integration.permission;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 端点权限覆盖永久门禁 IT（audit-permission-matrix 任务 3.1）。
 *
 * <p><b>价值</b>：鉴权靠方法级 {@code @RequirePermission}，注解缺失时 {@code PermissionsInterceptor} 直接放行——"新
 * controller 零注解合入 CI 依旧全绿"的结构性掩盖从此有信号。本 IT 纯 JVM 静态扫描， <b>无 Docker / 无 Spring 上下文 / 无外网依赖</b>，本地与
 * CI 均真跑不跳过。
 *
 * <p><b>门禁语义</b>（与 tasks.md 3.1 一致）：
 *
 * <ul>
 *   <li>controller 扫描数 ≠ 登记数 → {@code PermissionCoverageScanner#scanControllers} 抛错即 fail；
 *   <li>CRITICAL（写语义 POST/PUT/DELETE 零注解未登记）非空 → fail，逐项输出类名/方法/HTTP 方法/路径；
 *   <li>WARN（读语义零注解未登记）与 PENDING_DECISION → 不失败（显式知情制，进报告待定夺）。
 * </ul>
 *
 * <p><b>首轮输出</b>：全量端点四档矩阵打印到 stdout，作为 {@code docs/permission-matrix-audit.md} 落盘依据 （同 schema
 * 审计先例）。
 */
class PermissionCoverageAuditIT {

  @Test
  void noCriticalEndpointWithoutPermissionAndRegistryConsistent() {
    // controller 登记校验：扫描数 ≠ 登记数即抛 IllegalStateException（含缺失/多出/类漂移明细）
    List<Class<?>> controllers = PermissionCoverageScanner.scanControllers();

    List<EndpointCoverage> endpoints = PermissionCoverageScanner.scanEndpoints();
    List<PermissionCoverageComparator.Finding> findings =
        PermissionCoverageComparator.classify(endpoints);

    List<PermissionCoverageComparator.Finding> critical =
        findings.stream()
            .filter(f -> f.tier() == PermissionCoverageComparator.Tier.CRITICAL)
            .toList();
    List<PermissionCoverageComparator.Finding> warn =
        findings.stream().filter(f -> f.tier() == PermissionCoverageComparator.Tier.WARN).toList();
    List<PermissionCoverageComparator.Finding> pending =
        findings.stream()
            .filter(f -> f.tier() == PermissionCoverageComparator.Tier.PENDING_DECISION)
            .toList();
    List<PermissionCoverageComparator.Finding> open =
        findings.stream()
            .filter(f -> f.tier() == PermissionCoverageComparator.Tier.INTENTIONAL_OPEN)
            .toList();
    List<PermissionCoverageComparator.Finding> secured =
        findings.stream()
            .filter(f -> f.tier() == PermissionCoverageComparator.Tier.SECURED)
            .toList();

    // 全量报告打印到 stdout——docs/permission-matrix-audit.md 以本输出为准
    StringBuilder report = new StringBuilder();
    report
        .append("\n===== 端点权限覆盖审计：controller=")
        .append(controllers.size())
        .append("（登记 ")
        .append(PermissionCoverageScanner.CONTROLLER_REGISTRY.size())
        .append("），端点=")
        .append(endpoints.size())
        .append("，四档分布 SECURED=")
        .append(secured.size())
        .append(" / INTENTIONAL_OPEN=")
        .append(open.size())
        .append(" / PENDING_DECISION=")
        .append(pending.size())
        .append(" / CRITICAL=")
        .append(critical.size())
        .append(" / WARN=")
        .append(warn.size())
        .append(" =====\n");
    report.append("-- CRITICAL（门禁必失败项）--\n");
    critical.forEach(f -> report.append("  ").append(f).append('\n'));
    report.append("-- WARN（零注解未登记读端点，报告待定夺）--\n");
    warn.forEach(f -> report.append("  ").append(f).append('\n'));
    report.append("-- PENDING_DECISION（零注解已登记待拍板）--\n");
    pending.forEach(f -> report.append("  ").append(f).append('\n'));
    report.append("-- INTENTIONAL_OPEN（有意开放已登记）--\n");
    open.forEach(f -> report.append("  ").append(f).append('\n'));
    report.append("-- SECURED（已挂注解，合规）--\n");
    secured.forEach(f -> report.append("  ").append(f).append('\n'));
    System.out.println(report);

    assertTrue(
        critical.isEmpty(),
        "CRITICAL 端点必须清零（写语义零注解未登记 = 登录后任意用户可写），共 "
            + critical.size()
            + " 处：\n"
            + critical.stream().map(String::valueOf).reduce("", (a, b) -> a + "  - " + b + "\n"));
    assertTrue(
        warn.isEmpty(),
        "WARN 端点应已全部登记进 PENDING_DECISION（零注解读端点未登记 = 遗漏登记），共 "
            + warn.size()
            + " 处：\n"
            + warn.stream().map(String::valueOf).reduce("", (a, b) -> a + "  - " + b + "\n"));
    // 自然校验：close-permission-read-gap 三端点必须落 SECURED 档（不被回退）
    List<String> securedPaths = secured.stream().map(f -> f.httpMethod() + " " + f.path()).toList();
    assertTrue(
        securedPaths.containsAll(
            List.of(
                "ANY /permission/list",
                "POST /permission/addORDeletePermissionsToRole",
                "GET /permission/getByRole")),
        "PermissionController 三端点必须落在 SECURED 档，实际=" + securedPaths);
  }

  @Test
  void pendingDecisionEntriesAllCorrespondToRealEndpoints() {
    // 防呆：登记清单里的 PENDING_DECISION 条目必须能在扫描端点中找到（防登记漂移使门禁失真）
    Map<String, Boolean> scanned = new TreeMap<>();
    PermissionCoverageScanner.scanEndpoints().forEach(e -> scanned.put(e.key(), true));

    List<String> missing =
        OpenEndpointRegistry.PENDING_DECISION.stream()
            .map(OpenEndpointRegistry.Entry::key)
            .filter(key -> !Boolean.TRUE.equals(scanned.get(key)))
            .toList();
    assertTrue(missing.isEmpty(), "PENDING_DECISION 存在扫描不到的登记条目（端点路径/方法漂移）：" + missing);
  }
}
