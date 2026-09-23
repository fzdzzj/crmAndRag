package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.CustomerContactRemarkVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.GroupResultVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactRemarkMapper;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;

/** 客户联系人检索与分组装配支持类：检索条件过滤与 VO 分页/分组装配，纯静态、无状态。 */
final class CustomerContactSearchSupport {

  private CustomerContactSearchSupport() {}

  /** 按公司名称模糊过滤联系人：先反查公司 ID，公司表无匹配时返回 false 表示结果必为空 */
  static boolean applyCompanyFilter(
      CustomerCompanyMapper customerCompanyMapper,
      LambdaQueryWrapper<CustomerContactEntity> queryWrapper,
      String companyName) {
    boolean matched = true;
    if (companyName != null && !companyName.isEmpty()) {
      // 先按公司名称模糊匹配出公司ID，再过滤联系人（公司表无匹配则返回空结果）
      List<Long> companyIds =
          customerCompanyMapper
              .selectList(
                  new LambdaQueryWrapper<CustomerCompanyEntity>()
                      .like(CustomerCompanyEntity::getCompanyName, companyName)
                      .eq(CustomerCompanyEntity::getIsDeleted, false))
              .stream()
              .map(CustomerCompanyEntity::getId)
              .collect(Collectors.toList());
      if (companyIds.isEmpty()) {
        matched = false;
      } else {
        queryWrapper.in(CustomerContactEntity::getCompanyId, companyIds);
      }
    }
    return matched;
  }

  /** 联系人其余检索条件：名称/职位/部门/电话/手机/邮箱模糊 + 性别/关系等级（0 视为未选）+ 删除标记缺省只查未删除 */
  static void applyContactSearchFilters(
      LambdaQueryWrapper<CustomerContactEntity> queryWrapper,
      CustomerContactDTO customerContactDTO) {
    likeIfNotBlank(queryWrapper, CustomerContactEntity::getName, customerContactDTO.getName());
    likeIfNotBlank(
        queryWrapper, CustomerContactEntity::getPosition, customerContactDTO.getPosition());
    likeIfNotBlank(queryWrapper, CustomerContactEntity::getDept, customerContactDTO.getDept());
    likeIfNotBlank(queryWrapper, CustomerContactEntity::getPhone, customerContactDTO.getPhone());
    likeIfNotBlank(queryWrapper, CustomerContactEntity::getMobile, customerContactDTO.getMobile());
    likeIfNotBlank(queryWrapper, CustomerContactEntity::getEmail, customerContactDTO.getEmail());
    // 性别/关系等级 0 视为未选
    if (customerContactDTO.getGender() != null && customerContactDTO.getGender() != 0) {
      queryWrapper.eq(CustomerContactEntity::getGender, customerContactDTO.getGender());
    }
    if (customerContactDTO.getRelationLevel() != null
        && customerContactDTO.getRelationLevel() != 0) {
      queryWrapper.eq(
          CustomerContactEntity::getRelationLevel, customerContactDTO.getRelationLevel());
    }
    if (customerContactDTO.getIsDeleted() != null) {
      queryWrapper.eq(CustomerContactEntity::getIsDeleted, customerContactDTO.getIsDeleted());
    } else {
      queryWrapper.eq(CustomerContactEntity::getIsDeleted, false);
    }
  }

  /** 非空白时按列模糊过滤（拆自 applyContactSearchFilters，行为等价） */
  private static void likeIfNotBlank(
      LambdaQueryWrapper<CustomerContactEntity> queryWrapper,
      SFunction<CustomerContactEntity, ?> column,
      String value) {
    if (value != null && !value.isEmpty()) {
      queryWrapper.like(column, value);
    }
  }

