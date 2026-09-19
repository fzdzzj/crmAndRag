package com.slz.crm.unit.server.interceptor;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.interceptor.PermissionsInterceptor;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限拦截器上下文边界测试：登录等无 token 请求未建立 BaseUnit 上下文时必须直接放行
 * （历史回归：登录请求在 preHandle 读取空 ThreadLocal 直接 NPE）；
 * 已建立上下文的请求仍走完整状态闸。
 */
class PermissionsInterceptorTest {

    private final PermissionsInterceptor interceptor = new PermissionsInterceptor();

    @AfterEach
    void cleanUpContext() {
        BaseUnit.removeCurrentId();
    }

    @Test
    void preHandleShouldPassThroughWhenContextAbsent() throws Exception {
        // 模拟 JWTInterceptor 对 /user/login 提前放行且未设置上下文
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("POST", "/user/login"),
                new MockHttpServletResponse(),
                handlerMethod()));
    }

    @Test
    void preHandleShouldEnforceUserStatusGateWhenContextPresent() {
        UserService userService = mock(UserService.class);
        UserVO frozen = new UserVO();
        frozen.setRoleId(0L);
        frozen.setStatus(0);
        when(userService.getById(anyLong())).thenReturn(frozen);
        setField("userService", userService);
        setField("permissionService", mock(PermissionService.class));

        RoleAO roleAO = new RoleAO();
        roleAO.setId(9L);
        BaseUnit.setCurrentRole(roleAO);

        assertThrows(BaseException.class, () -> interceptor.preHandle(
                new MockHttpServletRequest("GET", "/knowledge/bases"),
                new MockHttpServletResponse(),
                handlerMethod()));

        Mockito.verify(userService).getById(9L);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field field = PermissionsInterceptor.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(interceptor, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private HandlerMethod handlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new Object(), Object.class.getDeclaredMethod("toString"));
    }
}
