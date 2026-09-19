package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.CompanyGroupDTO;
import com.slz.crm.pojo.entity.CompanyDeptEntity;
import com.slz.crm.pojo.entity.CompanyGroupEntity;
import com.slz.crm.server.mapper.CompanyDeptMapper;
import com.slz.crm.server.mapper.CompanyGroupMapper;
import com.slz.crm.server.service.CompanyGroupService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 集团主数据服务实现
 *
 * @author CRM Team
 */
@Service
public class CompanyGroupServiceImpl extends ServiceImpl<CompanyGroupMapper, CompanyGroupEntity>
    implements CompanyGroupService {

  @Autowired private CompanyDeptMapper companyDeptMapper;

  @Override
  public List<CompanyGroupEntity> list(String keyword, Integer status) {
    LambdaQueryWrapper<CompanyGroupEntity> wrapper = new LambdaQueryWrapper<>();
    if (StringUtils.hasText(keyword)) {
      wrapper.like(CompanyGroupEntity::getGroupName, keyword.trim());
    }
    if (status != null) {
      wrapper.eq(CompanyGroupEntity::getStatus, status);
    }
    wrapper.orderByAsc(CompanyGroupEntity::getGroupName);
    return list(wrapper);
  }

  @Override
  public CompanyGroupEntity add(CompanyGroupDTO dto) {
    if (dto == null || !StringUtils.hasText(dto.getGroupName())) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "集团名称不能为空");
    }
    String groupName = dto.getGroupName().trim();
    if (groupName.length() > 50) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "集团名称长度不能超过 50 个字符");
    }
    Long count = lambdaQuery().eq(CompanyGroupEntity::getGroupName, groupName).count();
    if (count != null && count > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "集团名称已存在，请勿重复添加");
    }
    CompanyGroupEntity entity = new CompanyGroupEntity();
    entity.setGroupName(groupName);
    entity.setStatus(1);
    save(entity);
    return entity;
  }

  @Override
  public CompanyGroupEntity update(CompanyGroupDTO dto) {
    if (dto == null || dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "集团ID不能为空");
    }
    CompanyGroupEntity existing = getById(dto.getId());
    if (existing == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "集团不存在");
    }
    if (!StringUtils.hasText(dto.getGroupName())) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "集团名称不能为空");
    }
    String groupName = dto.getGroupName().trim();
    if (groupName.length() > 50) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "集团名称长度不能超过 50 个字符");
    }
    Long count =
        lambdaQuery()
            .eq(CompanyGroupEntity::getGroupName, groupName)
            .ne(CompanyGroupEntity::getId, dto.getId())
            .count();
    if (count != null && count > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "集团名称已存在，请勿重复添加");
    }
    existing.setGroupName(groupName);
    existing.setUpdateTime(LocalDateTime.now());
    updateById(existing);
    return existing;
  }

  @Override
  public void updateStatus(Long id, Integer status) {
    CompanyGroupEntity existing = getById(id);
    if (existing == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "集团不存在");
    }
    if (status == null || (status != 0 && status != 1)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "状态参数不合法（0-停用，1-启用）");
    }
    existing.setStatus(status);
    existing.setUpdateTime(LocalDateTime.now());
    updateById(existing);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteById(Long id) {
    CompanyGroupEntity existing = getById(id);
    if (existing == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "集团不存在");
    }
    Long deptCount =
        companyDeptMapper.selectCount(
            new LambdaQueryWrapper<CompanyDeptEntity>().eq(CompanyDeptEntity::getGroupId, id));
    if (deptCount != null && deptCount > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该集团下存在部门，请先删除该集团下的部门后再删除集团");
    }
    removeById(id);
  }
}
