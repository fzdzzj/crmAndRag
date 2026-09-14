package com.slz.crm.integration.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限覆盖比对器纯函数单测（audit-permission-matrix 任务 2.2，≥4 条）。
 *
 * <p>夹具覆盖四档分类 + 全 SECURED 零 finding + PENDING_DECISION 已消解（转 SECURED/INTENTIONAL_OPEN） +
 * INTENTIONAL_OPEN 前缀匹配（{@code /public/**}）。</p>
 */
class PermissionCoverageComparatorTest {

    private static EndpointCoverage ep(String method, String path, boolean secured, String permission) {
        return new EndpointCoverage(method, path, "Fixture#m", secured, permission, "com.slz.crm.FixtureController");
    }

    private static List<PermissionCoverageComparator.Finding> byTier(
            List<PermissionCoverageComparator.Finding> findings, PermissionCoverageComparator.Tier tier) {
        return findings.stream().filter(f -> f.tier() == tier).toList();
    }

    @Test
    void securedAndIntentionalOpenClassifiedAndPendingCleared() {
        List<EndpointCoverage> endpoints = List.of(
                ep("POST", "/contract", true, "SALES_CREATE_CONTRACT"),                 // SECURED
                ep("GET", "/permission/auditor", false, null),                           // INTENTIONAL_OPEN（登记）
                ep("GET", "/user/my", false, null));                                     // INTENTIONAL_OPEN（自服务，登记）

        List<PermissionCoverageComparator.Finding> findings = PermissionCoverageComparator.classify(endpoints);

        assertEquals(1, byTier(findings, PermissionCoverageComparator.Tier.SECURED).size());
        assertEquals(2, byTier(findings, PermissionCoverageComparator.Tier.INTENTIONAL_OPEN).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.PENDING_DECISION).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.CRITICAL).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.WARN).size());
    }

    @Test
    void unregisteredWriteEndpointIsCriticalAndReadIsWarn() {
        List<EndpointCoverage> endpoints = List.of(
                ep("POST", "/fixture/write", false, null),
                ep("PUT", "/fixture/update", false, null),
                ep("DELETE", "/fixture/delete", false, null),
                ep("GET", "/fixture/read", false, null),
                ep("ANY", "/fixture/any", false, null));

        List<PermissionCoverageComparator.Finding> findings = PermissionCoverageComparator.classify(endpoints);

        List<PermissionCoverageComparator.Finding> critical = byTier(findings, PermissionCoverageComparator.Tier.CRITICAL);
        assertEquals(3, critical.size(), "POST/PUT/DELETE 零注解未登记 = CRITICAL");
        List<PermissionCoverageComparator.Finding> warn = byTier(findings, PermissionCoverageComparator.Tier.WARN);
        assertEquals(2, warn.size(), "GET/ANY 零注解未登记 = WARN");
    }

    @Test
    void allSecuredFixtureProducesZeroUncoveredFindings() {
        List<EndpointCoverage> endpoints = List.of(
                ep("GET", "/a", true, "A"),
                ep("POST", "/b", true, "B"),
                ep("DELETE", "/c", true, "C"));

        List<PermissionCoverageComparator.Finding> findings = PermissionCoverageComparator.classify(endpoints);

        assertEquals(3, byTier(findings, PermissionCoverageComparator.Tier.SECURED).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.CRITICAL).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.WARN).size());
    }

    @Test
    void assistRealEndpointsNowSecuredWithNoPendingDecision() {
        // apply-permission-matrix 任务 3.6：PENDING_DECISION 已全部消解——AssistController 真实端点按方案A挂 800 段注解转 SECURED
        List<EndpointCoverage> endpoints = List.of(
                ep("POST", "/assist/apply", true, "AI_ASSIST_APPLY"),
                ep("GET", "/assist/my", true, "AI_ASSIST_VIEW"));

        List<PermissionCoverageComparator.Finding> findings = PermissionCoverageComparator.classify(endpoints);

        assertEquals(2, byTier(findings, PermissionCoverageComparator.Tier.SECURED).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.PENDING_DECISION).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.CRITICAL).size());
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.WARN).size());
    }

    @Test
    void publicPrefixRegistryMatchesAnyPathUnderIt() {
        List<EndpointCoverage> endpoints = List.of(
                ep("GET", "/public/attachment/download", false, null),
                ep("GET", "/public/other/new-endpoint", false, null));

        List<PermissionCoverageComparator.Finding> findings = PermissionCoverageComparator.classify(endpoints);

        assertTrue(byTier(findings, PermissionCoverageComparator.Tier.INTENTIONAL_OPEN).size() == 2,
                "/public/** 前缀登记应匹配其下任意路径（含未来新端点）");
    }

    @Test
    void comparatorMatchesEveryRealZeroAnnotationEndpoint() {
        // 门禁同构校验：全量扫描端点中，除 INTENTIONAL_OPEN 外，零注解端点必须全部落在 PENDING_DECISION 区
        List<EndpointCoverage> endpoints = PermissionCoverageScanner.scanEndpoints();
        List<PermissionCoverageComparator.Finding> findings = PermissionCoverageComparator.classify(endpoints);

        Set<String> uncovered = findings.stream()
                .filter(f -> f.tier() == PermissionCoverageComparator.Tier.CRITICAL
                        || f.tier() == PermissionCoverageComparator.Tier.WARN)
                .map(PermissionCoverageComparator.Finding::path)
                .collect(Collectors.toSet());
        assertTrue(uncovered.isEmpty(), "实测零注解端点必须全部登记（CRITICAL/WARN 应为空），未覆盖：" + uncovered);
        assertEquals(0, byTier(findings, PermissionCoverageComparator.Tier.PENDING_DECISION).size(),
                "PENDING_DECISION 已全部消解为 0（apply-permission-matrix 任务 3.6）");
    }
}
