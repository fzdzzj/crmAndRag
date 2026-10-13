package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.AOTOExcelUntil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.common.untils.ValidationUtils;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.dto.CustomerMergeDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
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

  /** 客户语义向量摄取（add-customer-semantic-retrieval 任务 2.3 hook 依赖；缺失时 hook 整体跳过）。 */
  @Autowired(required = false)
  private CustomerIngestionService customerIngestionService;

  @Override
  public CustomerCompanyVO add(CustomerCompanyDTO customerCompanyDTO) {

    if (customerCompanyDTO.getPhone() != null && !customerCompanyDTO.getPhone().isBlank()) {

      isValid(customerCompanyDTO.getPhone());
    }
    // 校验公司名+部门组合唯一（部门可空，同名不同部门允许）
    CustomerCompanySupport.validateCompanyNameDeptUnique(
        customerCompanyDTO, null, customerCompanyMapper);

    CustomerCompanyEntity customerCompanyEntity = new CustomerCompanyEntity();

    // 数据验证：客户属性和等级
    CustomerCompanySupport.validateCustomerTypeAndGrade(customerCompanyDTO);

    BeanUtils.copyProperties(customerCompanyDTO, customerCompanyEntity);
    // 部门规范化：空串视为未填写，避免库里同时存在 null 与 ""
    customerCompanyEntity.setDept(
        CustomerCompanySupport.normalizeDept(customerCompanyDTO.getDept()));

    Long currentId = BaseUnit.getCurrentId();

    customerCompanyEntity.setCreatorId(currentId);
    customerCompanyEntity.setIsDeleted(false);

    // 处理客户等级字段（所有用户都可以配置）
    if (customerCompanyDTO.getGrade() == null) {
      customerCompanyEntity.setGrade(0);
    }

    CustomerCompanyVO result;
    try {
      if (!save(customerCompanyEntity)) {
        result = null;
      } else {
        CustomerCompanyVO created = new CustomerCompanyVO();
        created.setId(customerCompanyEntity.getId());
        created.setCompanyName(customerCompanyEntity.getCompanyName());
        result = created;
      }
    } catch (DuplicateKeyException e) {
      // 并发写数据库唯一约束 uk_company_name_dept 兑底（正常路径已由 Java 判重拦截）
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "公司名称与部门组合已存在，请勿重复添加");
    }
    if (result != null) {
      // add-customer-semantic-retrieval 任务 2.3：新增成功同步 upsert 语义向量
      reingestAfterCommit(customerCompanyEntity.getId(), "新增客户公司");
    }
    return result;
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
      CustomerCompanySupport.ImportResult parsed =
          CustomerCompanySupport.readImport(inputStream, userMapper, baseMapper);
      dataList = parsed.data();
      rowCount = parsed.rowCount();
    } catch (IOException e) {
      log.error("Excel文件读取失败", e);
      throw new BaseException(ErrorCode.FILE_FORMAT_ERROR, "文件读取失败: " + e.getMessage());
    }

    int result;
    if (dataList == null || dataList.isEmpty()) {
      // Excel 中有数据但全部已存在（或本次重复），视为成功导入 0 条，而不是报“数据为空”
      if (rowCount > 0) {
        result = 0;
      } else {
        throw new BaseException(ErrorCode.COMPANY_DATA_EMPTY);
      }
    } else {
      try {
        saveBatch(dataList);
      } catch (DuplicateKeyException e) {
        // 并发/重复导入时数据库唯一约束兑底
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "导入数据中公司名称与部门组合已存在，请勿重复导入");
      }
      // add-customer-semantic-retrieval 任务 2.3：导入成功批量 upsert 语义向量
      // （dataList 为非终态局部变量，lambda 捕获需 effectively final 别名）
      List<CustomerCompanyEntity> importedList = dataList;
      runAfterCommit(
          "导入客户公司",
          () -> {
            for (CustomerCompanyEntity imported : importedList) {
              reingestById(imported.getId());
            }
          });
      result = dataList.size();
    }
    return result;
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
    List<CustomerCompanyEntity> customerCompanyEntityList =
        CustomerCompanySupport.buildUpdateEntities(customerCompanyDTOList, customerCompanyMapper);

    updateBatchById(customerCompanyEntityList);

    // add-customer-semantic-retrieval 任务 2.3：更新成功批量刷新语义向量（回查完整实体防 DTO 部分字段丢键）
    runAfterCommit(
        "批量更新客户公司",
        () -> {
          for (CustomerCompanyEntity updated : customerCompanyEntityList) {
            reingestById(updated.getId());
          }
        });

    return customerCompanyEntityList.size();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "companyName", allEntries = true)
  public Integer deleteOrRecoverByIds(List<Long> idList, Boolean isRecover) {

    List<CustomerCompanyEntity> customerCompanyEntityList =
        CustomerCompanySupport.buildRecoverEntities(
            idList, isRecover, this::getById, customerCompanyMapper);

    try {
      updateBatchById(customerCompanyEntityList);
    } catch (DuplicateKeyException e) {
      // 恢复时正常记录中已存在同公司同部门，数据库唯一约束兑底
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "恢复失败：公司名称与部门组合已存在正常记录中");
    }

    // add-customer-semantic-retrieval 任务 2.3：删除(isRecover=true→is_deleted=1)连带删向量；恢复回查后 upsert
    runAfterCommit(
        "删除/恢复客户公司",
        () -> {
          for (Long id : idList) {
            if (Boolean.TRUE.equals(isRecover)) {
              customerIngestionService.deleteByCompanyId(id);
            } else {
              reingestById(id);
            }
          }
        });

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
    int deleted = customerCompanyMapper.deleteBatchIds(idList);

    // add-customer-semantic-retrieval 任务 2.3：物理删除连带删向量
    runAfterCommit("物理删除客户公司", () -> idList.forEach(customerIngestionService::deleteByCompanyId));
    return deleted;
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

    // add-customer-semantic-retrieval 任务 2.3：逻辑删除连带删向量（本方法无事务，立即同步）
    runAfterCommit("逻辑删除客户公司", () -> customerIngestionService.deleteByCompanyId(id));

    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer logicalDeleteById(Long id) {
    Integer deleted = foreignKeyDeleteUtil.logicalDeleteByCascade(CustomerCompanyEntity.class, id);
    // add-customer-semantic-retrieval 任务 2.3：级联逻辑删除连带删向量（batchLogicalDeleteByIds 循环
    // 复用本 hook，同一事务逐个注册 afterCommit，不再单独挂批量 hook 避免重复删除）
    runAfterCommit("级联逻辑删除客户公司", () -> customerIngestionService.deleteByCompanyId(id));
    return deleted;
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
    return CustomerCompanySupport.getCompanyByCondition(
        id, customerContactMapper, customerCompanyMapper, dataConvertService);
  }

  @Override
  public byte[] excel() {
    List<CustomerCompanyEntity> entities =
        list(
            new LambdaQueryWrapper<CustomerCompanyEntity>()
                .eq(CustomerCompanyEntity::getIsDeleted, false));

    if (entities.isEmpty()) {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    return CustomerCompanySupport.buildExcelBytes(entities, dataConvertService, aotoExcelUntil);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "companyName", key = "#customerMergeDTO.mergedCompanyId")
  public Boolean merge(CustomerMergeDTO customerMergeDTO) {
    CustomerCompanySupport.applyMerge(
        customerMergeDTO, customerCompanyMapper, customerMergeLogMapper);
    // add-customer-semantic-retrieval 任务 2.3：被合并方已标 is_deleted=1，连带删向量（目标公司不变不重嵌）
    Long mergedCompanyId = customerMergeDTO.getMergedCompanyId();
    if (mergedCompanyId != null) {
      runAfterCommit("合并客户公司", () -> customerIngestionService.deleteByCompanyId(mergedCompanyId));
    }
    return true;
  }

  @Override
  @Privacy
  public Page<CustomerCompanyVO> getAllCompany(Integer pageNum, Integer pageSize) {

    Page<CustomerCompanyEntity> page = new Page<>(pageNum, pageSize);
    Page<CustomerCompanyEntity> pageResult =
        page(
            page,
            new LambdaQueryWrapper<CustomerCompanyEntity>()
                .eq(CustomerCompanyEntity::getIsDeleted, false));

    return CustomerCompanySupport.toVoPage(pageResult, dataConvertService);
  }

  @Override
  @Privacy
  public CustomerCompanyVO getCompanyDetail(Long id) {
    return CustomerCompanySupport.getCompanyDetail(
        id, this::getById, businessRecordAccessService, dataConvertService);
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
    return CustomerCompanySupport.toVoPage(page(page, wrapper), dataConvertService);
  }

  @Override
  @Privacy
  public Page<CustomerCompanyVO> customQuery(
      CustomerCompanyDTO customerCompanyDTO, Integer pageNum, Integer pageSize) {
    if (pageNum == null || pageSize == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    Page<CustomerCompanyEntity> page = new Page<>(pageNum, pageSize);
    // wrapper 装配拆入 QuerySupport（add-customer-semantic-retrieval 任务 4.3 NcssCount，行为等价）
    return CustomerCompanySupport.toVoPage(
        page(page, CustomerCompanyQuerySupport.customQueryWrapper(customerCompanyDTO)),
        dataConvertService);
  }

  @Override
  public GroupResultVO groupByCustomerCompany(String field) {
    // 校验与 wrapper 装配拆入 QuerySupport（add-customer-semantic-retrieval 任务 4.3 NcssCount，行为等价）
    List<CustomerCompanyEntity> entities =
        customerCompanyMapper.selectList(CustomerCompanyQuerySupport.groupFieldQuery(field));
    return CustomerCompanySupport.buildGroupResult(entities, field, dataConvertService);
  }

  @Override
  public byte[] template() {
    return CustomerCompanyQuerySupport.buildTemplateBytes(aotoExcelUntil);
  }

  @Override
  public Page<CustomerCompanyVO> findCompanyByGroup(
      String belongGroup, Integer pageNum, Integer pageSize) {
    return CustomerCompanySupport.toVoPage(
        page(
            new Page<>(pageNum, pageSize),
            CustomerCompanyQuerySupport.belongGroupQuery(belongGroup)),
        dataConvertService);
  }

  /**
   * 语义向量同步闸（add-customer-semantic-retrieval 任务 2.3）：依赖未装配（部分单测场景）整体跳过； 事务与异常语义委托 {@link
   * CustomerSemanticHookSupport#runAfterCommit}（任务 4.3 NcssCount 拆分）。
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
   * 回查完整实体后 upsert 语义向量（DTO 部分字段更新场景防丢字段）；实体缺失或已删转删向量。 仅可在 runAfterCommit 的 action 内调用（异常由闸捕获）。
   *
   * @param id 客户公司主键
   */
  private void reingestById(Long id) {
    CustomerSemanticHookSupport.reingestById(id, customerIngestionService, this::getById);
  }

  /**
   * 新增场景 hook：回查完整实体（DTO 可能未含全部语义字段）后 upsert。
   *
   * @param id 新增实体主键
   * @param scene 场景描述
   */
  private void reingestAfterCommit(Long id, String scene) {
    runAfterCommit(scene, () -> reingestById(id));
  }
}
