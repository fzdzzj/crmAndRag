package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
    if (!casToPrivate(id, currentId)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "客户已被他人认领或状态已变更，无法认领");
    }

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
    String releaseReason = reason == null || reason.isBlank() ? "销售主动放弃" : reason;
    // fix-minor-debt-family 任务 5.1：实体默认 NOT_NULL 更新策略下 updateById 置 null 是空操作，
    // 退回公海必须把 owner_id 真正清空，故与 casToPrivate 同型改用显式 UpdateWrapper set null。
    LambdaUpdateWrapper<CustomerCompanyEntity> releaseUpdate =
        new LambdaUpdateWrapper<CustomerCompanyEntity>()
            .set(CustomerCompanyEntity::getPoolStatus, CustomerPoolConstant.POOL_STATUS_PUBLIC)
            .set(CustomerCompanyEntity::getOwnerId, null)
            .set(CustomerCompanyEntity::getPoolReleaseTime, LocalDateTime.now())
            .set(CustomerCompanyEntity::getPoolReleaseReason, releaseReason)
            .eq(CustomerCompanyEntity::getId, id)
            .eq(CustomerCompanyEntity::getIsDeleted, false);
    customerCompanyMapper.update(null, releaseUpdate);

    insertLog(
        id, currentId, CustomerPoolConstant.ACTION_RELEASE, previousOwner, null, releaseReason);
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
    // fix-minor-debt-family 任务 4.1：目标用户存在性 + 在职校验前置于配额行锁，
    // 既消除「分派给幽灵用户」，也避免 FOR UPDATE 命中不存在行退化为 gap 锁。
    UserEntity targetUser = userMapper.selectById(dto.getTargetUserId());
    CustomerPoolTargetUserGuard.assertAssignable(targetUser);
    assertQuotaNotExceeded(dto.getTargetUserId());

    Long id = dto.getCompanyId();
    CustomerCompanyEntity company = getCompany(id);
    assertInPublicPool(company, "分派");

    Long previousOwner = company.getOwnerId();
    if (!casToPrivate(id, dto.getTargetUserId())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "客户已被他人认领或状态已变更，无法分派");
    }

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

  /**
   * 私海保有量配额闸（fix-pool-claim-race 任务 1.2 原子化收口）：先以 {@code SELECT ... FOR UPDATE} 锁定目标用户行，
   * 再计数校验。仅锁用户行即可让同一业务员的并发认领/分派在配额检查处串行化——后到者必然在先到者提交后才执行 计数，从而读到先到者新增的私海持有量，杜绝「N 个并发都读到 holding=49
   * 全放行」突破 50 上限。
   *
   * <p>锁序统一为「先锁 sys_user 行，再改 customer_company 行」，与回池/自动回收路径无反向锁序，规避死锁。 持有行 FOR UPDATE 与 UPDATE
   * 内嵌计数子查询两个备选被否决：前者锁不住并发新认领的行（不在持有行锁集内）， 后者派生表在 RR 下走快照读同样不串行化。
   *
   * <p>分派路径的目标用户存在性由 {@link CustomerPoolTargetUserGuard#assertAssignable} 先行保证，本方法只负责配额锁与计数； 认领路径的
   * userId 来自登录态，恒存在。
   */
  private void assertQuotaNotExceeded(Long userId) {
    userMapper.selectList(
        new LambdaQueryWrapper<UserEntity>()
            .select(UserEntity::getId)
            .eq(UserEntity::getId, userId)
            .last("FOR UPDATE"));
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

  /**
   * 认领/分派共用的 CAS 收口（fix-pool-claim-race 任务 1.2）：单条条件 UPDATE 把「读-判-写」压缩为原子语句， WHERE 公海态谓词（id +
   * pool_status=公海 + 未删除）在写入时刻复验，并发后到者 affected=0，由调用方抛 「已被认领/状态已变更」业务错误（错误码复用 {@link
   * #assertInPublicPool} 的 PARAM_FORMAT_ERROR 语义）。
   *
   * <p>SET 显式置空 pool_release_time/pool_release_reason：实体默认 NOT_NULL 更新策略下 updateById 置 null
   * 是空操作，此处按流转状态机「公海→私海清除退回痕迹」的既有语义真正落库清除。
   *
   * @return true=抢占成功（affected=1）；false=客户已被并发认领或状态已变更（affected=0）
   */
  private boolean casToPrivate(Long id, Long targetOwnerId) {
    LambdaUpdateWrapper<CustomerCompanyEntity> cas =
        new LambdaUpdateWrapper<CustomerCompanyEntity>()
            .set(CustomerCompanyEntity::getPoolStatus, CustomerPoolConstant.POOL_STATUS_PRIVATE)
            .set(CustomerCompanyEntity::getOwnerId, targetOwnerId)
            .set(CustomerCompanyEntity::getPoolReleaseTime, null)
            .set(CustomerCompanyEntity::getPoolReleaseReason, null)
            .eq(CustomerCompanyEntity::getId, id)
            .eq(CustomerCompanyEntity::getPoolStatus, CustomerPoolConstant.POOL_STATUS_PUBLIC)
            .eq(CustomerCompanyEntity::getIsDeleted, false);
    return customerCompanyMapper.update(null, cas) == 1;
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
