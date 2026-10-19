package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.service.DataConvertService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 用户的部门校验与 VO 部门名填充支持类（tighten-pmd-residual-325 任务 6.3 拆自 UserServiceImpl，行为等价）。纯静态、无状态，mapper
 * 与数据转换服务经参数传入。
 */
final class UserDeptSupport {

  private UserDeptSupport() {}

  /**
   * 校验部门存在且启用
   *
   * @param deptId 部门ID（可空）
   * @param sysDeptMapper 部门 mapper
   */
  static void validateDeptId(Long deptId, SysDeptMapper sysDeptMapper) {
    if (deptId == null) {
      return;
    }
    SysDeptEntity dept = sysDeptMapper.selectById(deptId);
    if (dept == null) {
      throw new BaseException(
          ErrorCode.ID_NOT_EXISTS, ErrorCode.ID_NOT_EXISTS.getMessage().formatted("部门"));
    }
    if (!Objects.equals(dept.getStatus(), 1)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门已停用，请选择启用中的部门");
    }
  }

  /**
   * 批量填充用户VO的部门名称
   *
   * @param userVOS 用户VO列表
   * @param dataConvertService 数据转换服务
   */
  static void fillDeptNames(List<UserVO> userVOS, DataConvertService dataConvertService) {
    if (userVOS != null && !userVOS.isEmpty()) {
      Set<Long> deptIds = new HashSet<>();
      for (UserVO vo : userVOS) {
        if (vo.getDeptId() != null) {
          deptIds.add(vo.getDeptId());
        }
      }
      if (!deptIds.isEmpty()) {
        Map<Long, String> deptNameMap = dataConvertService.getDeptNames(deptIds);
        for (UserVO vo : userVOS) {
          if (vo.getDeptId() != null) {
            vo.setDeptName(deptNameMap.get(vo.getDeptId()));
          }
        }
      }
    }
  }

  /**
   * 将在职系统用户实体列表转换为下拉选择项列表（含部门名转换）。
   *
   * @param users 系统用户实体列表
   * @param dataConvertService 数据转换服务
   * @return 选择项列表
   */
  static List<com.slz.crm.pojo.vo.UserOptionVO> toActiveOptions(
      List<com.slz.crm.pojo.entity.UserEntity> users, DataConvertService dataConvertService) {
    final List<com.slz.crm.pojo.vo.UserOptionVO> result;
    if (users == null || users.isEmpty()) {
      result = java.util.Collections.emptyList();
    } else {
      Set<Long> deptIds = new HashSet<>();
      for (com.slz.crm.pojo.entity.UserEntity user : users) {
        if (user.getDeptId() != null) {
          deptIds.add(user.getDeptId());
        }
      }
      Map<Long, String> deptNameMap =
          deptIds.isEmpty()
              ? java.util.Collections.emptyMap()
              : dataConvertService.getDeptNames(deptIds);
      result = new java.util.ArrayList<>(users.size());
      for (com.slz.crm.pojo.entity.UserEntity user : users) {
        com.slz.crm.pojo.vo.UserOptionVO vo = new com.slz.crm.pojo.vo.UserOptionVO();
        vo.setId(user.getId());
        vo.setRealName(user.getRealName());
        vo.setDeptId(user.getDeptId());
        if (user.getDeptId() != null) {
          vo.setDeptName(deptNameMap.get(user.getDeptId()));
        }
        result.add(vo);
      }
    }
    return result;
  }
}
