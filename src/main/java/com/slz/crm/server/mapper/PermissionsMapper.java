package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.entity.UserEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PermissionsMapper extends BaseMapper<PermissionsEntity> {
    List<PermissionsEntity> getPermissionList(Long roleId);

    void insertANDID(PermissionsEntity perm);


    void batchAddPermissionToRole(
            @Param("roleId") Integer roleId,
            @Param("permissionIds") List<Integer> permissionIds,
            @Param("currentId") Long currentId);


    void batchDeletePermissionToRole(
            @Param("roleId") Integer roleId,
            @Param("permissionIds") List<Integer> permissionIds);

    List<Integer> selectPermissionIdsByRoleId(Integer roleId);

    List<Integer> selectExistPermissionIds(@Param("permissionIds") List<Integer> permissionIds);

    List<UserEntity> selectAuditorList();
}
