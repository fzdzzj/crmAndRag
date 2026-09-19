package com.slz.crm.unit.server.service;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.service.KnowledgeAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 知识库管理服务当前用户桥接测试：BaseUnit 上下文（JWTInterceptor 现状口径）
 * 必须能桥接为合法 UserContext；无任何上下文时按未登录抛 TOKEN_ERROR。
 * 历史缺陷：兜底直接构造 0 值 UserContext，违反契约自检导致所有 /knowledge 端点 500。
 */
class KnowledgeAdminCurrentUserTest {

    private final KnowledgeAdminService service = new KnowledgeAdminService(null, null, null, null, null);

    @AfterEach
    void cleanUpContext() {
        BaseUnit.removeCurrentId();
    }

    @Test
    void shouldBridgeBaseUnitRoleToValidUserContext() throws Exception {
        RoleAOHolder.setIdAndRole(9L, 1L, 11L);
        UserContext user = currentUser();
        assertEquals(9L, user.userId());
        assertEquals(1L, user.roleId());
        assertEquals(11L, user.deptId());
        assertEquals("user:9", user.userIdRef());
    }

    @Test
    void shouldRejectWhenNoContextAvailable() throws Exception {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, this::currentUser);
        assertEquals(BaseException.class, thrown.getCause().getClass());
    }

    private UserContext currentUser() throws Exception {
        Method method = KnowledgeAdminService.class.getDeclaredMethod("currentUser");
        method.setAccessible(true);
        return (UserContext) method.invoke(service);
    }

    /** RoleAO 是贫血对象，这里只做测试夹具。 */
    private static final class RoleAOHolder {
        static void setIdAndRole(Long userId, Long roleId, Long deptId) {
            com.slz.crm.pojo.ao.RoleAO role = new com.slz.crm.pojo.ao.RoleAO();
            role.setId(userId);
            role.setRoleId(roleId);
            role.setDeptId(deptId);
            BaseUnit.setCurrentRole(role);
        }
    }
}
