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
    reingestAfterCommit("导入客户联系人", distinctCompanyIds(dataList));

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
    reingestAfterCommit("修改客户联系人", distinctCompanyIds(dataList));

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
    if (entity.getCompanyId() != null) {
      reingestAfterCommit("新增客户联系人", List.of(entity.getCompanyId()));
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
    reingestAfterCommit("软删/恢复客户联系人", distinctCompanyIds(dataList));

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
    List<Long> companyIds = distinctCompanyIds(doomed);

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
    reingestAfterCommit("物理删除客户联系人", companyIds);

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

  /**
   * 提取联系人列表所属公司 id 去重集合（add-customer-contact-retrieval 任务 1.3；null/空返回空集合）。 用于批量重摄取去重，避免同一公司重复
   * upsert。
   *
   * @param contacts 联系人列表（可为 null/空）
   * @return 去重后的公司 id 集合（保序去重，LinkedHashSet 语义）
   */
  private List<Long> distinctCompanyIds(List<CustomerContactEntity> contacts) {
    if (contacts == null || contacts.isEmpty()) {
      return List.of();
    }
    Set<Long> companyIds = new LinkedHashSet<>();
    for (CustomerContactEntity c : contacts) {
      if (c.getCompanyId() != null) {
        companyIds.add(c.getCompanyId());
      }
    }
    return new ArrayList<>(companyIds);
  }

  /**
   * 所属公司语义向量同步闸（add-customer-contact-retrieval 任务 1.3）：依赖未装配（部分单测场景）整体跳过； 事务与异常语义委托 {@link
   * CustomerSemanticHookSupport#runAfterCommit}。无 embedding 配置的测试环境静默降级、不致既有 IT 失败。
   *
   * @param scene 场景描述（日志定位用）
   * @param action 向量同步动作
   */
  private void runAfterCommit(String scene, Runnable action) {
    if (customerIngestionService == null) {
      return;
    }
    CustomerSemanticHookSupport.runAfterCommit(scene, action);
  }

  /**
   * 回查完整公司实体后 upsert 语义向量（DTO 部分字段更新场景防丢字段；公司缺失或已删转删向量）。 仅可在 runAfterCommit 的 action 内调用（异常由闸捕获）。
   *
   * @param companyId 所属公司主键
   */
  private void reingestById(Long companyId) {
    CustomerSemanticHookSupport.reingestById(
        companyId, customerIngestionService, customerCompanyMapper::selectById);
  }

  /**
   * 联系人 CRUD 变更后的公司重摄取入口（add-customer-contact-retrieval 任务 1.3）：对去重后的受影响公司逐一 回查后 upsert。
   *
   * @param scene 场景描述
   * @param companyIds 受影响公司 id 集合（可为空）
   */
  private void reingestAfterCommit(String scene, List<Long> companyIds) {
    if (companyIds == null || companyIds.isEmpty()) {
      return;
    }
    runAfterCommit(
        scene,
        () -> {
          for (Long companyId : companyIds) {
            reingestById(companyId);
          }
        });
  }
}
