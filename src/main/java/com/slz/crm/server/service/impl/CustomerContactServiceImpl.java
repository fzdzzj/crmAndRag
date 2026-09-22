package com.slz.crm.server.service.impl;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.enumeration.RemarkType;
import com.slz.crm.common.excellistener.CustomerContactListener;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.pojo.dto.BirthdayInfoDTO;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.dto.CustomerContactRemarkDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.RolePermissionsEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.excel.CustomerContactExcel;
import com.slz.crm.pojo.excel.GetCustomerContactExcel;
import com.slz.crm.pojo.vo.CustomerContactRemarkVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.GroupResultVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.*;
import com.slz.crm.server.properties.BirthdayReminderProperties;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.SalesOpportunityService;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CustomerContactServiceImpl
    extends ServiceImpl<CustomerContactMapper, CustomerContactEntity>
    implements CustomerContactService {

  @Autowired private CustomerCompanyMapper customerCompanyMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private CustomerContactMapper customerContactMapper;

  @Autowired private ForeignKeyDeleteUtil foreignKeyDeleteUtil;

  @Autowired private AOTOExcelUntil aotoExcelUntil;

  @Autowired private SalesOpportunityService salesOpportunityService;

  @Autowired private ContractOrderItemService contractOrderItemService;
  @Autowired private RolePermissionsMapper rolePermissionsMapper;
  @Autowired private RoleMapper roleMapper;
  @Autowired private CustomerContactRemarkMapper customerContactRemarkMapper;
  @Autowired private BirthdayReminderProperties birthdayReminderProperties;

  @Autowired private DataConvertService dataConvertService;

  @Autowired private BusinessRecordAccessService businessRecordAccessService;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int list(MultipartFile file) {

    if (file.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }
    List<CustomerContactEntity> dataList;

    try {
      CustomerContactListener listener = new CustomerContactListener(customerCompanyMapper);
      EasyExcel.read(file.getInputStream(), CustomerContactExcel.class, listener).sheet().doRead();
      dataList = listener.getDataList();

      // 保存联系人数据
      saveBatch(dataList);

      // 保存备注数据
      List<List<CustomerContactRemarkEntity>> remarkList = listener.getRemarkList();
      if (!remarkList.isEmpty()) {
        List<CustomerContactRemarkEntity> allRemarks = new ArrayList<>();
        for (int i = 0; i < remarkList.size(); i++) {
          List<CustomerContactRemarkEntity> remarks = remarkList.get(i);
          if (!remarks.isEmpty() && i < dataList.size()) {
            Long contactId = dataList.get(i).getId();
            for (CustomerContactRemarkEntity remark : remarks) {
              remark.setContactId(contactId);
              remark.setCreateTime(LocalDateTime.now());
              allRemarks.add(remark);
            }
          }
        }
        if (!allRemarks.isEmpty()) {
          customerContactRemarkMapper.insertBatch(allRemarks);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return dataList.size();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "contactName", allEntries = true)
  public int updateList(List<CustomerContactDTO> list) {

    Long creatorId = BaseUnit.getCurrentId();

    List<CustomerContactEntity> dataList = new ArrayList<>();

    list.forEach(
        data -> {
          // 验证关系等级
          validateRelationLevel(data.getRelationLevel());

          CustomerContactEntity entity = new CustomerContactEntity();
          BeanUtils.copyProperties(data, entity);
          entity.setCreatorId(creatorId);
          entity.setUpdateTime(LocalDateTime.now());
          dataList.add(entity);
        });

    updateBatchById(dataList);

    // 处理备注信息：先删除原有备注，再新增
    for (CustomerContactDTO dto : list) {
      if (dto.getId() != null) {
        // 删除该联系人原有的所有备注
        LambdaQueryWrapper<CustomerContactRemarkEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomerContactRemarkEntity::getContactId, dto.getId());
        customerContactRemarkMapper.delete(queryWrapper);

        // 新增备注
        List<CustomerContactRemarkDTO> remarks = dto.getRemarks();
        if (remarks != null && !remarks.isEmpty()) {
          for (CustomerContactRemarkDTO remark : remarks) {
            // 验证备注类型
            validateRemarkType(remark);

            CustomerContactRemarkEntity remarkEntity = new CustomerContactRemarkEntity();
            BeanUtils.copyProperties(remark, remarkEntity);
            remarkEntity.setContactId(dto.getId());
            remarkEntity.setCreatorId(creatorId);
            customerContactRemarkMapper.insert(remarkEntity);
          }
        }
      }
    }

    return list.size();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public CustomerContactVO insert(CustomerContactDTO customerContactDTO) {

    Long creatorId = BaseUnit.getCurrentId();

    // 验证关系等级
    validateRelationLevel(customerContactDTO.getRelationLevel());

    CustomerContactEntity entity = new CustomerContactEntity();
    BeanUtils.copyProperties(customerContactDTO, entity);
    entity.setCreatorId(creatorId);
    entity.setIsDeleted(false);
    CustomerContactVO created = null;
    if (baseMapper.insert(entity) > 0) {
      // 处理备注信息
      List<CustomerContactRemarkDTO> remarks = customerContactDTO.getRemarks();
      if (remarks != null && !remarks.isEmpty()) {
        for (CustomerContactRemarkDTO remark : remarks) {
          // 验证备注类型
          validateRemarkType(remark);

          CustomerContactRemarkEntity remarkEntity = new CustomerContactRemarkEntity();
          BeanUtils.copyProperties(remark, remarkEntity);
          remarkEntity.setContactId(entity.getId());
          remarkEntity.setCreatorId(creatorId);
          customerContactRemarkMapper.insert(remarkEntity);
        }
      }

      created = new CustomerContactVO();
      created.setId(entity.getId());
      created.setName(entity.getName());
    }
    return created;
  }

  /**
   * 验证备注类型是否合法
   *
   * @param remark 备注 DTO
   */
  @Override
  public void validateRemarkType(CustomerContactRemarkDTO remark) {
    if (remark.getRemarkType() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "备注类型不能为空");
    }

    RemarkType remarkType = RemarkType.getByCode(remark.getRemarkType());
    if (remarkType == null) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, "备注类型无效，只支持：1-喜好、2-住址、3-本人出生日期、4-亲属出生日期、5-自定义");
    }

    int remarkTypeCode = remark.getRemarkType();

    // 根据备注类型验证必填字段
    // 喜好、住址、自定义类型需要填写备注内容
    if (RemarkType.needContent(remarkTypeCode)
        && (remark.getRemarkContent() == null || remark.getRemarkContent().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型需要填写备注内容");
    }

    // 本人出生日期、亲属出生日期类型需要填写出生日期
    if (RemarkType.needBirthday(remarkTypeCode) && remark.getRemarkDate() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型需要填写出生日期");
    }

    // 亲属出生日期类型需要填写姓名
    if (RemarkType.needName(remarkTypeCode)
        && (remark.getRemarkName() == null || remark.getRemarkName().trim().isEmpty())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型需要填写姓名");
    }

    // 验证不能填写的字段
    // 喜好、住址、自定义类型只能填备注内容，不能填姓名和出生日期
    if (RemarkType.onlyNeedContent(remarkTypeCode)) {
      if (remark.getRemarkName() != null && !remark.getRemarkName().trim().isEmpty()) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写姓名");
      }
      if (remark.getRemarkDate() != null) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写出生日期");
      }
    }

    // 本人出生日期类型只填写出生日期，不能填备注内容和姓名
    if (RemarkType.isSelfBirthday(remarkTypeCode)) {
      if (remark.getRemarkContent() != null && !remark.getRemarkContent().trim().isEmpty()) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写备注内容");
      }
      if (remark.getRemarkName() != null && !remark.getRemarkName().trim().isEmpty()) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写姓名");
      }
    }

    // 亲属出生日期类型需要填写姓名和出生日期，不能填备注内容
    if (RemarkType.isRelativeBirthday(remarkTypeCode)) {
      if (remark.getRemarkContent() != null && !remark.getRemarkContent().trim().isEmpty()) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, remarkType.getDesc() + "类型不能填写备注内容");
      }
    }
  }

  /**
   * 验证关系等级是否合法（1-9）
   *
   * @param relationLevel 关系等级
   */
  private void validateRelationLevel(Integer relationLevel) {
    if (relationLevel == null) {
      return; // 允许为空，不强制填写
    }
    if (relationLevel < 1 || relationLevel > 9) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "关系等级必须在 1-9 之间");
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "contactName", allEntries = true)
  public int deleteOrRecoverByIds(List<Long> idList, boolean isDelete) {

    List<CustomerContactEntity> dataList = new ArrayList<>();

    idList.forEach(
        id -> {
          CustomerContactEntity entity = getById(id);
          entity.setIsDeleted(isDelete);
          dataList.add(entity);
        });

    updateBatchById(dataList);

    // 如果是删除操作，级联删除相关数据
    if (isDelete) {
      // 级联删除相关销售机会
      salesOpportunityService.cascadeDeleteByContactIds(idList);
    }

    return dataList.size();
  }

  @Transactional(rollbackFor = Exception.class)
  @Override
  @CacheEvict(value = "contactName", allEntries = true)
  public int deleteByIds(List<Long> idList) {

    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 1. 删除客户联系人备注表数据
    LambdaQueryWrapper<CustomerContactRemarkEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.in(CustomerContactRemarkEntity::getContactId, idList);
    customerContactRemarkMapper.delete(queryWrapper);

    // 2. 删除所有关联的外键数据（使用配置的关联关系）
    // 资源类型：2-联系人（对应DataShareEntity的resourceType）
    foreignKeyDeleteUtil.deleteCascade(CustomerContactEntity.class, idList, 2);

    // 3. 物理删除联系人本身
    return customerContactMapper.deleteBatchIds(idList);
  }

  @Privacy
  public List<CustomerContactVO> getContactByCompanyId(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // 查询公司名称
    String companyName = dataConvertService.getCompanyName(id);

    LambdaQueryWrapper<CustomerContactEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper
        .eq(CustomerContactEntity::getCompanyId, id)
        .eq(CustomerContactEntity::getIsDeleted, false);

    List<CustomerContactEntity> customerContactEntityList = baseMapper.selectList(queryWrapper);
    List<CustomerContactVO> customerContactVOList;
    if (customerContactEntityList.isEmpty()) {
      customerContactVOList = List.of();
    } else {
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
            List<CustomerContactRemarkVO> remarkVOList =
                CustomerContactRemarkVO.fromEntity(entities);
            String creatorName = creatorNameMap.get(entity.getCreatorId());
            CustomerContactVO vo =
                CustomerContactVO.fromEntity(entity, companyName, creatorName, remarkVOList);
            built.add(vo);
          });
      customerContactVOList = built;
    }

    return customerContactVOList;
  }

  @Override
  public byte[] getExcel() {

    List<CustomerContactExcel> list = new ArrayList<>();

    List<CustomerContactEntity> entities =
        list(
            new LambdaQueryWrapper<CustomerContactEntity>()
                .eq(CustomerContactEntity::getIsDeleted, false));

    if (entities.isEmpty()) {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // 批量收集ID后统一查询
    Set<Long> creatorIds = new HashSet<>();
    Set<Long> companyIds = new HashSet<>();
    for (CustomerContactEntity entity : entities) {
      if (entity.getCreatorId() != null) creatorIds.add(entity.getCreatorId());
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
    }
    Map<Long, String> creatorNameMap = dataConvertService.getUserNames(creatorIds);
    Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);

    entities.forEach(
        entity -> {
          CustomerContactExcel excel = new CustomerContactExcel();
          BeanUtils.copyProperties(entity, excel);

          // 公司名称、性别、关系等级
          excel.setCreateName(creatorNameMap.get(entity.getCreatorId()));
          excel.setGender(entity.getGender() == null ? "" : (entity.getGender() == 0 ? "男" : "女"));
          excel.setCompanyName(companyNameMap.getOrDefault(entity.getCompanyId(), ""));

          // 客户关系等级（数字转字符串）
          if (entity.getRelationLevel() != null) {
            excel.setRelationLevel(String.valueOf(entity.getRelationLevel()));
          }

          // 查询该联系人的所有备注
          List<CustomerContactRemarkEntity> remarks =
              customerContactRemarkMapper.selectByContactId(entity.getId());

          // 获取喜好备注（类型 1）
          String hobbyRemark =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 1) // 1-喜好
                  .map(CustomerContactRemarkEntity::getRemarkContent)
                  .filter(content -> content != null && !content.trim().isEmpty())
                  .findFirst()
                  .orElse(null);
          excel.setHobbyRemark(hobbyRemark);

          // 获取住址备注（类型 2）
          String addressRemark =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 2) // 2-住址
                  .map(CustomerContactRemarkEntity::getRemarkContent)
                  .filter(content -> content != null && !content.trim().isEmpty())
                  .findFirst()
                  .orElse(null);
          excel.setAddressRemark(addressRemark);

          // 获取本人出生日期（类型 3）
          String selfBirthday =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 3) // 3-本人出生日期
                  .findFirst()
                  .map(r -> r.getRemarkDate().toString())
                  .orElse(null);
          excel.setSelfBirthday(selfBirthday);

          // 聚合亲属信息（类型 4），格式：姓名：日期;姓名:日期
          String relativeInfos =
              remarks.stream()
                  .filter(r -> r.getRemarkType() == 4) // 4-亲属出生日期
                  .filter(r -> r.getRemarkName() != null && r.getRemarkDate() != null)
                  .map(r -> r.getRemarkName() + ":" + r.getRemarkDate())
                  .collect(Collectors.joining(";"));
          excel.setRelativeInfo(relativeInfos);

          list.add(excel);
        });

    return aotoExcelUntil.AOTOExcelByStream(list, "客户联系人");
  }

  @Override
  @Privacy
  public Page<CustomerContactVO> getCustomerContactByCompanyId(Integer pageNum, Integer pageSize) {
    if (pageNum == null || pageSize == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    Page<CustomerContactEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<CustomerContactEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(CustomerContactEntity::getIsDeleted, false);
    baseMapper.selectPage(page, queryWrapper);
    List<CustomerContactEntity> records = page.getRecords();
    List<CustomerContactVO> customerContactVOList = new ArrayList<>();
    records.forEach(
        entity -> {
          CustomerContactVO vo = new CustomerContactVO();
          BeanUtils.copyProperties(entity, vo);
          customerContactVOList.add(vo);
        });

    Page<CustomerContactVO> ans = new Page<>(pageNum, pageSize);
    BeanUtils.copyProperties(page, ans);
    ans.setRecords(customerContactVOList);

    return ans;
  }

  @Override
  @Privacy
  public Page<CustomerContactVO> search(
      CustomerContactDTO customerContactDTO, Integer pageNum, Integer pageSize) {
    LambdaQueryWrapper<CustomerContactEntity> queryWrapper = new LambdaQueryWrapper<>();
    if (customerContactDTO.getCompanyId() != null) {
      queryWrapper.eq(CustomerContactEntity::getCompanyId, customerContactDTO.getCompanyId());
    }
    if (customerContactDTO.getCompanyName() != null
        && !customerContactDTO.getCompanyName().isEmpty()) {
      // 先按公司名称模糊匹配出公司ID，再过滤联系人（公司表无匹配则返回空结果）
      List<Long> companyIds =
          customerCompanyMapper
              .selectList(
                  new LambdaQueryWrapper<CustomerCompanyEntity>()
                      .like(
                          CustomerCompanyEntity::getCompanyName,
                          customerContactDTO.getCompanyName())
                      .eq(CustomerCompanyEntity::getIsDeleted, false))
              .stream()
              .map(CustomerCompanyEntity::getId)
              .collect(Collectors.toList());
      if (companyIds.isEmpty()) {
        return new Page<>(pageNum, pageSize);
      }
      queryWrapper.in(CustomerContactEntity::getCompanyId, companyIds);
    }
    if (customerContactDTO.getName() != null && !customerContactDTO.getName().isEmpty()) {
      queryWrapper.like(CustomerContactEntity::getName, customerContactDTO.getName());
    }
    if (customerContactDTO.getPosition() != null && !customerContactDTO.getPosition().isEmpty()) {
      queryWrapper.like(CustomerContactEntity::getPosition, customerContactDTO.getPosition());
    }
    if (customerContactDTO.getDept() != null && !customerContactDTO.getDept().isEmpty()) {
      queryWrapper.like(CustomerContactEntity::getDept, customerContactDTO.getDept());
    }
    if (customerContactDTO.getPhone() != null && !customerContactDTO.getPhone().isEmpty()) {
      queryWrapper.like(CustomerContactEntity::getPhone, customerContactDTO.getPhone());
    }
    if (customerContactDTO.getMobile() != null && !customerContactDTO.getMobile().isEmpty()) {
      queryWrapper.like(CustomerContactEntity::getMobile, customerContactDTO.getMobile());
    }
    if (customerContactDTO.getEmail() != null && !customerContactDTO.getEmail().isEmpty()) {
      queryWrapper.like(CustomerContactEntity::getEmail, customerContactDTO.getEmail());
    }
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
    Page<CustomerContactEntity> page = new Page<>(pageNum, pageSize);
    baseMapper.selectPage(page, queryWrapper);
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

  @Override
  @Privacy
  public CustomerContactVO get(Long id) {
    // 获取联系人
    // 用 selectById 而非 getOne(wrapper)：避免 QueryWrapperAspect 数据权限切面过滤掉
    // 协助人可见的关联联系人（可见性由 AssistScopeService 放行）
    CustomerContactEntity entity = customerContactMapper.selectById(id);
    CustomerContactVO vo = null;
    if (entity != null && !Boolean.TRUE.equals(entity.getIsDeleted())) {
      boolean assistRelated = businessRecordAccessService.assertCanReadContact(entity);
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
      vo =
          CustomerContactVO.fromEntity(
              entity, companyName, creatorName, CustomerContactRemarkVO.fromEntity(remarkEntities));
      // 对象级授权已经确认当前用户与该联系人有关，详情按本人规则展示
      Long currentId = BaseUnit.getCurrentId();
      if (currentId != null && assistRelated) {
        vo.setRelatedUserIds(Set.of(currentId));
      }
    }
    return vo;
  }

  @Override
  public GroupResultVO groupByCustomerContact(String field) {

    // 检查字段是否有效
    if (!Arrays.asList("companyId", "gender", "creatorId", "position", "dept").contains(field)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    LambdaQueryWrapper<CustomerContactEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(CustomerContactEntity::getIsDeleted, false);

    List<CustomerContactEntity> entities = customerContactMapper.selectList(queryWrapper);
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

  private Object getGroupKey(CustomerContactEntity entity, String field) {
    return switch (field) {
      case "companyId" -> entity.getCompanyId();
      case "gender" -> entity.getGender();
      case "creatorId" -> entity.getCreatorId();
      case "position" -> entity.getPosition();
      case "dept" -> entity.getDept();
      default -> null;
    };
  }

  @Override
  public byte[] template() {
    List<GetCustomerContactExcel> customerContactExcels = new ArrayList<>();
    customerContactExcels.add(new GetCustomerContactExcel());
    return aotoExcelUntil.AOTOExcelByStream(
        customerContactExcels, "customer_contact_template.xlsx");
  }

  @Override
  public void cascadeDeleteByCompanyId(Long companyId) {
    LambdaUpdateWrapper<CustomerContactEntity> wrapper = new LambdaUpdateWrapper<>();
    wrapper
        .eq(CustomerContactEntity::getCompanyId, companyId)
        .eq(CustomerContactEntity::getIsDeleted, false)
        .set(CustomerContactEntity::getIsDeleted, true);
    update(wrapper);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer logicalDeleteById(Long id) {
    return foreignKeyDeleteUtil.logicalDeleteByCascade(CustomerContactEntity.class, id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer batchLogicalDeleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    Integer count = 0;
    for (Long id : idList) {
      count += logicalDeleteById(id);
    }
    return count;
  }

  @Override
  public List<UserVO> getAuditor() {

    Long id = PermissionOperates.SALES_APPROVE_STAGE_ADVANCE.getId();

    List<RolePermissionsEntity> rolePermissionsEntities =
        rolePermissionsMapper.selectList(
            new LambdaQueryWrapper<RolePermissionsEntity>()
                .eq(RolePermissionsEntity::getPermissionsId, id));
    List<Long> list =
        rolePermissionsEntities.stream().map(RolePermissionsEntity::getRoleId).toList();

    Map<Long, String> roleNameMap = new HashMap<>();

    if (!list.isEmpty()) {
      roleMapper
          .selectList(new LambdaQueryWrapper<RoleEntity>().in(RoleEntity::getId, list))
          .forEach(
              role -> {
                roleNameMap.put(role.getId(), role.getRoleName());
              });
    }

    LambdaQueryWrapper<UserEntity> in = new LambdaQueryWrapper<UserEntity>();

    if (!list.isEmpty()) {
      in.in(UserEntity::getRoleId, list);
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

  @Override
  public List<String> getBirthdayReminderMessage(Long contactId) {

    if (!birthdayReminderProperties.isEnabled()) {
      throw new BaseException(ErrorCode.NOT_ENABLED, "生日提醒功能未启用");
    }

    // 1. 查询联系人信息
    CustomerContactEntity entity =
        getOne(
            new LambdaQueryWrapper<CustomerContactEntity>()
                .eq(CustomerContactEntity::getId, contactId)
                .eq(CustomerContactEntity::getIsDeleted, false));

    if (entity == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "联系人不存在");
    }

    // 2. 获取称呼和性别
    String title;
    if (entity.getGender() == null) {
      title = "";
    } else {
      title = entity.getGender() == 1 ? "先生" : "女士";
    }
    String contactName = entity.getName();

    // 3. 查询联系人的所有生日备注（类型 3-本人，类型 4-亲属）
    List<CustomerContactRemarkEntity> remarks =
        customerContactRemarkMapper.selectByContactId(contactId);
    if (remarks == null || remarks.isEmpty()) {
      return List.of();
    }

    // 4. 筛选出有出生日期的备注
    LocalDate today = LocalDate.now();
    List<BirthdayInfoDTO> birthdayInfos = new ArrayList<>();

    for (CustomerContactRemarkEntity remark : remarks) {
      if (remark.getRemarkType() == 3 || remark.getRemarkType() == 4) {
        LocalDate birthday = remark.getRemarkDate();
        if (birthday != null) {
          BirthdayInfoDTO info = new BirthdayInfoDTO();
          info.setIsSelf(remark.getRemarkType() == 3);
          info.setName(remark.getRemarkType() == 3 ? contactName : remark.getRemarkName());
          info.setBirthday(birthday);

          // 计算今年的生日日期
          LocalDate upcomingBirthday = calculateUpcomingBirthday(birthday);
          info.setUpcomingBirthday(upcomingBirthday);

          // 计算距离生日还有几天
          long daysUntil = ChronoUnit.DAYS.between(today, upcomingBirthday);
          info.setDaysUntil((int) daysUntil);

          birthdayInfos.add(info);
        }
      }
    }

    // 5. 按天数排序，最近的在前面
    birthdayInfos.sort((a, b) -> a.getDaysUntil() - b.getDaysUntil());

    // 6. 根据配置的天数过滤，只保留指定天数内的生日
    int reminderDays = birthdayReminderProperties.getDays();
    List<BirthdayInfoDTO> filteredInfos =
        birthdayInfos.stream()
            .filter(info -> info.getDaysUntil() >= 0 && info.getDaysUntil() <= reminderDays)
            .toList();

    // 7. 生成提醒消息列表，每个人对应一条消息
    if (filteredInfos.isEmpty()) {
      return List.of();
    } else {
      List<String> messages = new ArrayList<>();
      for (BirthdayInfoDTO info : filteredInfos) {
        messages.add(buildSingleMessage(contactName, title, info));
      }
      return messages;
    }
  }

  /** 构建单条提醒消息 */
  private String buildSingleMessage(String contactName, String title, BirthdayInfoDTO item) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("尊敬的%s%s，", contactName, title));

    if (item.getIsSelf()) {
      // 本人生日
      sb.append(String.format("您的生日还有%d天，", item.getDaysUntil()));
      if (item.getDaysUntil() == 0) {
        sb.append("祝您生日快乐！");
      } else {
        sb.append("提前祝您生日快乐！");
      }
    } else {
      // 亲属生日
      sb.append(String.format("您的亲属%s的生日还有%d天，", item.getName(), item.getDaysUntil()));
      if (item.getDaysUntil() == 0) {
        sb.append("记得送上祝福哦！");
      } else {
        sb.append("别忘了准备礼物和祝福！");
      }
    }

    return sb.toString();
  }

  /** 计算今年的生日日期（处理闰年 2 月 29 日） 如果是闰年 2 月 29 日出生，非闰年则按 2 月 28 日处理 */
  private LocalDate calculateUpcomingBirthday(LocalDate birthday) {
    int currentYear = Year.now().getValue();
    LocalDate upcomingBirthday = calculateBirthdayForYear(birthday, currentYear);

    // 如果今年的生日已经过了，计算明年的生日
    if (upcomingBirthday.isBefore(LocalDate.now())) {
      upcomingBirthday = calculateBirthdayForYear(birthday, currentYear + 1);
    }

    return upcomingBirthday;
  }

  /**
   * 计算指定年份的生日日期（处理闰年 2 月 29 日）
   *
   * @param birthday 原始出生日期
   * @param year 目标年份
   * @return 该年份的生日日期
   */
  private LocalDate calculateBirthdayForYear(LocalDate birthday, int year) {
    boolean isLeapYearBirthday = birthday.getMonthValue() == 2 && birthday.getDayOfMonth() == 29;
    boolean isTargetYearLeapYear = Year.of(year).isLeap();

    LocalDate result;
    if (isLeapYearBirthday && !isTargetYearLeapYear) {
      // 闰年出生但在非闰年，返回 2 月 28 日
      result = LocalDate.of(year, 2, 28);
    } else {
      // 其他情况直接设置年份
      result = birthday.withYear(year);
    }
    return result;
  }
}
