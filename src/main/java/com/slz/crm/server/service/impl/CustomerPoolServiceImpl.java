package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.pojo.dto.CustomerPoolAssignDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerPoolLogEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerPoolLogVO;
import com.slz.crm.server.constant.CustomerPoolConstant;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerPoolLogMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.CustomerPoolService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户公海池业务服务实现（add-customer-public-sea-pool 任务 2.x）。
 *
 * <p>流转状态机：私海（pool_status=0，owner 非空）⇄ 公海（pool_status=1，owner 为空）。 认领/分派从公海取回私海并校验保有量配额（默认
 * 50）；退回释放回公海；每次流转事务后经 {@link CustomerSemanticHookSupport} 异步同步语义向量，保证 Qdrant 权限锚与所有者一致。
 */
@Slf4j
@Service
public class CustomerPoolServiceImpl implements CustomerPoolService {

  @Autowired private CustomerCompanyMapper customerCompanyMapper;

  @Autowired private CustomerPoolLogMapper customerPoolLogMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private SysDeptMapper sysDeptMapper;

  /** 客户语义向量摄取（依赖缺失时 hook 整体跳过，与 CustomerCompanyServiceImpl 同口径）。 */
  @Autowired(required = false)
  private CustomerIngestionService customerIngestionService;

  /** 客户语义向量异步任务执行器（异步非阻塞核心依赖）。 */
  @Autowired
  @Qualifier("embeddingTaskExecutor")
  private Executor embeddingTaskExecutor;

