package com.slz.crm.integration.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 开放端点登记清单（audit-permission-matrix 任务 2.3，显式登记制）。
 *
 * <p>端点权限覆盖显式制：任何零注解端点必须能在此登记清单找到，否则门禁按 CRITICAL/WARN 处理。
 * 两区：</p>
 * <ul>
 *   <li><b>INTENTIONAL_OPEN</b>——有意开放（JWT 层排除的匿名路径 + 业务必需登录即可用），各附理由；
 *       支持路径前缀登记（如 {@code /public/**}，匹配其下任意方法任意路径）；</li>
 *   <li><b>PENDING_DECISION</b>——已知零注解、待权限映射拍板（理由统一"待权限映射拍板"）。
 *       首轮审计把实测全部零注解端点（摸底 40 + 新发现 22 = 62，其中 5 个属 INTENTIONAL_OPEN）登记进来；
 *       拍板后落地为注解或转 INTENTIONAL_OPEN，本区条目随之移除。</li>
 * </ul>
 *
 * <p>匹配键 = {@code "METHOD path"}（如 {@code GET /assist/my}）；登记不区分大小写敏感细节。</p>
 */
public final class OpenEndpointRegistry {

    /** 单条登记：HTTP 方法（ANY 表示任意方法）+ 路径（以 {@code /**} 结尾为前缀匹配）+ 理由 + 来源定位。 */
    public record Entry(String httpMethod, String path, String reason, String source) {
        public String key() {
            return httpMethod + " " + path;
        }
    }

    // ==================== 有意开放区（登记理由充分，门禁直接放行） ====================

    /** 有意开放端点。仅当前 5 条；新增有意开放端点必须在此登记并附理由。 */
    public static final List<Entry> INTENTIONAL_OPEN = buildIntentionalOpen();

    // ==================== 待拍板区（首轮审计实测零注解端点全量登记） ====================

    /** 待权限映射拍板端点。57 条 = 摸底 40 + 新发现 22 − 已归 INTENTIONAL_OPEN 的 5 条。 */
    public static final List<Entry> PENDING_DECISION = buildPendingDecision();

    private OpenEndpointRegistry() {
    }

    /**
     * 判断端点是否登记为有意开放。
     *
     * @param httpMethod HTTP 方法（GET/POST/PUT/DELETE/ANY）
     * @param path       完整路径
     * @return 精确键匹配，或命中 {@code /x/**} 前缀登记且路径在其下
     */
    public static boolean isIntentionalOpen(String httpMethod, String path) {
        String key = httpMethod + " " + path;
        for (Entry entry : INTENTIONAL_OPEN) {
            if (entry.path().endsWith("/**")) {
                if (path.startsWith(entry.path().substring(0, entry.path().length() - 2))) {
                    return true;
                }
            } else if (entry.key().equals(key) || ("ANY ".equals(entry.httpMethod() + " ") && key.endsWith(entry.path()))) {
                return true;
            }
        }
        return false;
    }

    /** 判断端点是否登记为待拍板。 */
    public static boolean isPendingDecision(String httpMethod, String path) {
        return PENDING_DECISION.stream().anyMatch(e -> e.key().equals(httpMethod + " " + path));
    }

    private static List<Entry> buildIntentionalOpen() {
        List<Entry> list = new ArrayList<>();
        // JWT 层排除（WebMvcConfiguration#publicPaths + JWTInterceptor 双重口径）
        list.add(new Entry("POST", "/user/login", "JWT 层排除：登录接口匿名访问（publicPaths /login）", "UserController#login"));
        list.add(new Entry("GET", "/health", "JWT 层排除：健康检查匿名探针（publicPaths /health）", "HealthController#health"));
        list.add(new Entry("GET", "/public/**", "JWT 层排除：公开附件下载路径（publicPaths /public/**，令牌二次鉴权）", "PublicAttachmentController#downloadAttachment"));
        // 业务必需登录即可用（close-permission-read-gap 拍板结论，用户已确认）
        list.add(new Entry("ANY", "/permission/getMyPermission", "业务必需：用户自查自身权限（close-permission-read-gap 拍板开放）", "PermissionController#getMyPermission"));
        list.add(new Entry("GET", "/permission/auditor", "业务必需：审批人下拉选择（close-permission-read-gap 拍板开放）", "PermissionController#getAuditorList"));
        return Collections.unmodifiableList(list);
    }

    private static List<Entry> buildPendingDecision() {
        List<Entry> list = new ArrayList<>();
        // 摸底 40 端点（Assist/AiChat/AiAction/DataStatistics/Report），理由统一待拍板
        String pendingReason = "待权限映射拍板";
        // ---- AssistController 23 ----
        add(list, "POST", "/assist/append", pendingReason, "AssistController#append");
        add(list, "POST", "/assist/apply", pendingReason, "AssistController#apply");
        add(list, "GET", "/assist/{id}/task", pendingReason, "AssistController#task");
        add(list, "PUT", "/assist", pendingReason, "AssistController#handle");
        add(list, "GET", "/assist/{id}/messages", pendingReason, "AssistController#messages");
        add(list, "GET", "/assist/{id}/attachments", pendingReason, "AssistController#attachments");
        add(list, "POST", "/assist/{id}/attachments", pendingReason, "AssistController#uploadAttachments");
        add(list, "GET", "/assist/{id}/opportunity", pendingReason, "AssistController#opportunity");
        add(list, "GET", "/assist/applications", pendingReason, "AssistController#myApplications");
        add(list, "DELETE", "/assist/{id}/attachments", pendingReason, "AssistController#deleteAttachments");
        add(list, "POST", "/assist/{id}/messages", pendingReason, "AssistController#sendMessage");
        add(list, "GET", "/assist/{id}/activity/attachments", pendingReason, "AssistController#sourceActivityAttachments");
        add(list, "DELETE", "/assist/{id}/source-attachments", pendingReason, "AssistController#deleteSourceAttachments");
        add(list, "GET", "/assist/{id}/task/attachments", pendingReason, "AssistController#sourceTaskAttachments");
        add(list, "POST", "/assist/{id}/source-attachments", pendingReason, "AssistController#uploadSourceAttachments");
        add(list, "GET", "/assist/{id}/contact", pendingReason, "AssistController#contact");
        add(list, "GET", "/assist/{id}/related", pendingReason, "AssistController#related");
        add(list, "GET", "/assist/{id}/company", pendingReason, "AssistController#company");
        add(list, "GET", "/assist/{id}/detail", pendingReason, "AssistController#detail");
        add(list, "GET", "/assist/{id}/approval", pendingReason, "AssistController#approval");
        add(list, "POST", "/assist/reapply", pendingReason, "AssistController#reapply");
        add(list, "GET", "/assist/{id}/activity", pendingReason, "AssistController#activity");
        add(list, "GET", "/assist/my", pendingReason, "AssistController#myAssists");
        // ---- AiChatController 7 ----
        add(list, "POST", "/ai/sessions", pendingReason, "AiChatController#createSession");
        add(list, "GET", "/ai/sessions/{id}/messages", pendingReason, "AiChatController#listMessages");
        add(list, "GET", "/ai/sessions", pendingReason, "AiChatController#listSessions");
        add(list, "DELETE", "/ai/sessions/{id}", pendingReason, "AiChatController#archiveSession");
        add(list, "POST", "/ai/sessions/{sessionId}/images", pendingReason, "AiChatController#uploadImage");
        add(list, "POST", "/ai/chat/cancel", pendingReason, "AiChatController#cancelChat");
        add(list, "POST", "/ai/chat/stream", pendingReason, "AiChatController#streamChat");
        // ---- AiActionController 4 ----
        add(list, "POST", "/ai/actions/{pendingId}/cancel", pendingReason, "AiActionController#cancel");
        add(list, "GET", "/ai/actions/{pendingId}", pendingReason, "AiActionController#getStatus");
        add(list, "PUT", "/ai/actions/{pendingId}/edit", pendingReason, "AiActionController#edit");
        add(list, "POST", "/ai/actions/{pendingId}/confirm", pendingReason, "AiActionController#confirm");
        // ---- DataStatisticsController 4 ----
        add(list, "POST", "/dataStatistics/chartData", pendingReason, "DataStatisticsController#getChartData");
        add(list, "POST", "/dataStatistics/chart", pendingReason, "DataStatisticsController#generateLineChart");
        add(list, "GET", "/dataStatistics/opportunityStageDistribution", pendingReason, "DataStatisticsController#getOpportunityStageDistribution");
        add(list, "POST", "/dataStatistics/summary", pendingReason, "DataStatisticsController#getStatisticsSummary");
        // ---- ReportController 2 ----
        add(list, "GET", "/report/business", pendingReason, "ReportController#getTotalBusinessNum");
        add(list, "GET", "/report/contract", pendingReason, "ReportController#getTotalSignContractNum");

        // ---- 审计新发现 22 端点（摸底遗漏，扫描实测登记）----
        // ---- UserController 6（/user/login 已归 INTENTIONAL_OPEN）----
        add(list, "DELETE", "/user", pendingReason, "UserController#delete");
        add(list, "GET", "/user/options", pendingReason, "UserController#options");
        add(list, "POST", "/user/update/my", pendingReason, "UserController#updateUserMy");
        add(list, "POST", "/user/password", pendingReason, "UserController#updatePassword");
        add(list, "POST", "/user/find", pendingReason, "UserController#findUser");
        add(list, "GET", "/user/my", pendingReason, "UserController#getMyUser");
        // ---- DynamicConfigAdminController 7 ----
        add(list, "POST", "/platform/config/items", pendingReason, "DynamicConfigAdminController#update");
        add(list, "GET", "/platform/config/items", pendingReason, "DynamicConfigAdminController#list");
        add(list, "DELETE", "/platform/config/items/{key}", pendingReason, "DynamicConfigAdminController#delete");
        add(list, "POST", "/platform/config/cache/refresh", pendingReason, "DynamicConfigAdminController#refreshCache");
        add(list, "GET", "/platform/config/items/{key}/history", pendingReason, "DynamicConfigAdminController#history");
        add(list, "POST", "/platform/config/items/{key}/rollback", pendingReason, "DynamicConfigAdminController#rollback");
        add(list, "GET", "/platform/config/items/{key}", pendingReason, "DynamicConfigAdminController#detail");
        // ---- CustomerCompanyController 1 ----
        add(list, "GET", "/company/template", pendingReason, "CustomerCompanyController#template");
        // ---- CustomerContactController 2 ----
        add(list, "GET", "/contact/template", pendingReason, "CustomerContactController#template");
        add(list, "GET", "/contact/auditor", pendingReason, "CustomerContactController#auditor");
        // ---- RoleController 1 ----
        add(list, "GET", "/role", pendingReason, "RoleController#getMyRole");
        return Collections.unmodifiableList(list);
    }

    private static void add(List<Entry> list, String method, String path, String reason, String source) {
        list.add(new Entry(method, path, reason, source));
    }
}
