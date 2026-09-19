package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.CompanyDeptDTO;
import com.slz.crm.pojo.entity.CompanyDeptEntity;
import com.slz.crm.pojo.entity.CompanyGroupEntity;
import com.slz.crm.server.mapper.CompanyDeptMapper;
import com.slz.crm.server.mapper.CompanyGroupMapper;
import com.slz.crm.server.service.CompanyDeptService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 部门主数据服务实现
 *
 * @author CRM Team
 */
@Service
public class CompanyDeptServiceImpl extends ServiceImpl<CompanyDeptMapper, CompanyDeptEntity>
    implements CompanyDeptService {

  @Autowired private CompanyGroupMapper companyGroupMapper;

  @Override
  public List<CompanyDeptEntity> list(Long groupId, String keyword, Integer status) {
    LambdaQueryWrapper<CompanyDeptEntity> wrapper = new LambdaQueryWrapper<>();
    if (groupId != null) {
      wrapper.eq(CompanyDeptEntity::getGroupId, groupId);
    }
    if (StringUtils.hasText(keyword)) {
      wrapper.like(CompanyDeptEntity::getDeptName, keyword.trim());
    }
    if (status != null) {
      wrapper.eq(CompanyDeptEntity::getStatus, status);
    }
    wrapper.orderByAsc(CompanyDeptEntity::getDeptName);
    return list(wrapper);
  }

  @Override
  public CompanyDeptEntity add(CompanyDeptDTO dto) {
    if (dto == null || dto.getGroupId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "请先选择归属集团");
    }
    if (!StringUtils.hasText(dto.getDeptName())) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "部门名称不能为空");
    }
    String deptName = dto.getDeptName().trim();
    if (deptName.length() > 50) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门名称长度不能超过 50 个字符");
    }
    CompanyGroupEntity group = companyGroupMapper.selectById(dto.getGroupId());
    if (group == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "归属集团不存在，请重新选择");
    }
    Long count =
        lambdaQuery()
            .eq(CompanyDeptEntity::getGroupId, dto.getGroupId())
            .eq(CompanyDeptEntity::getDeptName, deptName)
            .count();
    if (count != null && count > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该集团下已存在同名部门，请勿重复添加");
    }
    CompanyDeptEntity entity = new CompanyDeptEntity();
    entity.setGroupId(dto.getGroupId());
    entity.setDeptName(deptName);
    entity.setStatus(1);
    save(entity);
    return entity;
  }

  @Override
  public CompanyDeptEntity update(CompanyDeptDTO dto) {
    if (dto == null || dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "部门ID不能为空");
    }
    CompanyDeptEntity existing = getById(dto.getId());
    if (existing == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门不存在");
    }
    if (!StringUtils.hasText(dto.getDeptName())) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "部门名称不能为空");
    }
    String deptName = dto.getDeptName().trim();
    if (deptName.length() > 50) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门名称长度不能超过 50 个字符");
    }
    Long groupId = dto.getGroupId() != null ? dto.getGroupId() : existing.getGroupId();
    if (companyGroupMapper.selectById(groupId) == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "归属集团不存在，请重新选择");
    }
    Long count =
        lambdaQuery()
            .eq(CompanyDeptEntity::getGroupId, groupId)
            .eq(CompanyDeptEntity::getDeptName, deptName)
            .ne(CompanyDeptEntity::getId, dto.getId())
            .count();
    if (count != null && count > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该集团下已存在同名部门，请勿重复添加");
    }
    existing.setGroupId(groupId);
    existing.setDeptName(deptName);
    existing.setUpdateTime(LocalDateTime.now());
    updateById(existing);
    return existing;
  }

  @Override
  public void updateStatus(Long id, Integer status) {
    CompanyDeptEntity existing = getById(id);
    if (existing == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门不存在");
    }
    if (status == null || (status != 0 && status != 1)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "状态参数不合法（0-停用，1-启用）");
    }
    existing.setStatus(status);
    existing.setUpdateTime(LocalDateTime.now());
    updateById(existing);
  }

  @Override
  public void deleteById(Long id) {
    CompanyDeptEntity existing = getById(id);
    if (existing == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门不存在");
    }
    removeById(id);
  }
}
