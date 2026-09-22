package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.dto.CustomerMergeDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerMergeLogEntity;
import com.slz.crm.pojo.excel.CustomerCompanyExcel;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.CustomerMergeLogMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.DataConvertService;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.BeanUtils;

/**
 * 客户公司的校验与 VO 装配支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * CustomerCompanyServiceImpl，行为等价）。纯静态、无状态，mapper 与数据转换服务经参数传入。
 */
final class CustomerCompanySupport {

  private CustomerCompanySupport() {}

  /** 批量收集创建人/负责人 ID 并查询名称映射（合并原 4 处重复块，行为等价）。 */
  static Map<Long, String> collectUserNameMap(
      Collection<CustomerCompanyEntity> entities, DataConvertService dataConvertService) {
    Set<Long> allUserIds = new HashSet<>();
    entities.forEach(
        e -> {
          if (e.getCreatorId() != null) allUserIds.add(e.getCreatorId());
          if (e.getOwnerId() != null) allUserIds.add(e.getOwnerId());
        });
    return dataConvertService.getUserNames(allUserIds);
  }

  /** 实体 + 名称映射 → 客户公司 VO */
  static CustomerCompanyVO toVo(CustomerCompanyEntity entity, Map<Long, String> userNameMap) {
    return CustomerCompanyVO.fromEntity(
        entity, userNameMap.get(entity.getCreatorId()), userNameMap.get(entity.getOwnerId()));
  }

  /** 实体列表 → VO 列表（批量查询名称） */
  static List<CustomerCompanyVO> toVoList(
      List<CustomerCompanyEntity> entities, DataConvertService dataConvertService) {
    Map<Long, String> userNameMap = collectUserNameMap(entities, dataConvertService);
    return entities.stream()
        .map(e -> toVo(e, userNameMap))
        .collect(java.util.stream.Collectors.toList());
  }

  /** 实体分页 → VO 分页（批量查询名称） */
  static Page<CustomerCompanyVO> toVoPage(
      Page<CustomerCompanyEntity> pageResult, DataConvertService dataConvertService) {
    Page<CustomerCompanyVO> ans = new Page<>();
    BeanUtils.copyProperties(pageResult, ans);
    ans.setRecords(toVoList(pageResult.getRecords(), dataConvertService));
    return ans;
  }

  /**
   * 数据检验：客户属性和等级
   *
   * @param customerCompanyDTO 客户公司 DTO
   */
  static void validateCustomerTypeAndGrade(CustomerCompanyDTO customerCompanyDTO) {
    // 验证部门长度（可选字段，长度不超过 50）
    if (customerCompanyDTO.getDept() != null && customerCompanyDTO.getDept().length() > 50) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门格式错误，长度不能超过 50 个字符");
    }

