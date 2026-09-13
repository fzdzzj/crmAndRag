package com.slz.crm.integration.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 端点权限覆盖比对器（audit-permission-matrix 任务 2.1，纯函数）。
 *
 * <p>输入扫描端点清单，结合 {@link OpenEndpointRegistry} 登记清单输出四档分级 finding：</p>
 * <ul>
 *   <li><b>SECURED</b>——已挂方法级 {@code @RequirePermission}（合规）；</li>
 *   <li><b>INTENTIONAL_OPEN</b>——登记于有意开放区（匿名/登录即可用，附理由）；</li>
 *   <li><b>PENDING_DECISION</b>——登记于待拍板区（已知零注解，显式知情）；</li>
 *   <li><b>CRITICAL</b>——写语义（POST/PUT/DELETE）零注解且未登记——门禁必失败项；</li>
 *   <li><b>WARN</b>——读语义（GET/ANY）零注解且未登记——门禁放行但报告待定夺。</li>
 * </ul>
 *
 * <p>与 {@code PermissionsInterceptor#preHandle} 口径一致：注解缺失 = 拦截器静默放行；
 * 本比对器把"缺失且未登记"显式化为 CRITICAL/WARN，结构性掩盖从此有 CI 信号。</p>
 */
public final class PermissionCoverageComparator {

    /** 分级：SECURED（合规）/ INTENTIONAL_OPEN（有意开放）/ PENDING_DECISION（待拍板）/ CRITICAL（写裸奔）/ WARN（读裸奔）。 */
    public enum Tier { SECURED, INTENTIONAL_OPEN, PENDING_DECISION, CRITICAL, WARN }

    /** 单条分级 finding：端点定位 + 档位 + 说明。 */
    public record Finding(String httpMethod, String path, String methodRef, Tier tier, String detail) {
        @Override
        public String toString() {
            return "[" + tier + "] " + httpMethod + " " + path + "（" + methodRef + "）" + detail;
        }
    }

    private PermissionCoverageComparator() {
    }

    /**
     * 对全量端点做覆盖分级。
     *
     * @param endpoints 扫描端点清单
     * @return 分级 finding（顺序：CRITICAL → WARN → PENDING_DECISION → INTENTIONAL_OPEN → SECURED，各组内按路径稳定序）
     */
    public static List<Finding> classify(List<EndpointCoverage> endpoints) {
        List<Finding> critical = new ArrayList<>();
        List<Finding> warn = new ArrayList<>();
        List<Finding> pending = new ArrayList<>();
        List<Finding> open = new ArrayList<>();
        List<Finding> secured = new ArrayList<>();

        for (EndpointCoverage endpoint : endpoints) {
            if (endpoint.secured()) {
                secured.add(toFinding(endpoint, Tier.SECURED, "（注解 " + endpoint.permissionName() + "）"));
                continue;
            }
            if (OpenEndpointRegistry.isIntentionalOpen(endpoint.httpMethod(), endpoint.path())) {
                open.add(toFinding(endpoint, Tier.INTENTIONAL_OPEN, "（有意开放，已登记）"));
                continue;
            }
            if (OpenEndpointRegistry.isPendingDecision(endpoint.httpMethod(), endpoint.path())) {
                pending.add(toFinding(endpoint, Tier.PENDING_DECISION, "（待权限映射拍板，已登记）"));
                continue;
            }
            if (isWriteSemantic(endpoint.httpMethod())) {
                critical.add(toFinding(endpoint, Tier.CRITICAL,
                        "（零注解未登记——写语义端点登录后任意用户可调用）"));
            } else {
                warn.add(toFinding(endpoint, Tier.WARN,
                        "（零注解未登记——读语义端点登录后任意用户可调用）"));
            }
        }

        List<Finding> findings = new ArrayList<>(endpoints.size());
        findings.addAll(critical);
        findings.addAll(warn);
        findings.addAll(pending);
        findings.addAll(open);
        findings.addAll(secured);
        return Collections.unmodifiableList(findings);
    }

    /** 写语义：POST/PUT/DELETE（GET/ANY 视为读语义）。 */
    public static boolean isWriteSemantic(String httpMethod) {
        return "POST".equals(httpMethod) || "PUT".equals(httpMethod) || "DELETE".equals(httpMethod);
    }

    private static Finding toFinding(EndpointCoverage endpoint, Tier tier, String detail) {
        return new Finding(endpoint.httpMethod(), endpoint.path(), endpoint.methodRef(), tier, detail);
    }
}
