package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.AddActivityContactRequestDTO;
import com.slz.crm.pojo.dto.AddActivityUserRequestDTO;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;

/** 商业活动关联关系维护支持类：关联用户/联系人的增删同步与附件级联删除，纯静态、无状态。 */
final class BusinessActivityRelationSupport {

  private BusinessActivityRelationSupport() {}

  /** 按增/删模式同步活动的关联用户；重复新增或删除不存在的关联都会抛错 */
  static void applyActivityUserRelations(
      BusinessActivityDTO businessActivityDTO,
      BusinessActivityEntity businessActivityEntity,
      Long currentId,
      BusinessActivityUserMapper businessActivityUserMapper) {
    List<BusinessActivityUserDTO> userIdList = businessActivityDTO.getUserIdList();
    if (userIdList == null || userIdList.isEmpty()) {
      return;
    }
    if (businessActivityDTO.isAddUser()) {
      // 新增关联用户（使用for循环替代forEach，确保异常能正确抛出）
      for (BusinessActivityUserDTO userDTO : userIdList) {
        // 检查是否存在重复关联
        int count =
            businessActivityUserMapper.existsByActivityIdAndUserId(
                businessActivityEntity.getId(), userDTO.getUserId());
        if (count == 1) {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
        }
        BusinessActivityUserEntity userEntity = new BusinessActivityUserEntity();
        BeanUtils.copyProperties(userDTO, userEntity);
        userEntity.setActivityId(businessActivityEntity.getId());
        userEntity.setCreatorId(currentId);
        businessActivityUserMapper.insert(userEntity);
      }
    } else {
      // 删除关联用户（使用for循环替代forEach，确保异常能正确抛出）
      for (BusinessActivityUserDTO userDTO : userIdList) {
        int count =
            businessActivityUserMapper.existsByActivityIdAndUserId(
                businessActivityEntity.getId(), userDTO.getUserId());
        if (count == 1) {
          businessActivityUserMapper.deleteByActivityIdAndUserId(
              businessActivityEntity.getId(), userDTO.getUserId());
        } else {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
        }
      }
    }
  }

  /** 按增/删模式同步活动的关联联系人；重复新增或删除不存在的关联都会抛错 */
  static void applyActivityContactRelations(
      BusinessActivityDTO businessActivityDTO,
      BusinessActivityEntity businessActivityEntity,
      Long currentId,
      BusinessActivityContactMapper businessActivityContactMapper) {
    List<BusinessActivityContactDTO> contactIdList = businessActivityDTO.getContactIdList();
    if (contactIdList == null || contactIdList.isEmpty()) {
      return;
    }
    if (businessActivityDTO.isAddContact()) {
      // 新增关联联系人（使用for循环替代forEach，确保异常能正确抛出）
      for (BusinessActivityContactDTO contactDTO : contactIdList) {
        // 检查是否存在重复关联
        int count =
            businessActivityContactMapper.existsByActivityIdAndContactId(
                businessActivityEntity.getId(), contactDTO.getContactId());
        if (count == 1) {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
        }
        BusinessActivityContactEntity contactEntity = new BusinessActivityContactEntity();
        BeanUtils.copyProperties(contactDTO, contactEntity);
        contactEntity.setActivityId(businessActivityEntity.getId());
        contactEntity.setCreatorId(currentId);
        businessActivityContactMapper.insert(contactEntity);
      }
    } else {
      // 删除关联联系人（使用for循环替代forEach，确保异常能正确抛出）
      for (BusinessActivityContactDTO contactDTO : contactIdList) {
        int count =
            businessActivityContactMapper.existsByActivityIdAndContactId(
                businessActivityEntity.getId(), contactDTO.getContactId());
        if (count == 1) {
          businessActivityContactMapper.deleteByActivityIdAndContactId(
              businessActivityEntity.getId(), contactDTO.getContactId());
        } else {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
        }
      }
    }
  }

