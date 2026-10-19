package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.knowledge.ingestion.BusinessActivityIngestionService;
import com.slz.crm.pojo.dto.AddActivityContactRequestDTO;
import com.slz.crm.pojo.dto.AddActivityUserRequestDTO;
import com.slz.crm.pojo.dto.BatchDeleteActivityAssociationRequestDTO;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityQueryDTO;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.entity.*;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.server.mapper.*;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.BusinessActivityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class BusinessActivityServiceImpl
    extends ServiceImpl<BusinessActivityMapper, BusinessActivityEntity>
    implements BusinessActivityService {

  /** 独立 SLF4J 日志器（本类 extends ServiceImpl 已自带 MyBatis {@code Log log}，name 冲突故用专属 LOGGER）。 */
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(BusinessActivityServiceImpl.class);

  @Autowired private BusinessActivityMapper businessActivityMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private CustomerCompanyMapper customerCompanyMapper;
  @Autowired private SalesOpportunityMapper salesOpportunityMapper;
  @Autowired private BusinessActivityContactMapper businessActivityContactMapper;
  @Autowired private BusinessActivityUserMapper businessActivityUserMapper;
  @Autowired private ContactTaskMapper contactTaskMapper;
  @Autowired private CustomerContactMapper customerContactMapper;
  @Autowired private ApprovalAttachmentService approvalAttachmentService;
  @Autowired private ApprovalAttachmentMapper approvalAttachmentMapper;

  @Autowired private AssistRequestService assistRequestService;

  /** 商业活动语义向量摄取（add-business-activity-semantic-retrieval 任务 2.2）。 */
  @Autowired private BusinessActivityIngestionService businessActivityIngestionService;

  /** 商业活动语义向量异步任务执行器（add-unified-business-semantic-retrieval 任务 2.1；异步非阻塞核心依赖）。 */
  @Autowired
  @Qualifier("embeddingTaskExecutor")
  private Executor embeddingTaskExecutor;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean create(BusinessActivityDTO businessActivityDTO) {

    BusinessActivityEntity businessActivityEntity = new BusinessActivityEntity();
    BeanUtils.copyProperties(businessActivityDTO, businessActivityEntity);

    Long currentId = BaseUnit.getCurrentId();

    businessActivityEntity.setCreatorId(currentId);
    save(businessActivityEntity);

    // 保存协助人记录（可空，空则跳过）
    assistRequestService.createAssists(
        ModelName.BUSINESS_ACTIVITY,
        businessActivityEntity.getId(),
        currentId,
        businessActivityDTO.getAssistApplyList());

    // 插入联系人活动关联
    List<BusinessActivityUserDTO> userIdList = businessActivityDTO.getUserIdList();
    List<BusinessActivityContactDTO> contactIdList = businessActivityDTO.getContactIdList();

    if (contactIdList != null) {
      contactIdList.forEach(
          contactId -> {
            BusinessActivityContactEntity entity = new BusinessActivityContactEntity();
            BeanUtils.copyProperties(contactId, entity);

            entity.setCreatorId(currentId);
            entity.setActivityId(businessActivityEntity.getId());
            businessActivityContactMapper.insert(entity);
          });
    }

    // 插入用户活动关啦
    if (userIdList != null) {
      userIdList.forEach(
          contactId -> {
            BusinessActivityUserEntity entity = new BusinessActivityUserEntity();
            BeanUtils.copyProperties(contactId, entity);
            entity.setCreatorId(currentId);
            entity.setActivityId(businessActivityEntity.getId());
            businessActivityUserMapper.insert(entity);
          });
    }

    // 如果关联了联络任务，根据传入的nextTaskStatus更新任务状态
    if (businessActivityDTO.getTaskId() != null) {
      // 只有传入了nextTaskStatus时才更新
      if (businessActivityDTO.getNextTaskStatus() != null) {
        ContactTaskEntity taskEntity =
            contactTaskMapper.selectById(businessActivityDTO.getTaskId());
        if (taskEntity != null) {
          // 验证状态值是否合法（0-3）
          Integer nextStatus = businessActivityDTO.getNextTaskStatus();
          if (nextStatus < 0 || nextStatus > 3) {
            throw new BaseException(
                ErrorCode.PARAM_FORMAT_ERROR, "任务状态参数错误，只支持：0未开始/1进行中/2已完成/3已取消");
          }
          // 更新任务状态
          taskEntity.setStatus(nextStatus);
          taskEntity.setUpdateTime(new java.util.Date());
          contactTaskMapper.updateById(taskEntity);
        }
      }
    }

    // add-business-activity-semantic-retrieval 任务 2.2：创建成功提交后增量 upsert 语义向量
    runSemanticSync("创建商业活动", () -> reingestSemanticById(businessActivityEntity.getId()));

    // add-customer-public-sea-pool 任务 3.2：新增活动后事务提交异步刷新关联客户 last_activity_time（跟进活跃度联动）
    refreshCustomerLastActivityAfterCommit(businessActivityEntity.getId());

    return true;
  }

  @Override
  public Page<BusinessActivityVO> businessActivityQuery(
      Integer pageNum, Integer pageSize, BusinessActivityQueryDTO dto) {
    Page<BusinessActivityEntity> page = new Page<>(pageNum, pageSize);

    Page<BusinessActivityEntity> entityPage =
        businessActivityMapper.selectPage(
            page,
            BusinessActivityQuerySupport.getQueryWrapper(
                dto, userMapper, salesOpportunityMapper, businessActivityUserMapper));

    // 转换为VO
    Page<BusinessActivityVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage);
    voPage.setRecords(
        BusinessActivityVoAssembler.assembleVoList(
            entityPage.getRecords(),
            userMapper,
            salesOpportunityMapper,
            businessActivityContactMapper,
            businessActivityUserMapper,
            customerContactMapper,
            assistRequestService));
    return voPage;
  }

  @Override
  public List<BusinessActivityVO> getAllActivity(Integer pageNum, Integer pageSize) {
    Page<BusinessActivityEntity> page = new Page<>(pageNum, pageSize);
    Page<BusinessActivityEntity> pageResult =
        page(
            page,
            BusinessActivityQuerySupport.getQueryWrapper(
                new BusinessActivityQueryDTO(),
                userMapper,
                salesOpportunityMapper,
                businessActivityUserMapper));

    return BusinessActivityVoAssembler.assembleVoList(
        pageResult.getRecords(),
        userMapper,
        salesOpportunityMapper,
        businessActivityContactMapper,
        businessActivityUserMapper,
        customerContactMapper,
        assistRequestService);
  }

  @Override
  public BusinessActivityVO getDetailById(Long id) {
    // 参数校验
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    BusinessActivityEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS);
    }

    // 相关人校验：创建人/参与人/协助人可见，超管豁免
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    if (!isAdmin) {
      boolean visible =
          Objects.equals(entity.getCreatorId(), currentId)
              || assistRequestService
                  .getRelatedRecordIdsByUser(ModelName.BUSINESS_ACTIVITY, currentId)
                  .contains(id)
              || businessActivityUserMapper.existsByActivityIdAndUserId(id, currentId) > 0;
      if (!visible) {
        throw new BaseException(ErrorCode.PERMISSION_DENIED);
      }
    }

    return BusinessActivityVoAssembler.buildDetailVo(
        entity, id, currentId, userMapper, salesOpportunityMapper, assistRequestService);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer updateList(List<BusinessActivityDTO> businessActivityDTOList) {

    List<BusinessActivityEntity> businessActivityEntityList = new ArrayList<>();
    Long currentId = BaseUnit.getCurrentId();

    for (BusinessActivityDTO businessActivityDTO : businessActivityDTOList) {
      BusinessActivityEntity businessActivityEntity = new BusinessActivityEntity();
      BeanUtils.copyProperties(businessActivityDTO, businessActivityEntity);
      businessActivityEntity.setId(businessActivityDTO.getId());
      businessActivityEntityList.add(businessActivityEntity);

      // 同步活动的关联用户
      BusinessActivityRelationSupport.applyActivityUserRelations(
          businessActivityDTO, businessActivityEntity, currentId, businessActivityUserMapper);

      // 修改活动关联的联系人
      BusinessActivityRelationSupport.applyActivityContactRelations(
          businessActivityDTO, businessActivityEntity, currentId, businessActivityContactMapper);
    }

    updateBatchById(businessActivityEntityList);

    // add-business-activity-semantic-retrieval 任务 2.2：更新成功提交后批量重嵌（回查完整实体防 DTO 部分字段丢键）
    runSemanticSync(
        "批量更新商业活动",
        () -> businessActivityEntityList.forEach(entity -> reingestSemanticById(entity.getId())));

    return businessActivityEntityList.size();
  }

  @Transactional(rollbackFor = Exception.class)
  @Override
  public Integer deleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 0. 级联删除协助记录
    assistRequestService.deleteByRecords(ModelName.BUSINESS_ACTIVITY, idList);

    // 1. 先删除每个业务活动关联的附件与联系/参与人关联（遍历删除）
    for (Long activityId : idList) {
      BusinessActivityRelationSupport.deleteAttachmentsForActivity(
          approvalAttachmentMapper, approvalAttachmentService, activityId);
      businessActivityContactMapper.deleteByActivityId(activityId);
      businessActivityUserMapper.deleteByActivityId(activityId);
    }

    // 2. 物理删除活动本身
    Integer deleted = businessActivityMapper.deleteBatchIds(idList);

    // add-business-activity-semantic-retrieval 任务 2.2：物理删除提交后连带删向量（删除即失效）
    runSemanticSync(
        "物理删除商业活动",
        () ->
            idList.forEach(
                id ->
                    BusinessActivitySemanticHookSupport.deleteById(
                        id, businessActivityIngestionService)));

    return deleted;
  }

  /**
   * 语义向量同步闸（任务 2.2；add-unified-business-semantic-retrieval 任务 2.1 异步化，委托 {@link
   * BusinessActivitySemanticHookSupport#runAfterCommitAsync}）：事务激活时注册 afterCommit，提交后异步派发至
   * embeddingTaskExecutor，无事务环境同样异步派发；semantic 侧未装配或异常均不阻断业务主流程，主线程零等待。
   *
   * @param scene 场景描述（日志定位用）
   * @param action 向量同步动作
   */
  private void runSemanticSync(String scene, Runnable action) {
    if (businessActivityIngestionService != null && embeddingTaskExecutor != null) {
      BusinessActivitySemanticHookSupport.runAfterCommitAsync(scene, embeddingTaskExecutor, action);
    }
  }

  /** 回查完整实体后 upsert 语义向量（任务 2.2）；仅在 runSemanticSync 的 action 内调用。 */
  private void reingestSemanticById(Long id) {
    BusinessActivitySemanticHookSupport.reingestById(
        id, businessActivityIngestionService, this::getById);
  }

  /**
   * 新增商业活动后，事务提交异步刷新关联客户的 {@code last_activity_time = now()}（add-customer-public-sea-pool 任务 3.2）。
   *
   * <p>通过活动关联联系人 → 联系人所属客户公司，批量刷新跟进活跃度；异常不外抛（活跃度刷新失败不阻断活动主流程）， 供超时自动回收的跟进时效判定。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 跟进活跃度刷新失败类型不可枚举（ORM/外呼多源），WARN 兜底不阻断活动
  private void refreshCustomerLastActivityAfterCommit(Long activityId) {
    Runnable refresh =
        () -> {
          try {
            Set<Long> contactIds =
                businessActivityContactMapper.selectContactIdsByActivity(activityId);
            if (contactIds == null || contactIds.isEmpty()) {
              return;
            }
            Set<Long> companyIds = customerContactMapper.selectCompanyIdsByContactIds(contactIds);
            if (companyIds == null || companyIds.isEmpty()) {
              return;
            }
            customerCompanyMapper.updateLastActivityTime(companyIds);
          } catch (Exception e) {
            LOGGER.warn("客户 last_activity_time 跟进活跃度联动刷新失败（活动 {}），可由后续活动刷新兜底", activityId, e);
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              refresh.run();
            }
          });
    } else {
      refresh.run();
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean addContactsToActivity(
      Long activityId, List<AddActivityContactRequestDTO> contactRequestList) {
    // 参数校验
    if (activityId == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    // 检查活动是否存在
    if (getById(activityId) == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS);
    }

    if (contactRequestList != null && !contactRequestList.isEmpty()) {
      BusinessActivityRelationSupport.addContactRelations(
          activityId, contactRequestList, BaseUnit.getCurrentId(), businessActivityContactMapper);
    }

    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean addUsersToActivity(
      Long activityId, List<AddActivityUserRequestDTO> userRequestList) {
    // 参数校验
    if (activityId == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    // 检查活动是否存在
    if (getById(activityId) == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS);
    }

    if (userRequestList != null && !userRequestList.isEmpty()) {
      BusinessActivityRelationSupport.addUserRelations(
          activityId, userRequestList, BaseUnit.getCurrentId(), businessActivityUserMapper);
    }

    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean deleteAssociations(
      Long activityId, BatchDeleteActivityAssociationRequestDTO request) {
    // 参数校验
    if (activityId == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR);
    }

    // 检查活动是否存在
    if (getById(activityId) == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS);
    }

    if (request != null) {
      // 批量删除联系人关联
      BusinessActivityRelationSupport.deleteActivityContactAssociations(
          activityId, request.getContactIds(), businessActivityContactMapper);
      // 批量删除用户关联
      BusinessActivityRelationSupport.deleteActivityUserAssociations(
          activityId, request.getUserIds(), businessActivityUserMapper);
    }

    return true;
  }
}