    // 验证客户属性
    if (customerCompanyDTO.getCustomerType() != null) {
      String customerType = customerCompanyDTO.getCustomerType();
      if (!"代理".equals(customerType) && !"直销".equals(customerType)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "客户属性格式错误，只能为'代理'或'直销'");
      }
    }

    // 验证客户等级范围（0-9）
    if (customerCompanyDTO.getGrade() != null) {
      if (customerCompanyDTO.getGrade() < 0 || customerCompanyDTO.getGrade() > 9) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "客户等级格式错误，必须在 0-9 之间（0 最低，9 最高）");
      }
    }
  }

  /**
   * 校验公司名+部门组合唯一（与 Excel 导入判重一致：同名同部门拒绝，同名不同部门允许）
   *
   * @param dto 客户公司 DTO
   * @param excludeId 编辑时排除自身 ID，新增传 null
   * @param customerCompanyMapper 客户公司 mapper
   */
  static void validateCompanyNameDeptUnique(
      CustomerCompanyDTO dto, Long excludeId, CustomerCompanyMapper customerCompanyMapper) {
    if (dto == null || dto.getCompanyName() == null || dto.getCompanyName().trim().isEmpty()) {
      return;
    }
    String companyName = dto.getCompanyName().trim();
    String dept = normalizeDept(dto.getDept());
    LambdaQueryWrapper<CustomerCompanyEntity> wrapper =
        new LambdaQueryWrapper<CustomerCompanyEntity>()
            .eq(CustomerCompanyEntity::getCompanyName, companyName)
            .eq(CustomerCompanyEntity::getIsDeleted, false)
            .and(
                w -> {
                  if (dept == null) {
                    w.isNull(CustomerCompanyEntity::getDept)
                        .or()
                        .eq(CustomerCompanyEntity::getDept, "");
                  } else {
                    w.eq(CustomerCompanyEntity::getDept, dept);
                  }
                });
    if (excludeId != null) {
      wrapper.ne(CustomerCompanyEntity::getId, excludeId);
    }
    Long count = customerCompanyMapper.selectCount(wrapper);
    if (count != null && count > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "公司名称与部门组合已存在，请勿重复添加");
    }
  }

  /**
   * 部门规范化：去除首尾空格，空串视为 null（与"未填写"等价，同 Excel 导入）
   *
   * @param dept 部门（可空）
   * @return 规范化后的部门
   */
  static String normalizeDept(String dept) {
    String result = null;
    if (dept != null) {
      String trimmed = dept.trim();
      result = trimmed.isEmpty() ? null : trimmed;
    }
    return result;
  }

  /** 文本字段非空白即追加 like 条件 */
  static void likeIfNotBlank(
      LambdaQueryWrapper<CustomerCompanyEntity> wrapper,
      SFunction<CustomerCompanyEntity, ?> column,
      String value) {
    if (value != null && !value.isBlank()) {
      wrapper.like(column, value);
    }
  }

  /** 文本字段非空白即追加 eq 条件 */
  static void eqIfNotBlank(
      LambdaQueryWrapper<CustomerCompanyEntity> wrapper,
      SFunction<CustomerCompanyEntity, ?> column,
      String value) {
    if (value != null && !value.isBlank()) {
      wrapper.eq(column, value);
    }
  }

  /** 按分组字段取分组键 */
  static Object getGroupKey(CustomerCompanyEntity entity, String field) {
    return switch (field) {
      case "industry" -> entity.getIndustry();
      case "customerType" -> entity.getCustomerType();
      case "ownerId" -> entity.getOwnerId();
      default -> null;
    };
  }

  /**
   * 根据联系人信息获取其所属公司信息（拆自 getCompanyByCondition，行为等价）。
   *
   * @param id 联系人id
   * @param customerContactMapper 联系人 mapper
   * @param companyMapper 公司 mapper
   * @param dataConvertService 数据转换服务
   * @return 公司信息VO对象
   */
  static CustomerCompanyVO getCompanyByCondition(
      Long id,
      CustomerContactMapper customerContactMapper,
      CustomerCompanyMapper companyMapper,
      DataConvertService dataConvertService) {
    CustomerContactEntity customerContactEntity = customerContactMapper.selectById(id);
    if (customerContactEntity == null) {
      throw new BaseException(ErrorCode.CONTACT_NOT_EXISTS);
    }

    Long companyId = customerContactEntity.getCompanyId();
    if (companyId == null) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS);
    }

    CustomerCompanyEntity customerCompanyEntity = companyMapper.selectById(companyId);
    if (customerCompanyEntity == null) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS);
    }
    String creatorName = dataConvertService.getUserName(customerCompanyEntity.getCreatorId());
    String ownerName = dataConvertService.getUserName(customerCompanyEntity.getOwnerId());
    return CustomerCompanyVO.fromEntity(customerCompanyEntity, creatorName, ownerName);
  }

  /**
   * 公司详情：参数校验 + 读权限断言 + VO 装配 + 协助相关人标记（拆自 getCompanyDetail，行为等价）。
   *
   * @param id 公司 ID
   * @param lookup 主类 getById 查询
   * @param businessRecordAccessService 对象级授权服务
   * @param dataConvertService 数据转换服务
   * @return 公司详情 VO
   */
  static CustomerCompanyVO getCompanyDetail(
      Long id,
      java.util.function.Function<Long, CustomerCompanyEntity> lookup,
      BusinessRecordAccessService businessRecordAccessService,
      DataConvertService dataConvertService) {
    // 参数校验
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    CustomerCompanyEntity customerCompanyEntity = lookup.apply(id);
    if (customerCompanyEntity == null) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS);
    }
    boolean assistRelated = businessRecordAccessService.assertCanReadCompany(customerCompanyEntity);

    String creatorName = dataConvertService.getUserName(customerCompanyEntity.getCreatorId());
    String ownerName = dataConvertService.getUserName(customerCompanyEntity.getOwnerId());

    CustomerCompanyVO vo =
        CustomerCompanyVO.fromEntity(customerCompanyEntity, creatorName, ownerName);
    // 对象级授权已经确认当前用户与该公司有关，详情按本人规则展示
    Long currentId = BaseUnit.getCurrentId();
    if (currentId != null && assistRelated) {
      vo.setRelatedUserIds(java.util.Set.of(currentId));
    }
    return vo;
  }

  /**
   * 实体列表 → 客户公司 Excel 字节流（拆自 excel()，行为等价）。
   *
   * @param entities 未删除的公司实体
   * @param dataConvertService 数据转换服务
   * @param aotoExcelUntil Excel 导出工具
   * @return Excel 字节流
   */
  static byte[] buildExcelBytes(
      List<CustomerCompanyEntity> entities,
      DataConvertService dataConvertService,
      AOTOExcelUntil aotoExcelUntil) {
    List<CustomerCompanyExcel> list = new java.util.ArrayList<>();

    Map<Long, String> userNameMap = collectUserNameMap(entities, dataConvertService);

    // 构建 Excel 列表
    for (CustomerCompanyEntity entity : entities) {
      CustomerCompanyExcel excel = new CustomerCompanyExcel();
      org.springframework.beans.BeanUtils.copyProperties(entity, excel);

      excel.setCustomerType(entity.getCustomerType());
      excel.setCreatorName(userNameMap.get(entity.getCreatorId()));
      excel.setOwnerName(userNameMap.get(entity.getOwnerId()));

      list.add(excel);
    }

    return aotoExcelUntil.AOTOExcelByStream(list, "客户公司");
  }

  /**
   * 合并客户：被合并客户失效 + 落合并日志（拆自 merge()，行为等价）。
   *
   * @param customerMergeDTO 合并请求
   * @param companyMapper 公司 mapper
   * @param customerMergeLogMapper 合并日志 mapper
   */
  static void applyMerge(
      CustomerMergeDTO customerMergeDTO,
      CustomerCompanyMapper companyMapper,
      CustomerMergeLogMapper customerMergeLogMapper) {
    CustomerMergeLogEntity entity = new CustomerMergeLogEntity();
    org.springframework.beans.BeanUtils.copyProperties(customerMergeDTO, entity);
    entity.setOperatorId(BaseUnit.getCurrentId());

    // 让被合并客户失效
    companyMapper.update(
        null,
        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                CustomerCompanyEntity>()
            .set(CustomerCompanyEntity::getIsDeleted, true)
            .eq(CustomerCompanyEntity::getId, customerMergeDTO.getMergedCompanyId()));

    // 添加合并记录
    customerMergeLogMapper.insert(entity);
  }

  /**
   * 批量更新的实体装配：判重 + 校验 + 规范化（拆自 updateList 循环体，行为等价）。
   *
   * @param customerCompanyDTOList 更新请求列表
   * @param customerCompanyMapper 公司 mapper
   * @param dataConvertService 数据转换服务（未使用，保留一致性签名）
   * @return 待更新实体列表
   */
  static List<CustomerCompanyEntity> buildUpdateEntities(
      List<CustomerCompanyDTO> customerCompanyDTOList,
      CustomerCompanyMapper customerCompanyMapper) {
    List<CustomerCompanyEntity> customerCompanyEntityList = new java.util.ArrayList<>();

    for (CustomerCompanyDTO customerCompanyDTO : customerCompanyDTOList) {

      // 校验公司名+部门组合唯一（排除自身）
      validateCompanyNameDeptUnique(
          customerCompanyDTO, customerCompanyDTO.getId(), customerCompanyMapper);

      // 数据验证：客户属性和等级
      validateCustomerTypeAndGrade(customerCompanyDTO);

      CustomerCompanyEntity customerCompanyEntity = new CustomerCompanyEntity();
      org.springframework.beans.BeanUtils.copyProperties(customerCompanyDTO, customerCompanyEntity);
      // 部门规范化：空串视为未填写
      customerCompanyEntity.setDept(normalizeDept(customerCompanyDTO.getDept()));

      if (customerCompanyEntity.getGrade() == null) {
        customerCompanyEntity.setGrade(0);
      }
      customerCompanyEntity.setCreatorId(BaseUnit.getCurrentId());

      customerCompanyEntityList.add(customerCompanyEntity);
    }

    return customerCompanyEntityList;
  }

  /**
   * Excel 导入读取（拆自 list()，行为等价）。
   *
   * @param inputStream 上传文件流
   * @param userMapper 用户 mapper
   * @param baseMapper 公司 mapper
   * @return 解析结果（实体列表 + 行数）
   */
  static ImportResult readImport(
      java.io.InputStream inputStream,
      com.slz.crm.server.mapper.UserMapper userMapper,
      CustomerCompanyMapper baseMapper)
      throws java.io.IOException {
    com.slz.crm.common.excellistener.CustomerCompanyListener listener =
        new com.slz.crm.common.excellistener.CustomerCompanyListener(userMapper, baseMapper);
    com.alibaba.excel.EasyExcel.read(inputStream, CustomerCompanyExcel.class, listener)
        .sheet()
        .doRead();
    return new ImportResult(listener.getData(), listener.getRowCount());
  }

  /** Excel 导入解析结果。 */
  record ImportResult(List<CustomerCompanyEntity> data, int rowCount) {}

  /**
   * 按字段分组构建分组结果（拆自 groupByCustomerCompany，行为等价）。
   *
   * @param entities 未删除的公司实体
   * @param field 分组字段（industry/customerType/ownerId）
   * @param dataConvertService 数据转换服务
   * @return 分组结果 VO
   */
  static com.slz.crm.pojo.vo.GroupResultVO buildGroupResult(
      List<CustomerCompanyEntity> entities, String field, DataConvertService dataConvertService) {
    Map<Long, String> userNameMap = collectUserNameMap(entities, dataConvertService);

    Map<Object, List<CustomerCompanyVO>> groupMap = new java.util.HashMap<>();

    // 按不同字段分组
    for (CustomerCompanyEntity entity : entities) {
      CustomerCompanyVO vo = toVo(entity, userNameMap);

      Object groupKey = getGroupKey(entity, field);
      if (groupKey == null) {
        continue;
      }

      groupMap.computeIfAbsent(groupKey, k -> new java.util.ArrayList<>()).add(vo);
    }

    com.slz.crm.pojo.vo.GroupResultVO resultVO = new com.slz.crm.pojo.vo.GroupResultVO();
    resultVO.setGroupField(field);
    resultVO.setGroupData(groupMap);
    return resultVO;
  }

  /**
   * 删除/恢复批量实体装配（拆自 deleteOrRecoverByIds 循环体，行为等价）。
   *
   * @param idList ID 列表
   * @param isRecover true=恢复，false=删除
   * @param lookup 主类 getById 查询
   * @param customerCompanyMapper 公司 mapper
   * @return 待更新实体列表
   */
  static List<CustomerCompanyEntity> buildRecoverEntities(
      List<Long> idList,
      Boolean isRecover,
      java.util.function.Function<Long, CustomerCompanyEntity> lookup,
      CustomerCompanyMapper customerCompanyMapper) {
    List<CustomerCompanyEntity> customerCompanyEntityList = new java.util.ArrayList<>();

    for (Long id : idList) {
      CustomerCompanyEntity existing = lookup.apply(id);
      if (existing == null) {
        throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("客户公司"));
      }
      if (!isRecover) {
        // 恢复校验：正常记录中不允许再出现同名同部门（回收站里的重复公司恢复时会冲突）
        CustomerCompanyDTO dto = new CustomerCompanyDTO();
        dto.setCompanyName(existing.getCompanyName());
        dto.setDept(existing.getDept());
        validateCompanyNameDeptUnique(dto, id, customerCompanyMapper);
      }
      CustomerCompanyEntity customerCompanyEntity = new CustomerCompanyEntity();
      customerCompanyEntity.setId(id);
      customerCompanyEntity.setIsDeleted(isRecover);
      customerCompanyEntityList.add(customerCompanyEntity);
    }

    return customerCompanyEntityList;
  }
}
