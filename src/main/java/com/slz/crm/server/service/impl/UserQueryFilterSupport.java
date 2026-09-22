package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.dto.GetUserDTO;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.UserMapper;
import java.util.List;

/**
 * 用户分页查询条件组装支持类（tighten-pmd-residual-325 任务 6.3 自 UserServiceImpl 拆出，行为等价）。 聚合基础字段过滤与创建人/角色名两路解析过滤。
 */
final class UserQueryFilterSupport {
  private UserQueryFilterSupport() {}

  /**
   * 组装用户分页查询条件（拆自 findPage，行为等价）。
   *
   * @param wp 查询构造器
   * @param dto 查询条件
   * @param userMapper 用户 Mapper（创建人姓名解析用）
   * @param roleMapper 角色 Mapper（角色名解析用）
   */
  static void applyUserPageFilters(
      LambdaQueryWrapper<UserEntity> wp,
      GetUserDTO dto,
      UserMapper userMapper,
      RoleMapper roleMapper) {
    if (dto.getRoleId() != null && !dto.getRoleId().isEmpty()) {
      wp.in(UserEntity::getRoleId, dto.getRoleId());
    }

    if (dto.getId() != null && !dto.getId().isEmpty()) {
      wp.in(UserEntity::getId, dto.getId());
    }

    if (dto.getPhone() != null) {
      wp.like(UserEntity::getPhone, dto.getPhone());
    }

    if (dto.getEmail() != null) {
      wp.like(UserEntity::getEmail, dto.getEmail());
    }

    if (dto.getRealName() != null) {
      wp.like(UserEntity::getRealName, dto.getRealName());
    }

    if (dto.getStatus() != null && !dto.getStatus().isEmpty()) {
      wp.in(UserEntity::getStatus, dto.getStatus());
    } else {
      wp.in(UserEntity::getStatus, 0, 1, 2);
    }

    if (dto.getCreatorId() != null) {
      wp.eq(UserEntity::getCreatorId, dto.getCreatorId());
    }

    applyCreatorNameFilter(wp, dto.getCreatorName(), userMapper);
    applyRoleNameFilter(wp, dto.getRoleName(), roleMapper);
  }

  /**
   * 按创建人姓名解析用户 ID 并过滤：未命中置创建人 ID 为 0（拆自 findPage，行为等价）。
   *
   * @param wp 查询构造器
   * @param creatorName 创建人姓名
   * @param userMapper 用户 Mapper
   */
  private static void applyCreatorNameFilter(
      LambdaQueryWrapper<UserEntity> wp, String creatorName, UserMapper userMapper) {
    if (creatorName == null) {
      return;
    }
    List<Long> list =
        userMapper
            .selectList(
                new LambdaQueryWrapper<UserEntity>().like(UserEntity::getRealName, creatorName))
            .stream()
            .map(UserEntity::getId)
            .toList();

    if (!list.isEmpty()) {
      wp.in(UserEntity::getCreatorId, list);
    } else {
      wp.eq(UserEntity::getCreatorId, 0);
    }
  }

  /**
   * 按角色名解析角色 ID 并过滤：未命中置创建人 ID 为 0（拆自 findPage，行为等价）。
   *
   * @param wp 查询构造器
   * @param roleName 角色名
   * @param roleMapper 角色 Mapper
   */
  private static void applyRoleNameFilter(
      LambdaQueryWrapper<UserEntity> wp, String roleName, RoleMapper roleMapper) {
    if (roleName == null) {
      return;
    }
    List<Long> list =
        roleMapper
            .selectList(
                new LambdaQueryWrapper<RoleEntity>().like(RoleEntity::getRoleName, roleName))
            .stream()
            .map(RoleEntity::getId)
            .toList();

    if (!list.isEmpty()) {
      wp.in(UserEntity::getCreatorId, list);
    } else {
      wp.eq(UserEntity::getCreatorId, 0);
    }
  }
}