  @Override
  public Page<CustomerCompanyVO> queryPool(
      Integer pageNum, Integer pageSize, String companyName, String industry, Integer grade) {
    if (pageNum == null || pageNum <= 0) {
      pageNum = 1;
    }
    if (pageSize == null || pageSize <= 0) {
      pageSize = 10;
    }
    Page<CustomerCompanyEntity> page = new Page<>(pageNum, pageSize);
    Page<CustomerCompanyEntity> result =
        customerCompanyMapper.selectPoolPage(page, companyName, industry, grade);

    Page<CustomerCompanyVO> voPage = new Page<>(pageNum, pageSize, result.getTotal());
    List<CustomerCompanyVO> records = new ArrayList<>();
    for (CustomerCompanyEntity entity : result.getRecords()) {
      records.add(toCompanyVo(entity));
    }
    voPage.setRecords(records);
    return voPage;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean claim(Long id) {
    Long currentId = requireLogin();
    assertQuotaNotExceeded(currentId);

    CustomerCompanyEntity company = getCompany(id);
    assertInPublicPool(company, "认领");

    Long previousOwner = company.getOwnerId();
    CustomerCompanyEntity update = new CustomerCompanyEntity();
    update.setId(id);
    update.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PRIVATE);
    update.setOwnerId(currentId);
    update.setPoolReleaseTime(null);
    update.setPoolReleaseReason(null);
    customerCompanyMapper.updateById(update);

    insertLog(
        id, currentId, CustomerPoolConstant.ACTION_CLAIM, previousOwner, currentId, "业务员自助认领");
    runAfterCommit("公海客户认领", id);
    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean release(Long id, String reason) {
    Long currentId = requireLogin();
    CustomerCompanyEntity company = getCompany(id);
    if (Boolean.TRUE.equals(company.getIsDeleted())) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS, "客户不存在或已删除");
    }
    if (!isOwner(currentId, company) && !isPoolManager(currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "只能退回自己负责的客户或由主管操作");
    }
    Long previousOwner = company.getOwnerId();
    CustomerCompanyEntity update = new CustomerCompanyEntity();
    update.setId(id);
    update.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PUBLIC);
    update.setOwnerId(null);
    update.setPoolReleaseTime(LocalDateTime.now());
    update.setPoolReleaseReason(reason == null || reason.isBlank() ? "销售主动放弃" : reason);
    customerCompanyMapper.updateById(update);

    insertLog(
        id,
        currentId,
        CustomerPoolConstant.ACTION_RELEASE,
        previousOwner,
        null,
        update.getPoolReleaseReason());
    runAfterCommit("客户退回公海", id);
    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean assign(CustomerPoolAssignDTO dto) {
    Long currentId = requireLogin();
    if (dto == null || dto.getCompanyId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "公司 ID 不能为空");
    }
    if (dto.getTargetUserId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "目标业务员不能为空");
    }
    if (!isPoolManager(currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "仅主管或管理员可执行分派");
    }
    assertQuotaNotExceeded(dto.getTargetUserId());

    Long id = dto.getCompanyId();
    CustomerCompanyEntity company = getCompany(id);
    assertInPublicPool(company, "分派");

    Long previousOwner = company.getOwnerId();
    CustomerCompanyEntity update = new CustomerCompanyEntity();
    update.setId(id);
    update.setPoolStatus(CustomerPoolConstant.POOL_STATUS_PRIVATE);
    update.setOwnerId(dto.getTargetUserId());
    update.setPoolReleaseTime(null);
    update.setPoolReleaseReason(null);
    customerCompanyMapper.updateById(update);

    insertLog(
        id,
        currentId,
        CustomerPoolConstant.ACTION_ASSIGN,
        previousOwner,
        dto.getTargetUserId(),
        dto.getReason());
    runAfterCommit("公海客户主管分派", id);
    return true;
  }

  @Override
  public List<CustomerPoolLogVO> queryLogs(Long companyId) {
    if (companyId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "公司 ID 不能为空");
    }
    List<CustomerPoolLogEntity> logs =
        customerPoolLogMapper.selectList(
            new LambdaQueryWrapper<CustomerPoolLogEntity>()
                .eq(CustomerPoolLogEntity::getCompanyId, companyId)
                .orderByDesc(CustomerPoolLogEntity::getCreateTime));
    return logs.stream().map(this::toLogVo).collect(Collectors.toList());
  }

  // ---------- 私有辅助 ----------

  /** 语义向量同步闸：依赖未装配（部分单测场景）整体跳过；事务与异常语义委托 {@link CustomerSemanticHookSupport}。 */
  private void runAfterCommit(String scene, Long companyId) {
    if (customerIngestionService != null && embeddingTaskExecutor != null) {
      CustomerSemanticHookSupport.runAfterCommitAsync(
          scene,
          embeddingTaskExecutor,
          () ->
              CustomerSemanticHookSupport.reingestById(
                  companyId, customerIngestionService, id -> customerCompanyMapper.selectById(id)));
    }
  }

  private Long requireLogin() {
    Long currentId = BaseUnit.getCurrentId();
    if (currentId == null) {
      throw new BaseException(ErrorCode.USER_NOT_LOGIN);
    }
    return currentId;
  }

  private CustomerCompanyEntity getCompany(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "公司 ID 不能为空");
    }
    CustomerCompanyEntity company = customerCompanyMapper.selectById(id);
    if (company == null || Boolean.TRUE.equals(company.getIsDeleted())) {
      throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS);
    }
    return company;
  }

  private void assertInPublicPool(CustomerCompanyEntity company, String scene) {
    if (company.getPoolStatus() == null
        || company.getPoolStatus() != CustomerPoolConstant.POOL_STATUS_PUBLIC) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "客户未在公海池中，无法" + scene);
    }
  }

  private void assertQuotaNotExceeded(Long userId) {
    Long holding =
        customerCompanyMapper.selectCount(
            new LambdaQueryWrapper<CustomerCompanyEntity>()
                .eq(CustomerCompanyEntity::getOwnerId, userId)
                .eq(CustomerCompanyEntity::getPoolStatus, CustomerPoolConstant.POOL_STATUS_PRIVATE)
                .eq(CustomerCompanyEntity::getIsDeleted, false));
    if (holding != null && holding >= CustomerPoolConstant.PRIVATE_POOL_QUOTA) {
      throw new BaseException(ErrorCode.CUSTOMER_POOL_QUOTA_EXCEEDED);
    }
  }

  private boolean isOwner(Long currentId, CustomerCompanyEntity company) {
    return company.getOwnerId() != null && company.getOwnerId().equals(currentId);
  }

  /** 公海池主管判定：超级管理员（roleId=1）或任一部门的 leader。 */
  private boolean isPoolManager(Long currentId) {
    boolean isManager;
    Long roleId = loadRoleId(currentId);
    if (roleId != null && roleId == 1L) {
      isManager = true;
    } else {
      Long ledDepts =
          sysDeptMapper.selectCount(
              new LambdaQueryWrapper<SysDeptEntity>().eq(SysDeptEntity::getLeaderId, currentId));
      isManager = ledDepts != null && ledDepts > 0;
    }
    return isManager;
  }

  private Long loadRoleId(Long userId) {
    UserEntity user = userMapper.selectById(userId);
    return user == null ? null : user.getRoleId();
  }

  private void insertLog(
      Long companyId,
      Long operatorId,
      String actionType,
      Long prevOwner,
      Long newOwner,
      String reason) {
    CustomerPoolLogEntity logEntity = new CustomerPoolLogEntity();
    logEntity.setCompanyId(companyId);
    logEntity.setOperatorId(operatorId);
    logEntity.setActionType(actionType);
    logEntity.setPreviousOwnerId(prevOwner);
    logEntity.setNewOwnerId(newOwner);
    logEntity.setReason(reason);
    customerPoolLogMapper.insert(logEntity);
  }

  private CustomerCompanyVO toCompanyVo(CustomerCompanyEntity entity) {
    String creatorName = loadUserName(entity.getCreatorId());
    String ownerName = loadUserName(entity.getOwnerId());
    return CustomerCompanyVO.fromEntity(entity, creatorName, ownerName);
  }

  private CustomerPoolLogVO toLogVo(CustomerPoolLogEntity entity) {
    CustomerPoolLogVO vo = new CustomerPoolLogVO();
    vo.setId(entity.getId());
    vo.setCompanyId(entity.getCompanyId());
    vo.setOperatorId(entity.getOperatorId());
    vo.setOperatorName(
        entity.getOperatorId() != null
                && entity.getOperatorId() == CustomerPoolConstant.SYSTEM_OPERATOR_ID
            ? "系统"
            : loadUserName(entity.getOperatorId()));
    vo.setActionType(entity.getActionType());
    vo.setPreviousOwnerId(entity.getPreviousOwnerId());
    vo.setNewOwnerId(entity.getNewOwnerId());
    vo.setReason(entity.getReason());
    vo.setCreateTime(entity.getCreateTime());
    return vo;
  }

  private String loadUserName(Long userId) {
    String name = null;
    if (userId != null) {
      UserEntity user = userMapper.selectById(userId);
      name = user == null ? null : user.getRealName();
    }
    return name;
  }
}
