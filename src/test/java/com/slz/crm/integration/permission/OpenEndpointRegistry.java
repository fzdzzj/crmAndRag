package com.slz.crm.integration.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 开放端点登记清单（audit-permission-matrix 任务 2.3，显式登记制）。
 *
 * <p>端点权限覆盖显式制：任何零注解端点必须能在此登记清单找到，否则门禁按 CRITICAL/WARN 处理。 两区：
 *
 * <ul>
 *   <li><b>INTENTIONAL_OPEN</b>——有意开放（JWT 层排除的匿名路径 + 业务必需登录即可用），各附理由； 支持路径前缀登记（如 {@code
 *       /public/**}，匹配其下任意方法任意路径）；
 *   <li><b>PENDING_DECISION</b>——已知零注解、待权限映射拍板（理由统一"待权限映射拍板"）。 首轮审计把实测全部零注解端点（摸底 40 + 新发现 17 =
 *       57）登记进来； 拍板后落地为注解或转 INTENTIONAL_OPEN，本区条目随之移除。
 * </ul>
 *
 * <p>匹配键 = {@code "METHOD path"}（如 {@code GET /assist/my}）；登记不区分大小写敏感细节。
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

  /** 待权限映射拍板端点。57 条 = 摸底 40 + 新发现 17。 */
  public static final List<Entry> PENDING_DECISION = buildPendingDecision();

  private OpenEndpointRegistry() {}

  /**
   * 判断端点是否登记为有意开放。
   *
   * @param httpMethod HTTP 方法（GET/POST/PUT/DELETE/ANY）
   * @param path 完整路径
   * @return 精确键匹配，或命中 {@code /x/**} 前缀登记且路径在其下
   */
  public static boolean isIntentionalOpen(String httpMethod, String path) {
    String key = httpMethod + " " + path;
    for (Entry entry : INTENTIONAL_OPEN) {
      if (entry.path().endsWith("/**")) {
        if (path.startsWith(entry.path().substring(0, entry.path().length() - 2))) {
          return true;
        }
      } else if (entry.key().equals(key)
          || ("ANY ".equals(entry.httpMethod() + " ") && key.endsWith(entry.path()))) {
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
    list.add(
        new Entry(
            "POST", "/user/login", "JWT 层排除：登录接口匿名访问（publicPaths /login）", "UserController#login"));
    list.add(
        new Entry(
            "GET", "/health", "JWT 层排除：健康检查匿名探针（publicPaths /health）", "HealthController#health"));
    list.add(
        new Entry(
            "GET",
            "/public/**",
            "JWT 层排除：公开附件下载路径（publicPaths /public/**，令牌二次鉴权）",
            "PublicAttachmentController#downloadAttachment"));
    // 业务必需登录即可用（close-permission-read-gap 拍板结论，用户已确认）
    list.add(
        new Entry(
            "ANY",
            "/permission/getMyPermission",
            "业务必需：用户自查自身权限（close-permission-read-gap 拍板开放）",
            "PermissionController#getMyPermission"));
    list.add(
        new Entry(
            "GET",
            "/permission/auditor",
            "业务必需：审批人下拉选择（close-permission-read-gap 拍板开放）",
            "PermissionController#getAuditorList"));

    // apply-permission-matrix 任务 3.2：自服务端点（用户改密/改己信息/查己信息/在职下拉，登录即可用）
    list.add(
        new Entry(
            "POST", "/user/password", "自服务：用户改自己密码（业务必需登录即可用）", "UserController#updatePassword"));
    list.add(
        new Entry(
            "POST", "/user/update/my", "自服务：用户改自己信息（业务必需登录即可用）", "UserController#updateUserMy"));
    list.add(new Entry("GET", "/user/my", "自服务：用户查自己信息（业务必需登录即可用）", "UserController#getMyUser"));
    list.add(
        new Entry("GET", "/user/options", "自服务：在职用户下拉（协助人选择器，登录即可用）", "UserController#options"));
    // apply-permission-matrix 任务 3.3（拍板工程默认 2）：静态 Excel 模板（无数据暴露面，导入流程依赖）
    list.add(
        new Entry(
            "GET",
            "/company/template",
            "静态 Excel 模板无数据面，导入流程依赖（拍板工程默认 2）",
            "CustomerCompanyController#template"));
    list.add(
        new Entry(
            "GET",
            "/contact/template",
            "静态 Excel 模板无数据面，导入流程依赖（拍板工程默认 2）",
            "CustomerContactController#template"));
    // apply-permission-matrix 任务 3.4：自查/下拉（与 /permission/auditor 同款）
    list.add(
        new Entry(
            "GET",
            "/contact/auditor",
            "业务必需：联系人侧审批人下拉（与 /permission/auditor 同款）",
            "CustomerContactController#auditor"));
    list.add(new Entry("GET", "/role", "业务必需：用户自查自身角色（登录即可用）", "RoleController#getMyRole"));
    // apply-permission-matrix 任务 3.5：DynamicConfig（服务层已强制 roleId=1 抛 96005，登记避免双重鉴权漂移）
    list.add(
        new Entry(
            "GET",
            "/platform/config/items",
            "服务层已强制 roleId=1 抛 96005（登记避免双重鉴权漂移）",
            "DynamicConfigAdminController#list"));
    list.add(
        new Entry(
            "GET",
            "/platform/config/items/{key}",
            "服务层已强制 roleId=1 抛 96005（登记避免双重鉴权漂移）",
            "DynamicConfigAdminController#detail"));
    list.add(
        new Entry(
            "GET",
            "/platform/config/items/{key}/history",
            "服务层已强制 roleId=1 抛 96005（登记避免双重鉴权漂移）",
            "DynamicConfigAdminController#history"));
    list.add(
        new Entry(
            "POST",
            "/platform/config/items",
            "服务层已强制 roleId=1 抛 96005（登记避免双重鉴权漂移）",
            "DynamicConfigAdminController#update"));
    list.add(
        new Entry(
            "POST",
            "/platform/config/items/{key}/rollback",
            "服务层已强制 roleId=1 抛 96005（登记避免双重鉴权漂移）",
            "DynamicConfigAdminController#rollback"));
    list.add(
        new Entry(
            "DELETE",
            "/platform/config/items/{key}",
            "服务层已强制 roleId=1 抛 96005（登记避免双重鉴权漂移）",
            "DynamicConfigAdminController#delete"));
    list.add(
        new Entry(
            "POST",
            "/platform/config/cache/refresh",
            "服务层已强制 roleId=1 抛 96005（登记避免双重鉴权漂移）",
            "DynamicConfigAdminController#refreshCache"));
    return Collections.unmodifiableList(list);
  }

  private static List<Entry> buildPendingDecision() {
    // apply-permission-matrix 任务 3.6：PENDING_DECISION 已全部消解（40 挂注解转 SECURED + 17 转
    // INTENTIONAL_OPEN 或注解），拍板记录见 docs/permission-matrix-audit.md §6。
    // 本区保持为空——任何新零注解端点将触发门禁 CRITICAL/WARN 报红。
    return Collections.unmodifiableList(new ArrayList<>());
  }
}
