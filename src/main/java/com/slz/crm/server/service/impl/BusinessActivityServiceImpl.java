package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessActivityServiceImpl
    extends ServiceImpl<BusinessActivityMapper, BusinessActivityEntity>
    implements BusinessActivityService {

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
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    BusinessActivityEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS.getMessage());
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

    return businessActivityEntityList.size();
  }

  @Transactional(rollbackFor = Exception.class)
  @Override
  public Integer deleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
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
    return businessActivityMapper.deleteBatchIds(idList);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean addContactsToActivity(
      Long activityId, List<AddActivityContactRequestDTO> contactRequestList) {
    // 参数校验
    if (activityId == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    // 检查活动是否存在
    if (getById(activityId) == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS.getMessage());
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
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    // 检查活动是否存在
    if (getById(activityId) == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS.getMessage());
    }

    if (userRequestList == null || userRequestList.isEmpty()) {
      return true;
    }

    BusinessActivityRelationSupport.addUserRelations(
        activityId, userRequestList, BaseUnit.getCurrentId(), businessActivityUserMapper);

    return true;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean deleteAssociations(
      Long activityId, BatchDeleteActivityAssociationRequestDTO request) {
    // 参数校验
    if (activityId == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    // 检查活动是否存在
    if (getById(activityId) == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS.getMessage());
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
