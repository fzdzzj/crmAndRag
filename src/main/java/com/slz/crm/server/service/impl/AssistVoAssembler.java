package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataConvertService;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 协助实体 → VO 装配（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属：按业务记录批量查询、待我协助分页、我发起的协助分页三处的同一段映射（拆分前是逐字重复的两份代码块）。 只读，不写库、不带事务注解、不新开事务。批量查用户与部门，避免 N+1。
 */
class AssistVoAssembler {

  private final UserMapper userMapper;
  private final DataConvertService dataConvertService;

  AssistVoAssembler(UserMapper userMapper, DataConvertService dataConvertService) {
    this.userMapper = userMapper;
    this.dataConvertService = dataConvertService;
  }

  /** 批量装配协助 VO（含申请人/协助人姓名与协助人部门名）；空入参返回空列表。 */
  List<AssistVO> toVoList(List<AssistRequestEntity> entities) {
    List<AssistVO> voList = Collections.emptyList();
    if (!entities.isEmpty()) {
      Map<Long, UserEntity> userMap = loadUserMap(entities);
      Map<Long, String> deptNameMap = loadDeptNameMap(userMap);
      voList =
          entities.stream()
              .map(entity -> toVo(entity, userMap, deptNameMap))
              .collect(Collectors.toList());
    }
    return voList;
  }

  /** 批量收集申请人/协助人并查姓名。 */
  private Map<Long, UserEntity> loadUserMap(List<AssistRequestEntity> entities) {
    Set<Long> userIds = new HashSet<>();
    entities.forEach(
        e -> {
          userIds.add(e.getApplicantId());
          userIds.add(e.getAssistUserId());
        });
    return userIds.isEmpty()
        ? Collections.emptyMap()
        : userMapper.selectBatchIds(userIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
  }

  /** 按用户部门集合批量查部门名。 */
  private Map<Long, String> loadDeptNameMap(Map<Long, UserEntity> userMap) {
    Set<Long> deptIds =
        userMap.values().stream()
            .map(UserEntity::getDeptId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return deptIds.isEmpty() ? Collections.emptyMap() : dataConvertService.getDeptNames(deptIds);
  }

  private AssistVO toVo(
      AssistRequestEntity entity, Map<Long, UserEntity> userMap, Map<Long, String> deptNameMap) {
    AssistVO vo = new AssistVO();
    vo.setId(entity.getId());
    vo.setModelName(entity.getModelName());
    vo.setRecordId(entity.getRecordId());
    vo.setApplicantId(entity.getApplicantId());
    vo.setApplyPurpose(entity.getApplyPurpose());
    vo.setApplyRequirement(entity.getApplyRequirement());
    vo.setAssistUserId(entity.getAssistUserId());
    vo.setAssistStatus(entity.getAssistStatus());
    vo.setAssistContent(entity.getAssistContent());
    vo.setRejectReason(entity.getRejectReason());
    vo.setParentId(entity.getParentId());
    vo.setAssistTime(entity.getAssistTime());
    vo.setCreateTime(entity.getCreateTime());

    UserEntity applicant = userMap.get(entity.getApplicantId());
    UserEntity assistUser = userMap.get(entity.getAssistUserId());
    vo.setApplicantName(applicant != null ? applicant.getRealName() : null);
    vo.setAssistUserName(assistUser != null ? assistUser.getRealName() : null);
    vo.setAssistUserDeptName(
        assistUser != null && assistUser.getDeptId() != null
            ? deptNameMap.get(assistUser.getDeptId())
            : null);
    return vo;
  }
}
