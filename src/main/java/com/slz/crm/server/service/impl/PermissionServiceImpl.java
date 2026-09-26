package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.RoleANDPermissionDTO;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AuditorVO;
import com.slz.crm.pojo.vo.PermissionGroupedVO;
import com.slz.crm.pojo.vo.PermissionVO;
import com.slz.crm.pojo.vo.SubPermissionVO;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.PermissionsMapper;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.PermissionService;
import jakarta.annotation.Resource;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PermissionServiceImpl extends ServiceImpl<PermissionsMapper, PermissionsEntity>
    implements PermissionService {
  @Resource private PermissionsMapper permissionsMapper;
  @Resource private RoleMapper roleMapper;
  @Autowired private UserMapper userMapper;

  @Override
  public boolean hasPermission(Long userId, PermissionOperates targetPerm) {
    // update-project-file-list-auth-hotpath 安全等价修复：判定必须使用「判定时刻」用户当前角色的权限链，
    // 不能复用请求早期状态闸读到的旧 roleId，否则并发改派角色后仍会按旧角色放行。
    // 联查 sys_user 与角色权限，一次查询即取得当前角色权限链；空权限链仍抛「该用户没有权限」，口径与旧实现一致。
    return hasPermission(targetPerm, permissionsMapper.getPermissionListByUserId(userId));
  }

  @Override
  public boolean hasPermission(
      PermissionOperates targetPerm, List<PermissionsEntity> permissionList) {
    if (permissionList.isEmpty()) {
      throw new BaseException("该用户没有权限");
    }
    List<Long> permissionIds = permissionList.stream().map(PermissionsEntity::getId).toList();

    Long permissionId = targetPerm.getId();

    return permissionIds.contains(permissionId);
  }

  @Override
  public List<PermissionsEntity> getPermissionList(Long roleId) {
    List<PermissionsEntity> permissionList = permissionsMapper.getPermissionList(roleId);

    return permissionList;
  }

  @Override
  public List<PermissionVO> getMyPermission() {
    Long userId = BaseUnit.getCurrentId();

    Long id =
        userMapper
            .selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getId, userId))
            .getRoleId();
    List<PermissionsEntity> permissionList = getPermissionList(id);

    List<PermissionVO> permissionVOList = new ArrayList<>();

    permissionList.forEach(
        permission -> {
          permissionVOList.add(new PermissionVO(permission));
        });

    return permissionVOList;
  }

  @Override
  public List<String> getKeyList() {

    List<String> keyList = new ArrayList<>();

    keyList.add("客户管理模块权限");
    keyList.add("销售管理模块权限");
    keyList.add("财务管理模块权限");
    keyList.add("联络任务模块权限");
    keyList.add("统计报表模块权限");
    keyList.add("权限管理模块权限");
    keyList.add("隐私信息查看权限");

    return keyList;
  }

  @Override
  public List<PermissionVO> getPermissionList(PermissionsEntity permissionsEntity) {
    List<PermissionsEntity> permissionList = new ArrayList<>();

    LambdaQueryWrapper<PermissionsEntity> queryWrapper = new LambdaQueryWrapper<>();
    if (permissionsEntity.getId() != null) {
      queryWrapper.like(PermissionsEntity::getId, permissionsEntity.getId());
    }
    if (permissionsEntity.getPermissionsName() != null) {
      queryWrapper.like(
          PermissionsEntity::getPermissionsName, permissionsEntity.getPermissionsName());
    }
    if (permissionsEntity.getPermissionsDesc() != null) {
      queryWrapper.like(
          PermissionsEntity::getPermissionsDesc, permissionsEntity.getPermissionsDesc());
    }
    permissionList = permissionsMapper.selectList(queryWrapper);

    return permissionList.stream().map(PermissionVO::new).toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean addOrDeletePermissionsToRole(RoleANDPermissionDTO roleANDPermissionDTOS) {

    if (roleANDPermissionDTOS.getRoleId() == null
        || CollectionUtils.isEmpty(roleANDPermissionDTOS.getPermissionIds())
        || roleANDPermissionDTOS.getIsAdd() == null) {
      throw new BaseException(MessageConstant.ROLE_PERMISSION_IS_NULL);
    }

    RoleEntity roleEntity = roleMapper.selectById(roleANDPermissionDTOS.getRoleId());
    if (roleEntity == null) {
      throw new BaseException(MessageConstant.ROLE_IS_NOT_EXIST);
    }

    List<Integer> existPermissionIds =
        permissionsMapper.selectExistPermissionIds(roleANDPermissionDTOS.getPermissionIds());
    if (CollectionUtils.isEmpty(existPermissionIds)) {
      throw new BaseException(MessageConstant.PERMISSION_IS_NOT_EXIST);
    }

    List<Integer> notExistIds =
        roleANDPermissionDTOS.getPermissionIds().stream()
            .filter(id -> !existPermissionIds.contains(id))
            .toList();
    if (!CollectionUtils.isEmpty(notExistIds)) {
      throw new BaseException("权限ID " + notExistIds + " 不存在");
    }

    List<Integer> hadPermissionIds =
        permissionsMapper.selectPermissionIdsByRoleId(roleANDPermissionDTOS.getRoleId());
    if (roleANDPermissionDTOS.getIsAdd()) {
      // 新增权限
      addPermissionsToRole(roleANDPermissionDTOS, hadPermissionIds);
    } else {
      // 删除权限
      deletePermissionsFromRole(roleANDPermissionDTOS, hadPermissionIds);
    }

    return true;
  }

  /**
   * 新增角色权限关联：校验重复关联后批量插入（拆自 addOrDeletePermissionsToRole，行为等价）。
   *
   * @param dto 角色权限关联请求
   * @param hadPermissionIds 角色已关联的权限 ID
   */
  private void addPermissionsToRole(RoleANDPermissionDTO dto, List<Integer> hadPermissionIds) {
    List<Integer> repeatIds =
        dto.getPermissionIds().stream().filter(hadPermissionIds::contains).toList();
    if (!CollectionUtils.isEmpty(repeatIds)) {
      throw new BaseException("角色已关联权限ID " + repeatIds + "，无需重复新增");
    }

    Long currentId = BaseUnit.getCurrentId();
    permissionsMapper.batchAddPermissionToRole(dto.getRoleId(), dto.getPermissionIds(), currentId);
  }

  /**
   * 删除角色权限关联：校验未关联项后批量删除（拆自 addOrDeletePermissionsToRole，行为等价）。
   *
   * @param dto 角色权限关联请求
   * @param hadPermissionIds 角色已关联的权限 ID
   */
  private void deletePermissionsFromRole(RoleANDPermissionDTO dto, List<Integer> hadPermissionIds) {
    List<Integer> notHadIds =
        dto.getPermissionIds().stream().filter(id -> !hadPermissionIds.contains(id)).toList();
    if (!CollectionUtils.isEmpty(notHadIds)) {
      throw new BaseException("角色未关联权限ID " + notHadIds + "，无法删除");
    }

    permissionsMapper.batchDeletePermissionToRole(dto.getRoleId(), dto.getPermissionIds());
  }

  @Override
  public List<PermissionVO> getByRole(Long roleId) {

    List<PermissionsEntity> permissionList = permissionsMapper.getPermissionList(roleId);
    List<PermissionVO> permissionVOList = new ArrayList<>();
    permissionList.forEach(
        permission -> {
          permissionVOList.add(new PermissionVO(permission));
        });

    return permissionVOList;
  }

  @Override
  public Map<String, List<PermissionVO>> getAllPermissionsGroupedByModule() {
    // 查询所有权限
    List<PermissionsEntity> allPermissions = permissionsMapper.selectList(null);

    // 按模块分组
    Map<String, List<PermissionVO>> permissionMap = new LinkedHashMap<>();

    // 初始化所有模块
    permissionMap.put("客户管理模块权限", new ArrayList<>());
    permissionMap.put("销售管理模块权限", new ArrayList<>());
    permissionMap.put("财务管理模块权限", new ArrayList<>());
    permissionMap.put("联络任务模块权限", new ArrayList<>());
    permissionMap.put("统计报表模块权限", new ArrayList<>());
    permissionMap.put("权限管理模块权限", new ArrayList<>());
    permissionMap.put("隐私信息查看权限", new ArrayList<>());

    // 遍历所有权限并按模块分组
    for (PermissionsEntity permission : allPermissions) {
      String moduleName = PermissionModuleResolver.resolve(permission.getPermissionsName());
      permissionMap.get(moduleName).add(new PermissionVO(permission));
    }

    return permissionMap;
  }

  @Override
  public List<AuditorVO> getAuditorList() {
    // 直接查询拥有审批权限的用户列表
    List<UserEntity> auditors = permissionsMapper.selectAuditorList();

    // 转换为AuditorVO列表
    return auditors.stream().map(user -> new AuditorVO(user.getId(), user.getRealName())).toList();
  }

  @Override
  public PermissionGroupedVO getMyPermissionGrouped() {
    Long userId = BaseUnit.getCurrentId();
    Long roleId =
        userMapper
            .selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getId, userId))
            .getRoleId();
    List<PermissionsEntity> permissionList = getPermissionList(roleId);
    return groupPermissions(permissionList);
  }

  @Override
  public PermissionGroupedVO getByRoleGrouped(Long roleId) {
    List<PermissionsEntity> permissionList = permissionsMapper.getPermissionList(roleId);
    return groupPermissions(permissionList);
  }

  @Override
  public Map<String, PermissionGroupedVO> getAllPermissionsGroupedByModuleGrouped() {
    List<PermissionsEntity> allPermissions = permissionsMapper.selectList(null);
    Map<String, List<PermissionsEntity>> moduleMap = new LinkedHashMap<>();

    for (String moduleName : getKeyList()) {
      moduleMap.put(moduleName, new ArrayList<>());
    }

    for (PermissionsEntity permission : allPermissions) {
      String moduleName = PermissionModuleResolver.resolve(permission.getPermissionsName());
      List<PermissionsEntity> permissions = moduleMap.get(moduleName);
      if (permissions != null) {
        permissions.add(permission);
      }
    }

    Map<String, PermissionGroupedVO> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<PermissionsEntity>> entry : moduleMap.entrySet()) {
      result.put(entry.getKey(), groupPermissions(entry.getValue()));
    }

    return result;
  }

  @Override
  public PermissionGroupedVO groupPermissions(List<PermissionsEntity> permissionList) {
    PermissionGroupedVO groupedVO = new PermissionGroupedVO();
    List<PermissionVO> mainPermissions = new ArrayList<>();
    List<SubPermissionVO> subPermissions = new ArrayList<>();

    for (PermissionsEntity entity : permissionList) {
      PermissionOperates operates = PermissionOperates.fromId(entity.getId());
      if (operates != null && operates.isDataScopePermission()) {
        // 子权限 - 携带父权限ID
        subPermissions.add(SubPermissionVO.fromEntity(entity));
      } else {
        // 主权限
        mainPermissions.add(new PermissionVO(entity));
      }
    }

    groupedVO.setMainPermissions(mainPermissions);
    groupedVO.setSubPermissions(subPermissions);
    return groupedVO;
  }
}
