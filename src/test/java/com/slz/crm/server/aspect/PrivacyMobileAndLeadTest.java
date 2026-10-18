package com.slz.crm.server.aspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.SalesLeadVO;
import com.slz.crm.server.service.PermissionService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 手机号脱敏激活与销售线索 PII 补漏专项测试（harden-user-password-and-privacy 任务 3.2，纯 JVM Mockito）。
 *
 * <p>直接驱动 {@code PrivacyAspect#after}（JoinPoint 入参未参与逻辑，传 null）验证： 无 PRIVACY_PHONE_VIEW 权限时联系人
 * {@code mobile} 与线索 {@code phone}/{@code email} 被脱敏为 ***； 有权限时不脱敏。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrivacyMobileAndLeadTest 手机号脱敏激活与线索 PII 专项测试")
class PrivacyMobileAndLeadTest {

  private static final Long CURRENT_USER_ID = 9001L;
  private static final Long CURRENT_ROLE_ID = 5L;

  @Mock private PermissionService permissionService;

  @InjectMocks private PrivacyAspect aspect;

  private CustomerContactVO contact;

  private SalesLeadVO lead;

  @BeforeEach
  void setUp() {
    RoleAO current = new RoleAO();
    current.setId(CURRENT_USER_ID);
    current.setRoleId(CURRENT_ROLE_ID);
    BaseUnit.setCurrentRole(current);
    when(permissionService.getPermissionList(CURRENT_ROLE_ID)).thenReturn(List.of());

    contact = new CustomerContactVO();
    contact.setPhone("0571-88880000");
    contact.setMobile("13800001111");
    contact.setEmail("contact@corp.local");

    lead = new SalesLeadVO();
    lead.setOwnerId(1L);
    lead.setPhone("13900002222");
    lead.setEmail("lead@corp.local");
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  private void stubPermissions(boolean phoneView, boolean emailView) {
    when(permissionService.hasPermission(eq(PermissionOperates.PRIVACY_PHONE_VIEW), anyList()))
        .thenReturn(phoneView);
    when(permissionService.hasPermission(eq(PermissionOperates.PRIVACY_EMAIL_VIEW), anyList()))
        .thenReturn(emailView);
  }

  @Test
  @DisplayName("1. 无 phone/email 查看权限：联系人 mobile+phone+email 与线索 phone+email 全部脱敏")
  void shouldMaskMobileAndLeadPiiWithoutPermission() {
    stubPermissions(false, false);

    aspect.after(null, List.of(contact, lead));

    assertEquals("***", contact.getMobile());
    assertEquals("***", contact.getPhone());
    assertEquals("***", contact.getEmail());
    assertEquals("***", lead.getPhone());
    assertEquals("***", lead.getEmail());
  }

  @Test
  @DisplayName("2. 有 PRIVACY_PHONE_VIEW 无 EMAIL_VIEW：手机号保留、邮箱脱敏（mobile 不被误脱敏）")
  void shouldKeepMobileWhenPhoneViewGranted() {
    stubPermissions(true, false);

    aspect.after(null, List.of(contact, lead));

    assertEquals("13800001111", contact.getMobile());
    assertEquals("0571-88880000", contact.getPhone());
    assertEquals("***", contact.getEmail());
    assertEquals("13900002222", lead.getPhone());
    assertEquals("***", lead.getEmail());
  }

  @Test
  @DisplayName("3. 双权限齐全：全部字段保留原文")
  void shouldKeepAllFieldsWhenAllPermissionsGranted() {
    stubPermissions(true, true);

    aspect.after(null, List.of(contact, lead));

    assertEquals("13800001111", contact.getMobile());
    assertEquals("0571-88880000", contact.getPhone());
    assertEquals("contact@corp.local", contact.getEmail());
    assertEquals("13900002222", lead.getPhone());
    assertEquals("lead@corp.local", lead.getEmail());
  }
}
