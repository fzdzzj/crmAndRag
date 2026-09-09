package com.slz.crm.unit.service;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.server.mapper.DataShareMapper;
import com.slz.crm.server.mapper.RolePermissionsMapper;
import com.slz.crm.server.mapper.TageResourceBindingMapper;
import com.slz.crm.server.mapper.TageRoleBindingMapper;
import com.slz.crm.server.service.impl.DataScopeServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("单条资源数据范围判断")
class DataScopeServiceTest {

    @Mock
    private TageRoleBindingMapper tageRoleBindingMapper;
    @Mock
    private TageResourceBindingMapper tageResourceBindingMapper;
    @Mock
    private DataShareMapper dataShareMapper;
    @Mock
    private RolePermissionsMapper rolePermissionsMapper;

    @InjectMocks
    private DataScopeServiceImpl service;

    @Test
    @DisplayName("超管可读取任意单条资源")
    void adminCanReadAnyResource() {
        assertTrue(service.canReadResource(role(7L, 1L), "sales_opportunity", 99L, 2L));
        verifyNoInteractions(
                tageRoleBindingMapper,
                tageResourceBindingMapper,
                dataShareMapper,
                rolePermissionsMapper);
    }

    @Test
    @DisplayName("SELF 范围允许业务相关人和显式共享资源")
    void selfScopeAllowsRelatedOrSharedResource() {
        RoleAO current = role(7L, 4L);
        when(rolePermissionsMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertTrue(service.canReadResource(current, "sales_opportunity", 99L, 7L, 8L));

        when(dataShareMapper.selectSharedResourceIds(7L, null, "sales_opportunity"))
                .thenReturn(List.of(99L));
        assertTrue(service.canReadResource(current, "sales_opportunity", 99L, 8L, 9L));
    }

    @Test
    @DisplayName("TAGE 范围允许角色标签绑定的资源")
    void tageScopeAllowsBoundResource() {
        RoleAO current = role(7L, 3L);
        RolePermissionsEntity tagePermission = new RolePermissionsEntity();
        tagePermission.setPermissionsId(PermissionOperates.CUSTOMER_VIEW_COMPANY_TAGE.getId());
        when(rolePermissionsMapper.selectList(any())).thenReturn(List.of(tagePermission));
        when(dataShareMapper.selectSharedResourceIds(7L, null, "customer_company"))
                .thenReturn(Collections.emptyList());
        when(tageRoleBindingMapper.selectTageIdsByRoleId(3L)).thenReturn(List.of(11L));
        when(tageResourceBindingMapper.selectResourceIdsByTageIds(
                List.of(11L), "customer_company")).thenReturn(List.of(99L));

        assertTrue(service.canReadResource(current, "customer_company", 99L, 8L));
    }

    @Test
    @DisplayName("无业务关系、共享或标签时拒绝单条资源")
    void unrelatedResourceIsDenied() {
        RoleAO current = role(7L, 4L);
        when(rolePermissionsMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dataShareMapper.selectSharedResourceIds(7L, null, "sales_opportunity"))
                .thenReturn(Collections.emptyList());

        assertFalse(service.canReadResource(current, "sales_opportunity", 99L, 8L, 9L));
    }

    private RoleAO role(Long userId, Long roleId) {
        RoleAO role = new RoleAO();
        role.setId(userId);
        role.setRoleId(roleId);
        return role;
    }
}
