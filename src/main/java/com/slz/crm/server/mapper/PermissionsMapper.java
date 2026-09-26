package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.entity.UserEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PermissionsMapper extends BaseMapper<PermissionsEntity> {
  List<PermissionsEntity> getPermissionList(Long roleId);

  /**
   * update-project-file-list-auth-hotpath 安全等价修复：按 userId 联查「当前用户 → 当前角色 → 权限链」，
   * 使权限判定始终基于判定时刻数据库中的当前角色， 不复用请求早期读到的旧 roleId。
   */
  List<PermissionsEntity> getPermissionListByUserId(Long userId);

  void insertANDID(PermissionsEntity perm);

  void batchAddPermissionToRole(
      @Param("roleId") Integer roleId,
      @Param("permissionIds") List<Integer> permissionIds,
      @Param("currentId") Long currentId);

  void batchDeletePermissionToRole(
      @Param("roleId") Integer roleId, @Param("permissionIds") List<Integer> permissionIds);

  List<Integer> selectPermissionIdsByRoleId(Integer roleId);

  List<Integer> selectExistPermissionIds(@Param("permissionIds") List<Integer> permissionIds);

  List<UserEntity> selectAuditorList();
}