  /** 由实体分页构建 VO 分页：批量收集公司/创建人并统一查名称，逐条带出备注列表 */
  static Page<CustomerContactVO> buildContactSearchPage(
      DataConvertService dataConvertService,
      CustomerContactRemarkMapper customerContactRemarkMapper,
      Page<CustomerContactEntity> page,
      Integer pageNum,
      Integer pageSize) {
    List<CustomerContactEntity> customerContactEntityList = page.getRecords();

    // 批量收集ID后统一查询
    Set<Long> companyIds = new HashSet<>();
    Set<Long> creatorIds = new HashSet<>();
    for (CustomerContactEntity entity : customerContactEntityList) {
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
      if (entity.getCreatorId() != null) creatorIds.add(entity.getCreatorId());
    }
    Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
    Map<Long, String> creatorNameMap = dataConvertService.getUserNames(creatorIds);

    List<CustomerContactVO> customerContactVOList = new ArrayList<>();
    customerContactEntityList.forEach(
        entity -> {
          String companyName = companyNameMap.getOrDefault(entity.getCompanyId(), "");
          String creatorName = creatorNameMap.get(entity.getCreatorId());

          // 查询联系人备注
          List<CustomerContactRemarkEntity> entities1 =
              customerContactRemarkMapper.selectByContactId(entity.getId());
          List<CustomerContactRemarkVO> remarkVOList =
              CustomerContactRemarkVO.fromEntity(entities1);

          // 构建 VO
          CustomerContactVO vo =
              CustomerContactVO.fromEntity(entity, companyName, creatorName, remarkVOList);

          customerContactVOList.add(vo);
        });

    Page<CustomerContactVO> ans = new Page<>(pageNum, pageSize);
    BeanUtils.copyProperties(page, ans);
    ans.setRecords(customerContactVOList);

    return ans;
  }

  /** 公司下的联系人列表 VO 装配：批量查创建人姓名并逐条带出备注列表 */
  static List<CustomerContactVO> buildCompanyContacts(
      DataConvertService dataConvertService,
      CustomerContactRemarkMapper customerContactRemarkMapper,
      String companyName,
      List<CustomerContactEntity> customerContactEntityList) {
    // 批量查询创建人姓名
    Set<Long> creatorIds = new HashSet<>();
    for (CustomerContactEntity entity : customerContactEntityList) {
      if (entity.getCreatorId() != null) creatorIds.add(entity.getCreatorId());
    }
    Map<Long, String> creatorNameMap = dataConvertService.getUserNames(creatorIds);

    List<CustomerContactVO> built = new ArrayList<>();
    customerContactEntityList.forEach(
        entity -> {
          List<CustomerContactRemarkEntity> entities =
              customerContactRemarkMapper.selectByContactId(entity.getId());
          List<CustomerContactRemarkVO> remarkVOList = CustomerContactRemarkVO.fromEntity(entities);
          String creatorName = creatorNameMap.get(entity.getCreatorId());
          CustomerContactVO vo =
              CustomerContactVO.fromEntity(entity, companyName, creatorName, remarkVOList);
          built.add(vo);
        });
    return built;
  }

  /** 分组统计装配：按指定字段分组并构建带备注的联系人 VO（field 已由调用方校验） */
  static GroupResultVO buildGroupResult(
      DataConvertService dataConvertService,
      CustomerContactRemarkMapper customerContactRemarkMapper,
      String field,
      List<CustomerContactEntity> entities) {
    Map<Object, List<CustomerContactVO>> groupMap = new HashMap<>();

    // 批量收集ID后统一查询
    Set<Long> companyIds = new HashSet<>();
    Set<Long> creatorIds = new HashSet<>();
    for (CustomerContactEntity entity : entities) {
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
      if (entity.getCreatorId() != null) creatorIds.add(entity.getCreatorId());
    }
    Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
    Map<Long, String> creatorNameMap = dataConvertService.getUserNames(creatorIds);

    // 按不同字段分组
    for (CustomerContactEntity entity : entities) {
      // 获取分组键
      Object groupKey = getGroupKey(entity, field);
      if (groupKey == null) {
        continue;
      }

      String companyName = companyNameMap.getOrDefault(entity.getCompanyId(), "");
      String creatorName = creatorNameMap.get(entity.getCreatorId());

      // 查询联系人备注
      List<CustomerContactRemarkEntity> entities1 =
          customerContactRemarkMapper.selectByContactId(entity.getId());
      List<CustomerContactRemarkVO> remarkVOList = CustomerContactRemarkVO.fromEntity(entities1);

      // 构建 VO
      CustomerContactVO vo =
          CustomerContactVO.fromEntity(entity, companyName, creatorName, remarkVOList);
      groupMap.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(vo);
    }

    GroupResultVO resultVO = new GroupResultVO();
    resultVO.setGroupField(field);
    resultVO.setGroupData(groupMap);

    return resultVO;
  }

