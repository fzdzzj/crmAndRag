package com.slz.crm.integration.permission;

/**
 * 端点权限覆盖记录（audit-permission-matrix 任务 1.2）。
 *
 * <p>扫描器产出的单个端点：HTTP 方法 + 路径 + 类#方法引用 + 是否带方法级
 * {@code @RequirePermission} 及其枚举值。路径由类级 {@code @RequestMapping} 前缀
 * 与方法级 mapping 拼接而成，仅用于报告展示与登记匹配，不参与运行期路由。</p>
 *
 * @param httpMethod     HTTP 方法（GET/POST/PUT/DELETE；方法级 {@code @RequestMapping} 未声明方法时为 ANY）
 * @param path           拼接后完整路径（如 {@code /assist/my}）
 * @param methodRef      端点所属 类名#方法名（报告定位用）
 * @param secured        是否带方法级 {@code @RequirePermission}
 * @param permissionName 注解枚举名（{@code PermissionOperates}），无注解时为 {@code null}
 * @param controllerClass 所属 controller 全限定类名
 */
public record EndpointCoverage(
        String httpMethod,
        String path,
        String methodRef,
        boolean secured,
        String permissionName,
        String controllerClass) {

    /** 登记匹配键：{@code "METHOD path"}（如 {@code GET /assist/my}）。 */
    public String key() {
        return httpMethod + " " + path;
    }
}
