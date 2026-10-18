package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.entity.UserEntity;
import java.time.LocalDateTime;

/**
 * 自服务信息更新的白名单构造支持类（harden-user-self-update 任务 1.1，拆自 UserServiceImpl 控制 NCSS）。
 *
 * <p>纯静态、无状态。白名单即安全边界：任何新字段进入 SET 子句前必须先过自服务准入评审， 严禁把本方法改为反射或 BeanUtils 全量拷贝。
 */
final class UserSelfUpdateSupport {

  private UserSelfUpdateSupport() {}

  /**
   * 构造自服务白名单更新 wrapper：仅 realName/phone/email 允许进入 SET 子句， 目标行强制绑定 currentUserId（不信任请求体身份字段），并强制刷新
   * updateTime。
   *
   * @param userDTO 用户数据传输对象（须已通过特权字段 fail-closed 拦截）
   * @param currentUserId 当前登录用户ID
   * @return 更新 wrapper
   */
  static LambdaUpdateWrapper<UserEntity> buildWhitelistWrapper(
      UserDTO userDTO, Long currentUserId) {
    LambdaUpdateWrapper<UserEntity> wrapper = new LambdaUpdateWrapper<>();
    wrapper.eq(UserEntity::getId, currentUserId);
    if (userDTO.getRealName() != null) {
      wrapper.set(UserEntity::getRealName, userDTO.getRealName());
    }
    if (userDTO.getPhone() != null) {
      wrapper.set(UserEntity::getPhone, userDTO.getPhone());
    }
    if (userDTO.getEmail() != null) {
      wrapper.set(UserEntity::getEmail, userDTO.getEmail());
    }
    wrapper.set(UserEntity::getUpdateTime, LocalDateTime.now());
    return wrapper;
  }
}
