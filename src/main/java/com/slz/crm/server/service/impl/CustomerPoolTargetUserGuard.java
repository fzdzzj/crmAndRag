package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.UserEntity;

/**
 * 公海分派目标用户闸（fix-minor-debt-family 任务 4.1）。
 *
 * <p>独立成类而非内联在 {@code CustomerPoolServiceImpl}：该服务类已贴着 PMD 的类级圈复杂度/NCSS 门限（75 / 150），
 * 内联本闸会把类整体推过线；本闸自身分支固定且小，抽离后两处门限都回到线下，语义仍在调用点显式表达。
 *
 * <p>状态语义与 {@code PermissionsInterceptor#checkUserStatus} 同口径——{@code status != 1} 视为非正常，其中
 * roleId=0 为冻结、roleId=2 为离职，其余归状态异常；错误码全部复用既有码，不新增常量。
 */
final class CustomerPoolTargetUserGuard {

  private CustomerPoolTargetUserGuard() {}

  /**
   * 校验分派目标用户存在且在职。
   *
   * @param user 目标用户（按 id 查库的结果，可为 null）
   * @throws BaseException 用户不存在（ID_NOT_EXISTS）/ 冻结（USER_IS_FROZEN）/ 离职（USER_IS_QUIT）/
   *     状态异常（USER_STATUS_EXCEPTION）
   */
  static void assertAssignable(UserEntity user) {
    if (user == null) {
      throw new BaseException(
          ErrorCode.ID_NOT_EXISTS, ErrorCode.ID_NOT_EXISTS.getMessage().formatted("目标业务员"));
    }
    if (user.getStatus() == null || user.getStatus() != 1) {
      Long roleId = user.getRoleId();
      if (roleId != null && roleId == 0L) {
        throw new BaseException(ErrorCode.USER_IS_FROZEN);
      }
      if (roleId != null && roleId == 2L) {
        throw new BaseException(ErrorCode.USER_IS_QUIT);
      }
      throw new BaseException(ErrorCode.USER_STATUS_EXCEPTION);
    }
  }
}
