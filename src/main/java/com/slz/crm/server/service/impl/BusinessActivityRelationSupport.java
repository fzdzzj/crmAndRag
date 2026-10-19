package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;

/**
 * 商业活动关联关系维护支持类：关联用户/联系人的增删同步与附件级联删除，纯静态、无状态。
 *
 * <p>A-8 集合化：存在性判定由「逐条 exists + 逐条写」改为「一次 IN 查询取已存在集合 + list-vs-set 比对 + 批量写（多值 insert / 单条 IN
 * delete）」，单方法 SQL 条数由 2N 降到 2。错误码、文案、null 跳过口径与「请求内重复 id」旧语义逐路径保持不变。
 */
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

    Long activityId = businessActivityEntity.getId();
    List<Long> userIds =
        userIdList.stream().map(BusinessActivityUserDTO::getUserId).collect(Collectors.toList());
    Set<Long> existingUserIds =
        businessActivityUserMapper.selectExistingUserIds(activityId, userIds);

    if (businessActivityDTO.isAddUser()) {
      // 请求内重复 id（旧语义第二轮 check 命中已插入行）与库内已存在均为「重复关联」
      if (hasDuplicatedId(userIds) || intersects(existingUserIds, userIds)) {
        throw new BaseException(
            ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
      }
      List<BusinessActivityUserEntity> entities = new ArrayList<>(userIdList.size());
      for (BusinessActivityUserDTO userDTO : userIdList) {
        BusinessActivityUserEntity userEntity = new BusinessActivityUserEntity();
        BeanUtils.copyProperties(userDTO, userEntity);
        userEntity.setActivityId(activityId);
        userEntity.setCreatorId(currentId);
        entities.add(userEntity);
      }
      businessActivityUserMapper.batchInsert(entities);
    } else {
      // 任一缺失（旧语义第二轮 check 已删行）与请求内重复 id 均为「关联不存在」
      if (!containsAllIds(existingUserIds, userIds) || hasDuplicatedId(userIds)) {
        throw new BaseException(
            ErrorCode.BUSINESS_ACTIVITY_USER_NOT_EXISTS,
            MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
      }
      businessActivityUserMapper.deleteByActivityIdAndUserIds(activityId, userIds);
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

    Long activityId = businessActivityEntity.getId();
    List<Long> contactIds =
        contactIdList.stream()
            .map(BusinessActivityContactDTO::getContactId)
            .collect(Collectors.toList());
    Set<Long> existingContactIds =
        businessActivityContactMapper.selectExistingContactIds(activityId, contactIds);

    if (businessActivityDTO.isAddContact()) {
      // 请求内重复 id（旧语义第二轮 check 命中已插入行）与库内已存在均为「重复关联」
      if (hasDuplicatedId(contactIds) || intersects(existingContactIds, contactIds)) {
        throw new BaseException(
            ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
      }
      List<BusinessActivityContactEntity> entities = new ArrayList<>(contactIdList.size());
      for (BusinessActivityContactDTO contactDTO : contactIdList) {
        BusinessActivityContactEntity contactEntity = new BusinessActivityContactEntity();
        BeanUtils.copyProperties(contactDTO, contactEntity);
        contactEntity.setActivityId(activityId);
        contactEntity.setCreatorId(currentId);
        entities.add(contactEntity);
      }
      businessActivityContactMapper.batchInsert(entities);
    } else {
      // 任一缺失（旧语义第二轮 check 已删行）与请求内重复 id 均为「关联不存在」
      if (!containsAllIds(existingContactIds, contactIds) || hasDuplicatedId(contactIds)) {
        throw new BaseException(
            ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS,
            MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
      }
      businessActivityContactMapper.deleteByActivityIdAndContactIds(activityId, contactIds);
    }
  }

  /** 向活动追加联系人关联：已存在关联即抛错，contactId 为空的请求项跳过 */
  static void addContactRelations(
      Long activityId,
      List<AddActivityContactRequestDTO> contactRequestList,
      Long currentId,
      BusinessActivityContactMapper businessActivityContactMapper) {
    // null 跳过口径保留：只为非空 contactId 做存在性比对与写入
    List<Long> contactIds =
        contactRequestList.stream()
            .map(AddActivityContactRequestDTO::getContactId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    if (contactIds.isEmpty()) {
      return;
    }

    Set<Long> existingContactIds =
        businessActivityContactMapper.selectExistingContactIds(activityId, contactIds);
    // 请求内重复 id（旧语义第二轮 check 命中刚插入的行）与库内已存在均为「重复关联」
    if (hasDuplicatedId(contactIds) || intersects(existingContactIds, contactIds)) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
    }

    List<BusinessActivityContactEntity> entities = new ArrayList<>(contactIds.size());
    for (AddActivityContactRequestDTO requestDTO : contactRequestList) {
      if (requestDTO.getContactId() == null) {
        continue;
      }
      BusinessActivityContactEntity entity = new BusinessActivityContactEntity();
      entity.setActivityId(activityId);
      entity.setContactId(requestDTO.getContactId());
      entity.setContactRole(requestDTO.getContactRole());
      entity.setCreatorId(currentId);
      entities.add(entity);
    }
    businessActivityContactMapper.batchInsert(entities);
  }

  /** 向活动追加用户关联：已存在关联即抛错，userId 为空的请求项跳过 */
  static void addUserRelations(
      Long activityId,
      List<AddActivityUserRequestDTO> userRequestList,
      Long currentId,
      BusinessActivityUserMapper businessActivityUserMapper) {
    // null 跳过口径保留：只为非空 userId 做存在性比对与写入
    List<Long> userIds =
        userRequestList.stream()
            .map(AddActivityUserRequestDTO::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    if (userIds.isEmpty()) {
      return;
    }

    Set<Long> existingUserIds =
        businessActivityUserMapper.selectExistingUserIds(activityId, userIds);
    // 请求内重复 id（旧语义第二轮 check 命中刚插入的行）与库内已存在均为「重复关联」
    if (hasDuplicatedId(userIds) || intersects(existingUserIds, userIds)) {
      throw new BaseException(
          ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
    }

    List<BusinessActivityUserEntity> entities = new ArrayList<>(userIds.size());
    for (AddActivityUserRequestDTO requestDTO : userRequestList) {
      if (requestDTO.getUserId() == null) {
        continue;
      }
      BusinessActivityUserEntity entity = new BusinessActivityUserEntity();
      entity.setActivityId(activityId);
      entity.setUserId(requestDTO.getUserId());
      entity.setUserRole(requestDTO.getUserRole());
      entity.setCreatorId(currentId);
      entities.add(entity);
    }
    businessActivityUserMapper.batchInsert(entities);
  }

  /** 批量删除活动的联系人关联：任一关联不存在即抛错 */
  static void deleteActivityContactAssociations(
      Long activityId, List<Long> contactIds, BusinessActivityContactMapper contactMapper) {
    if (contactIds == null || contactIds.isEmpty()) {
      return;
    }

    Set<Long> existingContactIds = contactMapper.selectExistingContactIds(activityId, contactIds);
    // 任一缺失（旧语义第二轮 check 已删行）与请求内重复 id 均为「关联不存在」
    if (!containsAllIds(existingContactIds, contactIds) || hasDuplicatedId(contactIds)) {
      throw new BaseException(
          ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS,
          MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
    }
    contactMapper.deleteByActivityIdAndContactIds(activityId, contactIds);
  }

  /** 批量删除活动的用户关联：任一关联不存在即抛错 */
  static void deleteActivityUserAssociations(
      Long activityId, List<Long> userIds, BusinessActivityUserMapper userRelMapper) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }

    Set<Long> existingUserIds = userRelMapper.selectExistingUserIds(activityId, userIds);
    // 任一缺失（旧语义第二轮 check 已删行）与请求内重复 id 均为「关联不存在」
    if (!containsAllIds(existingUserIds, userIds) || hasDuplicatedId(userIds)) {
      throw new BaseException(
          ErrorCode.BUSINESS_ACTIVITY_USER_NOT_EXISTS,
          MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
    }
    userRelMapper.deleteByActivityIdAndUserIds(activityId, userIds);
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

  /** 请求内重复 id 判定：旧逐条实现里第二轮 check 必然看到第一轮写入/删除的效果，故重复必须以同一码同一文案抛出。 */
  private static boolean hasDuplicatedId(List<Long> ids) {
    return new HashSet<>(ids).size() != ids.size();
  }

  /** 已存在集合与请求 id 是否有交集；null 集合按空处理（空表 / 无关联） */
  private static boolean intersects(Collection<Long> existing, Collection<Long> requested) {
    return existing != null && !Collections.disjoint(existing, requested);
  }

  /** 请求 id 是否全部已存在；null 集合按空处理（任一缺失即 false） */
  private static boolean containsAllIds(Collection<Long> existing, Collection<Long> requested) {
    return requested.isEmpty() || (existing != null && existing.containsAll(requested));
  }
}
