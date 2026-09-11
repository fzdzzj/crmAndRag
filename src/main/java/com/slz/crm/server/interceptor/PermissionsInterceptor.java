package com.slz.crm.server.interceptor;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PermissionsInterceptor implements HandlerInterceptor {
    @Resource
    private UserService userService;
    @Resource
    private PermissionService permissionService;

    /**
     * 校验接口权限，并把当前用户的部门 ID 填充进请求级角色对象。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  Spring MVC 处理器
     * @return true 表示继续执行后续拦截器与处理器
     * @throws Exception 用户状态异常、无权限或查询用户失败时抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {


        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }

        Long currentId = BaseUnit.getCurrentId();
        UserVO user = userService.getById(currentId);

        // DEPT/DEPT_AND_CHILD 依赖部门维度；在权限注解判断前填充，
        // 保证未加 @RequirePermission 的查询接口也能获得完整数据权限上下文。
        com.slz.crm.pojo.ao.RoleAO roleAO = BaseUnit.getCurrentRole();
        if (roleAO != null) {
            roleAO.setDeptId(user.getDeptId());
        }

        RequirePermission requirePermission = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (requirePermission == null) {
            return true;
        }

        if (user.getStatus() != 1) {
            if (user.getRoleId() == 0) {
                throw new BaseException(ErrorCode.USER_IS_FROZEN);
            }
            if (user.getRoleId() == 2) {
                throw new BaseException(ErrorCode.USER_IS_QUIT);
            } else {
                throw new BaseException(ErrorCode.USER_STATUS_EXCEPTION);
            }
        }

        // 如果用户角色ID为1，视为超级管理员，拥有所有权限
        if (user.getRoleId() == 1) {
            return true;
        }

        // 重新读取当前角色的 RoleAO，避免误用局部空引用
        roleAO = BaseUnit.getCurrentRole();

        // 如果RoleAO中没有权限列表,则加载并填充
        if (roleAO.getPermissions() == null || roleAO.getPermissions().isEmpty()) {
            // 从数据库加载角色权限列表
            java.util.List<com.slz.crm.pojo.entity.PermissionsEntity> permissionList =
                    permissionService.getPermissionList(user.getRoleId());

            // 将权限列表填入RoleAO
            roleAO.setPermissions(permissionList);
        }

        // 从注解中获取权限枚举常量
        PermissionOperates targetPermission = requirePermission.value();

        // 校验用户是否拥有目标权限(使用RoleAO中的权限列表)
        boolean hasPermission = roleAO.hasPermission(targetPermission);

        if (!hasPermission) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED);
        }
        return true;
    }
}