  /** 向活动追加联系人关联：已存在关联即抛错，contactId 为空的请求项跳过 */
  static void addContactRelations(
      Long activityId,
      List<AddActivityContactRequestDTO> contactRequestList,
      Long currentId,
      BusinessActivityContactMapper businessActivityContactMapper) {
    // 添加联系人关联
    for (AddActivityContactRequestDTO requestDTO : contactRequestList) {
      if (requestDTO.getContactId() == null) {
        continue;
      }

      // 检查是否已存在关联
      int count =
          businessActivityContactMapper.existsByActivityIdAndContactId(
              activityId, requestDTO.getContactId());
      if (count > 0) {
        throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
      }

      // 创建新的关联记录
      BusinessActivityContactEntity entity = new BusinessActivityContactEntity();
      entity.setActivityId(activityId);
      entity.setContactId(requestDTO.getContactId());
      entity.setContactRole(requestDTO.getContactRole());
      entity.setCreatorId(currentId);
      businessActivityContactMapper.insert(entity);
    }
  }

  /** 向活动追加用户关联：已存在关联即抛错，userId 为空的请求项跳过 */
  static void addUserRelations(
      Long activityId,
      List<AddActivityUserRequestDTO> userRequestList,
      Long currentId,
      BusinessActivityUserMapper businessActivityUserMapper) {
    // 添加用户关联
    for (AddActivityUserRequestDTO requestDTO : userRequestList) {
      if (requestDTO.getUserId() == null) {
        continue;
      }

      // 检查是否已存在关联
      int count =
          businessActivityUserMapper.existsByActivityIdAndUserId(
              activityId, requestDTO.getUserId());
      if (count > 0) {
        throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
      }

      // 创建新的关联记录
      BusinessActivityUserEntity entity = new BusinessActivityUserEntity();
      entity.setActivityId(activityId);
      entity.setUserId(requestDTO.getUserId());
      entity.setUserRole(requestDTO.getUserRole());
      entity.setCreatorId(currentId);
      businessActivityUserMapper.insert(entity);
    }
  }

  /** 批量删除活动的联系人关联：任一关联不存在即抛错 */
  static void deleteActivityContactAssociations(
      Long activityId, List<Long> contactIds, BusinessActivityContactMapper contactMapper) {
    if (contactIds != null && !contactIds.isEmpty()) {
      for (Long contactId : contactIds) {
        int count = contactMapper.existsByActivityIdAndContactId(activityId, contactId);
        if (count == 0) {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
        }
        contactMapper.deleteByActivityIdAndContactId(activityId, contactId);
      }
    }
  }

  /** 批量删除活动的用户关联：任一关联不存在即抛错 */
  static void deleteActivityUserAssociations(
      Long activityId, List<Long> userIds, BusinessActivityUserMapper userRelMapper) {
    if (userIds != null && !userIds.isEmpty()) {
      for (Long userId : userIds) {
        int count = userRelMapper.existsByActivityIdAndUserId(activityId, userId);
        if (count == 0) {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
        }
        userRelMapper.deleteByActivityIdAndUserId(activityId, userId);
      }
    }
  }

  /** 级联删除单个业务活动关联的全部附件 */
  static void deleteAttachmentsForActivity(
      ApprovalAttachmentMapper attachmentMapper,
      ApprovalAttachmentService attachmentService,
      Long activityId) {
    // 查询该业务活动的所有附件
    List<ApprovalAttachmentEntity> attachments =
        attachmentMapper.selectList(
            new LambdaQueryWrapper<ApprovalAttachmentEntity>()
                .eq(ApprovalAttachmentEntity::getAndId, activityId)
                .eq(ApprovalAttachmentEntity::getModelName, ModelName.BUSINESS_ACTIVITY));

    if (attachments != null && !attachments.isEmpty()) {
      List<Long> attachmentIds =
          attachments.stream().map(ApprovalAttachmentEntity::getId).collect(Collectors.toList());
      attachmentService.removeByIds(attachmentIds, ModelName.BUSINESS_ACTIVITY);
    }
  }
}
