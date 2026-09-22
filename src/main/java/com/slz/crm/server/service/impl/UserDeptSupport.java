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
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("部门"));
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
}
