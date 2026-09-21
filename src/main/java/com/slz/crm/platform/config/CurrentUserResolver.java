package com.slz.crm.platform.config;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.pojo.ao.RoleAO;
import org.springframework.stereotype.Component;

/**
 * 当前登录操作人解析（动态配置写入/审计的身份来源）。
 *
 * <p><b>为什么有两套来源：</b>冻结契约口径是 {@link UserContextHolder}（B/C/D/E 统一消费）， 但当前基座的 {@code JWTInterceptor}
 * 尚未接线到它、仍填充 CRM 既有 {@link BaseUnit}（RoleAO）。 因此解析顺序：契约口径优先（integrator 接线后自动生效）→ 回退 BaseUnit（现状可用）→
 * 两者皆无视为未登录（返回 null，调用方抛 UNAUTHORIZED）。
 *
 * <p>线程安全：无状态组件，线程安全。
 */
@Component
public class CurrentUserResolver {

  /**
   * @return 当前操作人；未登录（无任何身份来源）返回 {@code null}
   */
  public ConfigOperator resolve() {
    ConfigOperator result = null;
    UserContext ctx = UserContextHolder.current();
    if (ctx != null) {
      result = new ConfigOperator(ctx.userIdRef(), ctx.userId(), ctx.roleId(), ctx.displayName());
    } else {
      RoleAO role = BaseUnit.getCurrentRole();
      if (role != null && role.getId() != null && role.getRoleId() != null) {
        result = new ConfigOperator("user:" + role.getId(), role.getId(), role.getRoleId(), null);
      }
    }
    return result;
  }
}
