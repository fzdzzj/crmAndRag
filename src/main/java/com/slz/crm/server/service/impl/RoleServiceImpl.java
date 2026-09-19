package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.vo.RoleVO;
import com.slz.crm.server.mapper.RoleMapper;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoleServiceImpl implements com.slz.crm.server.service.RoleService {

  @Autowired private RoleMapper roleMapper;

  @Override
  public Page<RoleVO> list(Integer pageNum, Integer pageSize) {

    // 分页查询角色列表
    Page<RoleEntity> page =
        roleMapper.selectPage(
            new Page<>(pageNum, pageSize),
            new LambdaQueryWrapper<RoleEntity>().eq(RoleEntity::getIsDeleted, 0));

    // 转换为VO列表
    List<RoleVO> roleVOList = page.getRecords().stream().map(RoleVO::fromEntity).toList();

    // 设置分页信息
    Page<RoleVO> roleVOPage = new Page<>();
    BeanUtils.copyProperties(page, roleVOPage);
    roleVOPage.setRecords(roleVOList);

    return roleVOPage;
  }

  @Override
  public RoleVO getMyRole() {
    RoleAO role = BaseUnit.getCurrentRole();
    if (role == null) {
      throw new BaseException(ErrorCode.USER_NOT_LOGIN);
    }

    if (role.getRoleId() == null) {
      throw new BaseException(ErrorCode.ROLE_NOT_EXISTS);
    }

    RoleEntity roleEntity = roleMapper.selectById(role.getRoleId());
    if (roleEntity == null) {
      throw new BaseException(ErrorCode.ROLE_NOT_EXISTS);
    }

    return RoleVO.fromEntity(roleEntity);
  }

  @Override
  public boolean save(RoleEntity roleEntity) {
    return roleMapper.insert(roleEntity) > 0;
  }

  @Override
  public void delete(Long roleId, Boolean isDelete) {
    // 禁止删除管理员角色（ID=1）
    if (roleId == 1L && Boolean.TRUE.equals(isDelete)) {
      throw new BaseException("禁止删除管理员角色");
    }

    RoleEntity roleEntity = new RoleEntity();
    roleEntity.setId(roleId);
    roleEntity.setIsDeleted(isDelete);
    roleMapper.updateById(roleEntity);
  }
}
