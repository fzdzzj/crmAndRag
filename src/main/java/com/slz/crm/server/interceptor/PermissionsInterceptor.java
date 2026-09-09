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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {


        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }

        RequirePermission requirePermission = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (requirePermission == null) {
            return true;
        }

        Long currentId = BaseUnit.getCurrentId();
        UserVO user = userService.getById(currentId);

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

        // 获取当前角色的RoleAO
        com.slz.crm.pojo.ao.RoleAO roleAO = BaseUnit.getCurrentRole();

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