  /** 分组字段合法集合（由主类入口校验用） */
  static boolean isValidGroupField(String field) {
    return Arrays.asList("companyId", "gender", "creatorId", "position", "dept").contains(field);
  }

  /** 单条联系人详情的 VO 装配：公司/创建人名称 + 备注列表 + 协助相关人标记 */
  static CustomerContactVO buildDetailVo(
      CustomerContactEntity entity,
      DataConvertService dataConvertService,
      CustomerContactRemarkMapper customerContactRemarkMapper,
      boolean assistRelated,
      Long currentId) {
    String companyName =
        entity.getCompanyId() == null
            ? null
            : dataConvertService.getCompanyName(entity.getCompanyId());
    String creatorName =
        entity.getCreatorId() == null
            ? null
            : dataConvertService.getUserName(entity.getCreatorId());
    List<CustomerContactRemarkEntity> remarkEntities =
        customerContactRemarkMapper.selectByContactId(entity.getId());
    CustomerContactVO vo =
        CustomerContactVO.fromEntity(
            entity, companyName, creatorName, CustomerContactRemarkVO.fromEntity(remarkEntities));
    // 对象级授权已经确认当前用户与该联系人有关，详情按本人规则展示
    if (currentId != null && assistRelated) {
      vo.setRelatedUserIds(Set.of(currentId));
    }
    return vo;
  }

  /** 审批人下拉装配：按阶段推进审批权限反查角色与用户，并回填角色名 */
  static List<UserVO> buildAuditorOptions(
      com.slz.crm.server.mapper.UserMapper userMapper,
      com.slz.crm.server.mapper.RolePermissionsMapper rolePermissionsMapper,
      com.slz.crm.server.mapper.RoleMapper roleMapper,
      Long permissionId) {
    List<RolePermissionsEntity> rolePermissionsEntities =
        rolePermissionsMapper.selectList(
            new LambdaQueryWrapper<RolePermissionsEntity>()
                .eq(RolePermissionsEntity::getPermissionsId, permissionId));
    List<Long> roleIds =
        rolePermissionsEntities.stream().map(RolePermissionsEntity::getRoleId).toList();

    Map<Long, String> roleNameMap = new HashMap<>();

    if (!roleIds.isEmpty()) {
      roleMapper
          .selectList(new LambdaQueryWrapper<RoleEntity>().in(RoleEntity::getId, roleIds))
          .forEach(
              role -> {
                roleNameMap.put(role.getId(), role.getRoleName());
              });
    }

    LambdaQueryWrapper<UserEntity> in = new LambdaQueryWrapper<UserEntity>();

    if (!roleIds.isEmpty()) {
      in.in(UserEntity::getRoleId, roleIds);
    } else {
      in.eq(UserEntity::getRoleId, 0);
    }

    List<UserEntity> userEntities = userMapper.selectList(in);
    List<UserVO> ans = new ArrayList<>();

    userEntities.forEach(
        userEntity -> {
          UserVO vo = new UserVO();
          BeanUtils.copyProperties(userEntity, vo);
          vo.setRoleName(roleNameMap.get(vo.getRoleId()));
          ans.add(vo);
        });

    return ans;
  }

  private static Object getGroupKey(CustomerContactEntity entity, String field) {
    return switch (field) {
      case "companyId" -> entity.getCompanyId();
      case "gender" -> entity.getGender();
      case "creatorId" -> entity.getCreatorId();
      case "position" -> entity.getPosition();
      case "dept" -> entity.getDept();
      default -> null;
    };
  }
}
