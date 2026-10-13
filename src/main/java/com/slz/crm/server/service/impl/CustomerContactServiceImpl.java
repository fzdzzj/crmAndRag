package com.slz.crm.server.service.impl;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.excellistener.CustomerContactListener;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.dto.CustomerContactRemarkDTO;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.pojo.excel.CustomerContactExcel;
import com.slz.crm.pojo.excel.GetCustomerContactExcel;
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
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@SuppressWarnings("PMD.NcssCount")
// 豁免理由（add-customer-contact-retrieval 任务 4.1）：本类聚合 20+ 个联系人 CRUD/查询/导入/生日提醒方法，NCSS 随
// 联系人语义重摄取横切（5 处 reingest 触发 + 注入依赖）刚越过类级 150 报告线；再拆分会牺牲高内聚，对齐既有
// CustomerSemanticHookSupport 同级 suppression 先例（PMD NcssCount 类级豁免，行为等价）。
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

  /** 所属公司语义摄取（add-customer-contact-retrieval 任务 1.3 hook 依赖；缺失时 hook 整体跳过）。 */
  @Autowired(required = false)
  private CustomerIngestionService customerIngestionService;

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
      CustomerContactExcelSupport.saveImportedRemarks(
          listener.getRemarkList(), dataList, customerContactRemarkMapper);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // add-customer-contact-retrieval 任务 1.3：Excel 导入路径——导入后所属公司重摄取（联系人入语义段）
    CustomerContactReingestSupport.reingestForContacts(
        "导入客户联系人", dataList, customerIngestionService, customerCompanyMapper);

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
          CustomerContactRemarkValidator.validateRelationLevel(data.getRelationLevel());

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
        CustomerContactRemarkValidator.replaceRemarks(
            dto.getId(), dto.getRemarks(), creatorId, customerContactRemarkMapper);
      }
    }

    // add-customer-contact-retrieval 任务 1.3：修改路径——受影响所属公司批量重摄取（联系人字段入语义段）
    CustomerContactReingestSupport.reingestForContacts(
        "修改客户联系人", dataList, customerIngestionService, customerCompanyMapper);

    return list.size();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public CustomerContactVO insert(CustomerContactDTO customerContactDTO) {

    Long creatorId = BaseUnit.getCurrentId();

    // 验证关系等级
    CustomerContactRemarkValidator.validateRelationLevel(customerContactDTO.getRelationLevel());

    CustomerContactEntity entity = new CustomerContactEntity();
    BeanUtils.copyProperties(customerContactDTO, entity);
    entity.setCreatorId(creatorId);
    entity.setIsDeleted(false);
    CustomerContactVO created = null;
    if (baseMapper.insert(entity) > 0) {
      // 处理备注信息
      CustomerContactRemarkValidator.insertRemarks(
          entity.getId(), customerContactDTO.getRemarks(), creatorId, customerContactRemarkMapper);

      created = new CustomerContactVO();
      created.setId(entity.getId());
      created.setName(entity.getName());
    }
    // add-customer-contact-retrieval 任务 1.3：新增路径——所属公司重摄取（新增联系人入语义段）
    CustomerContactReingestSupport.reingestAfterCommit(
        "新增客户联系人",
        entity.getCompanyId() == null ? List.of() : List.of(entity.getCompanyId()),
        customerIngestionService,
        customerCompanyMapper);
    return created;
  }

  /**
   * 验证备注类型是否合法
   *
   * @param remark 备注 DTO
   */
  @Override
  public void validateRemarkType(CustomerContactRemarkDTO remark) {
    CustomerContactRemarkValidator.validateRemarkType(remark);
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

    // add-customer-contact-retrieval 任务 1.3：软删/恢复路径——受影响所属公司重摄取（软删联系人出语义段）
    CustomerContactReingestSupport.reingestForContacts(
        "软删/恢复客户联系人", dataList, customerIngestionService, customerCompanyMapper);

    return dataList.size();
  }

  @Transactional(rollbackFor = Exception.class)
  @Override
  @CacheEvict(value = "contactName", allEntries = true)
  public int deleteByIds(List<Long> idList) {

    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 物理删除前先收集受影响公司 id（删除后无法反查 company_id，用于联系人段重摄取）
    List<CustomerContactEntity> doomed = customerContactMapper.selectBatchIds(idList);

    // 1. 删除客户联系人备注表数据
    LambdaQueryWrapper<CustomerContactRemarkEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.in(CustomerContactRemarkEntity::getContactId, idList);
    customerContactRemarkMapper.delete(queryWrapper);

    // 2. 删除所有关联的外键数据（使用配置的关联关系）
    // 资源类型：2-联系人（对应DataShareEntity的resourceType）
    foreignKeyDeleteUtil.deleteCascade(CustomerContactEntity.class, idList, 2);

    // 3. 物理删除联系人本身
    int deleted = customerContactMapper.deleteBatchIds(idList);

    // add-customer-contact-retrieval 任务 1.3：物理删除路径——受影响所属公司重摄取（删除联系人出语义段）
    CustomerContactReingestSupport.reingestAfterCommit(
        "物理删除客户联系人",
        CustomerContactReingestSupport.distinctCompanyIds(doomed),
        customerIngestionService,
        customerCompanyMapper);

    return deleted;
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
      customerContactVOList =
          CustomerContactSearchSupport.buildCompanyContacts(
              dataConvertService,
              customerContactRemarkMapper,
              companyName,
              customerContactEntityList);
    }

    return customerContactVOList;
  }

  @Override
  public byte[] getExcel() {

    List<CustomerContactEntity> entities =
        list(
            new LambdaQueryWrapper<CustomerContactEntity>()
                .eq(CustomerContactEntity::getIsDeleted, false));

    if (entities.isEmpty()) {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    return aotoExcelUntil.AOTOExcelByStream(
        CustomerContactExcelSupport.buildExcelRows(
            entities, dataConvertService, customerContactRemarkMapper),
        "客户联系人");
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
    Page<CustomerContactEntity> page = new Page<>(pageNum, pageSize);
    // 公司表无匹配时结果必为空，跳过查询
    if (CustomerContactSearchSupport.applyCompanyFilter(
        customerCompanyMapper, queryWrapper, customerContactDTO.getCompanyName())) {
      CustomerContactSearchSupport.applyContactSearchFilters(queryWrapper, customerContactDTO);
      baseMapper.selectPage(page, queryWrapper);
    }
    return CustomerContactSearchSupport.buildContactSearchPage(
        dataConvertService, customerContactRemarkMapper, page, pageNum, pageSize);
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
      vo =
          CustomerContactSearchSupport.buildDetailVo(
              entity,
              dataConvertService,
              customerContactRemarkMapper,
              assistRelated,
              BaseUnit.getCurrentId());
    }
    return vo;
  }

  @Override
  public GroupResultVO groupByCustomerContact(String field) {

    // 检查字段是否有效
    if (!CustomerContactSearchSupport.isValidGroupField(field)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    LambdaQueryWrapper<CustomerContactEntity> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(CustomerContactEntity::getIsDeleted, false);

    List<CustomerContactEntity> entities = customerContactMapper.selectList(queryWrapper);

    return CustomerContactSearchSupport.buildGroupResult(
        dataConvertService, customerContactRemarkMapper, field, entities);
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
    return CustomerContactSearchSupport.buildAuditorOptions(
        userMapper,
        rolePermissionsMapper,
        roleMapper,
        PermissionOperates.SALES_APPROVE_STAGE_ADVANCE.getId());
  }

  @Override
  public List<String> getBirthdayReminderMessage(Long contactId) {

    List<String> result;
    if (!birthdayReminderProperties.isEnabled()) {
      throw new BaseException(ErrorCode.NOT_ENABLED, "生日提醒功能未启用");
    } else {
      // 1. 查询联系人信息
      CustomerContactEntity entity =
          getOne(
              new LambdaQueryWrapper<CustomerContactEntity>()
                  .eq(CustomerContactEntity::getId, contactId)
                  .eq(CustomerContactEntity::getIsDeleted, false));

      if (entity == null) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "联系人不存在");
      }

      result =
          CustomerContactBirthdaySupport.buildReminderMessages(
              entity,
              LocalDate.now(),
              birthdayReminderProperties.getDays(),
              customerContactRemarkMapper);
    }
    return result;
  }
}
