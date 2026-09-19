package com.slz.crm.server.service.impl;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.excellistener.CustomerCompanyListener;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.common.untils.ValidationUtils;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.dto.CustomerMergeDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerMergeLogEntity;
import com.slz.crm.pojo.excel.CustomerCompanyExcel;
import com.slz.crm.pojo.excel.GetCustomerCompanyExcel;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.GroupResultVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.CustomerMergeLogMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.SalesOpportunityService;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class CustomerCompanyServiceImpl
    extends ServiceImpl<CustomerCompanyMapper, CustomerCompanyEntity>
    implements CustomerCompanyService {

  @Autowired private UserMapper userMapper;

  @Autowired private CustomerCompanyMapper customerCompanyMapper;

  @Autowired private CustomerMergeLogMapper customerMergeLogMapper;

  @Autowired private ForeignKeyDeleteUtil foreignKeyDeleteUtil;

  @Autowired private AOTOExcelUntil aotoExcelUntil;

  @Autowired private CustomerContactMapper customerContactMapper;

  @Autowired private CustomerContactService customerContactService;

  @Autowired private SalesOpportunityService salesOpportunityService;

  @Autowired private ContractOrderItemService contractOrderItemService;

  @Autowired private DataConvertService dataConvertService;

  @Autowired private BusinessRecordAccessService businessRecordAccessService;

  @Override
  public CustomerCompanyVO add(CustomerCompanyDTO customerCompanyDTO) {

    if (customerCompanyDTO.getPhone() != null && !customerCompanyDTO.getPhone().isBlank()) {

      isValid(customerCompanyDTO.getPhone());
    }
    // 校验公司名+部门组合唯一（部门可空，同名不同部门允许）
    validateCompanyNameDeptUnique(customerCompanyDTO, null);

    CustomerCompanyEntity customerCompanyEntity = new CustomerCompanyEntity();

    // 数据验证：客户属性和等级
    validateCustomerTypeAndGrade(customerCompanyDTO);

    BeanUtils.copyProperties(customerCompanyDTO, customerCompanyEntity);
    // 部门规范化：空串视为未填写，避免库里同时存在 null 与 ""
    customerCompanyEntity.setDept(normalizeDept(customerCompanyDTO.getDept()));

    Long currentId = BaseUnit.getCurrentId();

    customerCompanyEntity.setCreatorId(currentId);
    customerCompanyEntity.setIsDeleted(false);

    // 处理客户等级字段（所有用户都可以配置）
    if (customerCompanyDTO.getGrade() == null) {
      customerCompanyEntity.setGrade(0);
    }

    try {
      if (!save(customerCompanyEntity)) {
        return null;
      }

      CustomerCompanyVO created = new CustomerCompanyVO();
      created.setId(customerCompanyEntity.getId());
      created.setCompanyName(customerCompanyEntity.getCompanyName());
      return created;
    } catch (DuplicateKeyException e) {
      // 并发写数据库唯一约束 uk_company_name_dept 兑底（正常路径已由 Java 判重拦截）
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "公司名称与部门组合已存在，请勿重复添加");
    }
  }

  /**
   * 数据检验：客户属性和等级
   *
   * @param customerCompanyDTO 客户公司 DTO
   */
  private static void validateCustomerTypeAndGrade(CustomerCompanyDTO customerCompanyDTO) {
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
   */
  private void validateCompanyNameDeptUnique(CustomerCompanyDTO dto, Long excludeId) {
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
  private static String normalizeDept(String dept) {
    if (dept == null) {
      return null;
    }
    String trimmed = dept.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int list(MultipartFile file) {

    if (file.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    List<CustomerCompanyEntity> dataList = null;
    int rowCount = 0;

    // 使用try-with-resources自动关闭流
    try (InputStream inputStream = file.getInputStream()) {
      CustomerCompanyListener listener = new CustomerCompanyListener(userMapper, baseMapper);
      EasyExcel.read(inputStream, CustomerCompanyExcel.class, listener).sheet().doRead();
      dataList = listener.getData();
      rowCount = listener.getRowCount();
    } catch (IOException e) {
      log.error("Excel文件读取失败", e);
      throw new BaseException(ErrorCode.FILE_FORMAT_ERROR, "文件读取失败: " + e.getMessage());
    }

    if (dataList == null || dataList.isEmpty()) {
      // Excel 中有数据但全部已存在（或本次重复），视为成功导入 0 条，而不是报“数据为空”
      if (rowCount > 0) {
        return 0;
      }
      throw new BaseException(ErrorCode.COMPANY_DATA_EMPTY);
    }

    try {
      saveBatch(dataList);
    } catch (DuplicateKeyException e) {
      // 并发/重复导入时数据库唯一约束兑底
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "导入数据中公司名称与部门组合已存在，请勿重复导入");
    }
    return dataList.size();
  }

  private void isValid(String phone) {
    if (!ValidationUtils.isValidMobile(phone)) {
      throw new BaseException(ErrorCode.PHONE_FORMAT_ERROR);
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "companyName", allEntries = true)
  public Integer updateList(List<CustomerCompanyDTO> customerCompanyDTOList) {

    List<CustomerCompanyEntity> customerCompanyEntityList = new ArrayList<>();

    for (CustomerCompanyDTO customerCompanyDTO : customerCompanyDTOList) {

      // 校验公司名+部门组合唯一（排除自身）
      validateCompanyNameDeptUnique(customerCompanyDTO, customerCompanyDTO.getId());

      // 数据验证：客户属性和等级
      validateCustomerTypeAndGrade(customerCompanyDTO);

      CustomerCompanyEntity customerCompanyEntity = new CustomerCompanyEntity();
      BeanUtils.copyProperties(customerCompanyDTO, customerCompanyEntity);
      // 部门规范化：空串视为未填写
      customerCompanyEntity.setDept(normalizeDept(customerCompanyDTO.getDept()));

      if (customerCompanyEntity.getGrade() == null) {
        customerCompanyEntity.setGrade(0);
      }
      customerCompanyEntity.setCreatorId(BaseUnit.getCurrentId());

      customerCompanyEntityList.add(customerCompanyEntity);
    }

    updateBatchById(customerCompanyEntityList);

    return customerCompanyEntityList.size();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "companyName", allEntries = true)
  public Integer deleteOrRecoverByIds(List<Long> idList, Boolean isRecover) {

    List<CustomerCompanyEntity> customerCompanyEntityList = new ArrayList<>();

    for (Long id : idList) {
      CustomerCompanyEntity existing = getById(id);
      if (existing == null) {
        throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("客户公司"));
      }
      if (!isRecover) {
        // 恢复校验：正常记录中不允许再出现同名同部门（回收站里的重复公司恢复时会冲突）
        CustomerCompanyDTO dto = new CustomerCompanyDTO();
        dto.setCompanyName(existing.getCompanyName());
        dto.setDept(existing.getDept());
        validateCompanyNameDeptUnique(dto, id);
      }
      CustomerCompanyEntity customerCompanyEntity = new CustomerCompanyEntity();
      customerCompanyEntity.setId(id);
      customerCompanyEntity.setIsDeleted(isRecover);
      customerCompanyEntityList.add(customerCompanyEntity);
    }

    try {
      updateBatchById(customerCompanyEntityList);
    } catch (DuplicateKeyException e) {
      // 恢复时正常记录中已存在同公司同部门，数据库唯一约束兑底
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "恢复失败：公司名称与部门组合已存在正常记录中");
    }

    return idList.size();
  }

  /**
   * 物理删除多个客户公司数据
   *
   * @param idList 客户公司ID列表
   * @return 成功删除的公司数量
   * @throws BaseException 当ID列表为空时抛出异常
   */
  @Transactional(rollbackFor = Exception.class)
  @Override
  @CacheEvict(value = "companyName", allEntries = true)
  public Integer deleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    // 1. 删除公司关联的所有外键数据（使用配置的关联关系）
    // 资源类型：1-公司（对应DataShareEntity的resourceType）
    foreignKeyDeleteUtil.deleteCascade(CustomerCompanyEntity.class, idList, 1);

    // 2. 物理删除公司本身
    return customerCompanyMapper.deleteBatchIds(idList);
  }

  @Override
  @CacheEvict(value = "companyName", key = "#id")
  public Boolean deleteById(Long id) {

    CustomerCompanyEntity customerCompanyEntity = new CustomerCompanyEntity();
    customerCompanyEntity.setId(id);
    customerCompanyEntity.setIsDeleted(true);

    this.updateById(customerCompanyEntity);

    // 级联删除联系人
    customerContactService.cascadeDeleteByCompanyId(id);

    // 级联删除销售机会
    salesOpportunityService.cascadeDeleteByCompanyId(id);

    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer logicalDeleteById(Long id) {
    return foreignKeyDeleteUtil.logicalDeleteByCascade(CustomerCompanyEntity.class, id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer batchLogicalDeleteByIds(List<Long> idList) {
    Integer totalDeleted = 0;
    for (Long id : idList) {
      totalDeleted += logicalDeleteById(id);
    }
    return totalDeleted;
  }

  /**
   * 根据联系人信息获取其所属公司信息
   *
   * @param id 联系人id
   * @return 公司信息VO对象
   * @throws BaseException 当联系人ID为空时抛出CONTACT_IS_NULL异常，当公司不存在时抛出COMPANY_IS_NULL异常
   */
  @Override
  @Privacy
  public CustomerCompanyVO getCompanyByCondition(Long id) {

    CustomerContactEntity customerContactEntity = customerContactMapper.selectById(id);
    if (customerContactEntity == null) {
      throw new BaseException(ErrorCode.CONTACT_NOT_EXISTS);
    }

    Long companyId = customerContactEntity.getCompanyId();
    if (companyId == null) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS);
    }

    CustomerCompanyEntity customerCompanyEntity = baseMapper.selectById(companyId);
    if (customerCompanyEntity == null) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS);
    }
    String creatorName = dataConvertService.getUserName(customerCompanyEntity.getCreatorId());
    String ownerName = dataConvertService.getUserName(customerCompanyEntity.getOwnerId());
    CustomerCompanyVO customerCompanyVO =
        CustomerCompanyVO.fromEntity(customerCompanyEntity, creatorName, ownerName);

    return customerCompanyVO;
  }

  @Override
  public byte[] excel() {

    List<CustomerCompanyExcel> list = new ArrayList<>();

    List<CustomerCompanyEntity> entities =
        list(
            new LambdaQueryWrapper<CustomerCompanyEntity>()
                .eq(CustomerCompanyEntity::getIsDeleted, false));

    if (entities.isEmpty()) {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // 批量收集用户ID并查询名称
    Set<Long> allUserIds = new HashSet<>();
    entities.forEach(
        e -> {
          if (e.getCreatorId() != null) allUserIds.add(e.getCreatorId());
          if (e.getOwnerId() != null) allUserIds.add(e.getOwnerId());
        });
    Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);

    // 构建 Excel 列表
    for (CustomerCompanyEntity entity : entities) {
      CustomerCompanyExcel excel = new CustomerCompanyExcel();
      BeanUtils.copyProperties(entity, excel);

      excel.setCustomerType(entity.getCustomerType());
      excel.setCreatorName(userNameMap.get(entity.getCreatorId()));
      excel.setOwnerName(userNameMap.get(entity.getOwnerId()));

      list.add(excel);
    }

    return aotoExcelUntil.AOTOExcelByStream(list, "客户公司");
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "companyName", key = "#customerMergeDTO.mergedCompanyId")
  public Boolean merge(CustomerMergeDTO customerMergeDTO) {

    CustomerMergeLogEntity entity = new CustomerMergeLogEntity();
    BeanUtils.copyProperties(customerMergeDTO, entity);
    entity.setOperatorId(BaseUnit.getCurrentId());

    // 让被合并客户失效
    update(
        new LambdaUpdateWrapper<CustomerCompanyEntity>()
            .set(CustomerCompanyEntity::getIsDeleted, true)
            .eq(CustomerCompanyEntity::getId, customerMergeDTO.getMergedCompanyId()));

    // 添加合并记录
    customerMergeLogMapper.insert(entity);

    return true;
  }

  @Override
  @Privacy
  public Page<CustomerCompanyVO> getAllCompany(Integer pageNum, Integer pageSize) {

    Page<CustomerCompanyVO> ans = new Page<>(pageNum, pageSize);

    Page<CustomerCompanyEntity> page = new Page<>(pageNum, pageSize);
    Page<CustomerCompanyEntity> pageResult =
        page(
            page,
            new LambdaQueryWrapper<CustomerCompanyEntity>()
                .eq(CustomerCompanyEntity::getIsDeleted, false));

    // 批量收集用户ID并查询名称
    Set<Long> allUserIds = new HashSet<>();
    pageResult
        .getRecords()
        .forEach(
            e -> {
              if (e.getCreatorId() != null) allUserIds.add(e.getCreatorId());
              if (e.getOwnerId() != null) allUserIds.add(e.getOwnerId());
            });
    Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);

    List<CustomerCompanyVO> customerCompanyVOList =
        pageResult.getRecords().stream()
            .map(
                entity ->
                    CustomerCompanyVO.fromEntity(
                        entity,
                        userNameMap.get(entity.getCreatorId()),
                        userNameMap.get(entity.getOwnerId())))
            .collect(Collectors.toList());

    BeanUtils.copyProperties(pageResult, ans);
    ans.setRecords(customerCompanyVOList);

    return ans;
  }

  @Override
  @Privacy
  public CustomerCompanyVO getCompanyDetail(Long id) {

    // 参数校验
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    CustomerCompanyEntity customerCompanyEntity = getById(id);
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
      vo.setRelatedUserIds(Set.of(currentId));
    }
    return vo;
  }

  @Override
  @Privacy
  public Page<CustomerCompanyVO> findCompany(
      String companyName, Integer pageNum, Integer pageSize) {
    LambdaQueryWrapper<CustomerCompanyEntity> wrapper = new LambdaQueryWrapper<>();
    wrapper
        .like(CustomerCompanyEntity::getCompanyName, companyName)
        .eq(CustomerCompanyEntity::getIsDeleted, false);

    Page<CustomerCompanyEntity> page = new Page<>(pageNum, pageSize);
    return getCustomerCompanyVOPage(wrapper, page);
  }

  /**
   * 分页获取客户公司VO列表
   *
   * @param wrapper 查询条件包装器
   * @param page 分页对象
   * @return 客户公司VO列表
   */
  private Page<CustomerCompanyVO> getCustomerCompanyVOPage(
      LambdaQueryWrapper<CustomerCompanyEntity> wrapper, Page<CustomerCompanyEntity> page) {
    Page<CustomerCompanyEntity> pageResult = page(page, wrapper);

    // 批量收集用户ID并查询名称
    Set<Long> allUserIds = new HashSet<>();
    pageResult
        .getRecords()
        .forEach(
            e -> {
              if (e.getCreatorId() != null) allUserIds.add(e.getCreatorId());
              if (e.getOwnerId() != null) allUserIds.add(e.getOwnerId());
            });
    Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);

    List<CustomerCompanyVO> list =
        pageResult.getRecords().stream()
            .map(
                entity ->
                    CustomerCompanyVO.fromEntity(
                        entity,
                        userNameMap.get(entity.getCreatorId()),
                        userNameMap.get(entity.getOwnerId())))
            .collect(Collectors.toList());

    Page<CustomerCompanyVO> ans = new Page<>();
    BeanUtils.copyProperties(pageResult, ans);
    ans.setRecords(list);

    return ans;
  }

  @Override
  @Privacy
  public Page<CustomerCompanyVO> customQuery(
      CustomerCompanyDTO customerCompanyDTO, Integer pageNum, Integer pageSize) {
    if (pageNum == null || pageSize == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    Page<CustomerCompanyEntity> page = new Page<>(pageNum, pageSize);

    LambdaQueryWrapper<CustomerCompanyEntity> wrapper = new LambdaQueryWrapper<>();

    if (customerCompanyDTO.getCompanyName() != null
        && !customerCompanyDTO.getCompanyName().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getCompanyName, customerCompanyDTO.getCompanyName());
    }
    if (customerCompanyDTO.getIndustry() != null && !customerCompanyDTO.getIndustry().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getIndustry, customerCompanyDTO.getIndustry());
    }
    if (customerCompanyDTO.getCustomerType() != null
        && !customerCompanyDTO.getCustomerType().isBlank()) {
      wrapper.eq(CustomerCompanyEntity::getCustomerType, customerCompanyDTO.getCustomerType());
    }
    if (customerCompanyDTO.getBelongGroup() != null
        && !customerCompanyDTO.getBelongGroup().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getBelongGroup, customerCompanyDTO.getBelongGroup());
    }
    if (customerCompanyDTO.getDept() != null && !customerCompanyDTO.getDept().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getDept, customerCompanyDTO.getDept());
    }
    if (customerCompanyDTO.getAddress() != null && !customerCompanyDTO.getAddress().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getAddress, customerCompanyDTO.getAddress());
    }
    if (customerCompanyDTO.getPhone() != null && !customerCompanyDTO.getPhone().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getPhone, customerCompanyDTO.getPhone());
    }
    if (customerCompanyDTO.getWebsite() != null && !customerCompanyDTO.getWebsite().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getWebsite, customerCompanyDTO.getWebsite());
    }
    if (customerCompanyDTO.getDescription() != null
        && !customerCompanyDTO.getDescription().isBlank()) {
      wrapper.like(CustomerCompanyEntity::getDescription, customerCompanyDTO.getDescription());
    }
    if (customerCompanyDTO.getIsDeleted() != null) {
      wrapper.eq(CustomerCompanyEntity::getIsDeleted, customerCompanyDTO.getIsDeleted());
    }
    return getCustomerCompanyVOPage(wrapper, page);
  }

  /**
   * 根据wrapper查询名称
   *
   * @param wrapper 查询条件包装器
   * @return 客户公司VO列表
   */
  private List<CustomerCompanyVO> getCustomerCompanyVOS(
      LambdaQueryWrapper<CustomerCompanyEntity> wrapper) {
    List<CustomerCompanyEntity> entities = list(wrapper);

    // 批量收集用户ID并查询名称
    Set<Long> allUserIds = new HashSet<>();
    entities.forEach(
        e -> {
          if (e.getCreatorId() != null) allUserIds.add(e.getCreatorId());
          if (e.getOwnerId() != null) allUserIds.add(e.getOwnerId());
        });
    Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);

    return entities.stream()
        .map(
            entity ->
                CustomerCompanyVO.fromEntity(
                    entity,
                    userNameMap.get(entity.getCreatorId()),
                    userNameMap.get(entity.getOwnerId())))
        .collect(Collectors.toList());
  }

  @Override
  public GroupResultVO groupByCustomerCompany(String field) {

    // 检查字段是否有效
    if (!Arrays.asList("industry", "customerType", "ownerId").contains(field)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    LambdaQueryWrapper<CustomerCompanyEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(CustomerCompanyEntity::getIsDeleted, false);

    List<CustomerCompanyEntity> entities = customerCompanyMapper.selectList(queryWrapper);

    // 批量收集用户ID并查询名称
    Set<Long> allUserIds = new HashSet<>();
    entities.forEach(
        e -> {
          if (e.getCreatorId() != null) allUserIds.add(e.getCreatorId());
          if (e.getOwnerId() != null) allUserIds.add(e.getOwnerId());
        });
    Map<Long, String> userNameMap = dataConvertService.getUserNames(allUserIds);

    Map<Object, List<CustomerCompanyVO>> groupMap = new HashMap<>();

    // 按不同字段分组
    for (CustomerCompanyEntity entity : entities) {
      CustomerCompanyVO vo =
          CustomerCompanyVO.fromEntity(
              entity, userNameMap.get(entity.getCreatorId()), userNameMap.get(entity.getOwnerId()));

      Object groupKey = getGroupKey(entity, field);
      if (groupKey == null) {
        continue;
      }

      groupMap.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(vo);
    }

    GroupResultVO resultVO = new GroupResultVO();
    resultVO.setGroupField(field);
    resultVO.setGroupData(groupMap);
    return resultVO;
  }

  private Object getGroupKey(CustomerCompanyEntity entity, String field) {
    return switch (field) {
      case "industry" -> entity.getIndustry();
      case "customerType" -> entity.getCustomerType();
      case "ownerId" -> entity.getOwnerId();
      default -> null;
    };
  }

  @Override
  public byte[] template() {
    List<GetCustomerCompanyExcel> customerCompanyExcels = new ArrayList<>();
    customerCompanyExcels.add(new GetCustomerCompanyExcel());
    return aotoExcelUntil.AOTOExcelByStream(
        customerCompanyExcels, "customer_company_template.xlsx");
  }

  @Override
  public Page<CustomerCompanyVO> findCompanyByGroup(
      String belongGroup, Integer pageNum, Integer pageSize) {
    LambdaQueryWrapper<CustomerCompanyEntity> wrapper = new LambdaQueryWrapper<>();
    wrapper
        .like(CustomerCompanyEntity::getBelongGroup, belongGroup)
        .eq(CustomerCompanyEntity::getIsDeleted, false);
    return getCustomerCompanyVOPage(wrapper, new Page<>(pageNum, pageSize));
  }
}
