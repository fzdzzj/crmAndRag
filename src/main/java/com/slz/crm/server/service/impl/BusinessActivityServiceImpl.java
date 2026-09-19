package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.*;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.BusinessActivityService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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

  /** 组装协助申请明细：优先明细列表，兼容旧 assistUserIds（无目的/要求） */
  private java.util.List<com.slz.crm.pojo.dto.AssistApplyItem> resolveAssistApplyList(
      java.util.List<com.slz.crm.pojo.dto.AssistApplyItem> applyList,
      java.util.List<Long> assistUserIds) {
    if (applyList != null && !applyList.isEmpty()) {
      return applyList;
    }
    if (assistUserIds == null || assistUserIds.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    return assistUserIds.stream()
        .map(
            id -> {
              com.slz.crm.pojo.dto.AssistApplyItem item =
                  new com.slz.crm.pojo.dto.AssistApplyItem();
              item.setAssistUserId(id);
              return item;
            })
        .collect(java.util.stream.Collectors.toList());
  }

  @Override
  public Page<BusinessActivityVO> businessActivityQuery(
      Integer pageNum, Integer pageSize, BusinessActivityQueryDTO dto) {
    Page<BusinessActivityEntity> page = new Page<>(pageNum, pageSize);

    Page<BusinessActivityEntity> entityPage =
        businessActivityMapper.selectPage(page, getQueryWrapper(dto));

    // 转换为VO
    Page<BusinessActivityVO> voPage = new Page<>();
    BeanUtils.copyProperties(entityPage, voPage);

    // 收集所有需要查询的ID
    Set<Long> userIds = new HashSet<>();
    Set<Long> opportunityIds = new HashSet<>();
    List<Long> activityIds = new ArrayList<>();

    for (BusinessActivityEntity entity : entityPage.getRecords()) {
      if (entity.getCreatorId() != null) userIds.add(entity.getCreatorId());
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
      activityIds.add(entity.getId());
    }

    // 批量查询
    Map<Long, UserEntity> userMap =
        userIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    Map<Long, SalesOpportunityEntity> opportunityMap =
        opportunityIds.isEmpty()
            ? Collections.emptyMap()
            : salesOpportunityMapper.selectBatchIds(opportunityIds).stream()
                .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));

    // 批量查询联系人和用户关联
    List<BusinessActivityContactEntity> contactRelations =
        activityIds.isEmpty()
            ? Collections.emptyList()
            : businessActivityContactMapper.selectList(
                new LambdaQueryWrapper<BusinessActivityContactEntity>()
                    .in(BusinessActivityContactEntity::getActivityId, activityIds));

    List<BusinessActivityUserEntity> userRelations =
        activityIds.isEmpty()
            ? Collections.emptyList()
            : businessActivityUserMapper.selectByActivityIds(activityIds);

    // 收集联系人ID和用户ID，并按活动ID分组
    Map<Long, List<BusinessActivityContactEntity>> contactRelationMap =
        contactRelations.stream()
            .collect(Collectors.groupingBy(BusinessActivityContactEntity::getActivityId));

    Map<Long, List<BusinessActivityUserEntity>> userRelationMap =
        userRelations.stream()
            .collect(Collectors.groupingBy(BusinessActivityUserEntity::getActivityId));

    Set<Long> contactIds =
        contactRelations.stream()
            .map(BusinessActivityContactEntity::getContactId)
            .collect(Collectors.toSet());

    Set<Long> activityUserIds =
        userRelations.stream()
            .map(BusinessActivityUserEntity::getUserId)
            .collect(Collectors.toSet());

    // 批量查询联系人和用户信息
    Map<Long, CustomerContactEntity> contactMap =
        contactIds.isEmpty()
            ? Collections.emptyMap()
            : customerContactMapper.selectBatchIds(contactIds).stream()
                .collect(Collectors.toMap(CustomerContactEntity::getId, Function.identity()));

    // 复用已查询的用户信息，添加活动中关联的用户
    Map<Long, UserEntity> activityUserMap;
    if (!activityUserIds.isEmpty()) {
      Set<Long> allUserIds = new HashSet<>(userMap.keySet());
      allUserIds.addAll(activityUserIds);
      activityUserMap =
          userMapper.selectBatchIds(allUserIds).stream()
              .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    } else {
      activityUserMap = userMap;
    }

    // 构建VO列表
    List<BusinessActivityVO> voList =
        entityPage.getRecords().stream()
            .map(
                entity -> {
                  String[] names =
                      getBusinessActivityNamesWithMaps(entity, userMap, opportunityMap);
                  BusinessActivityVO vo = BusinessActivityVO.fromEntity(entity, names[0], names[1]);

                  // 填充联系人信息
                  List<BusinessActivityContactEntity> contacts =
                      contactRelationMap.getOrDefault(entity.getId(), Collections.emptyList());
                  if (!contacts.isEmpty()) {
                    List<BusinessActivityVO.ActivityContactVO> contactVOList =
                        contacts.stream()
                            .map(
                                contactRel -> {
                                  BusinessActivityVO.ActivityContactVO contactVO =
                                      new BusinessActivityVO.ActivityContactVO();
                                  contactVO.setContactId(contactRel.getContactId());
                                  CustomerContactEntity contactEntity =
                                      contactMap.get(contactRel.getContactId());
                                  if (contactEntity != null) {
                                    contactVO.setContactName(contactEntity.getName());
                                  }
                                  contactVO.setContactRole(contactRel.getContactRole());
                                  return contactVO;
                                })
                            .toList();
                    vo.setContacts(contactVOList);
                  }

                  // 填充用户信息
                  List<BusinessActivityUserEntity> users =
                      userRelationMap.getOrDefault(entity.getId(), Collections.emptyList());
                  if (!users.isEmpty()) {
                    List<BusinessActivityVO.ActivityUserVO> userVOList =
                        users.stream()
                            .map(
                                userRel -> {
                                  BusinessActivityVO.ActivityUserVO userVO =
                                      new BusinessActivityVO.ActivityUserVO();
                                  userVO.setUserId(userRel.getUserId());
                                  UserEntity userEntity = activityUserMap.get(userRel.getUserId());
                                  if (userEntity != null) {
                                    userVO.setUserName(userEntity.getRealName());
                                  }
                                  userVO.setUserRole(userRel.getUserRole());
                                  return userVO;
                                })
                            .toList();
                    vo.setUsers(userVOList);
                  }

                  return vo;
                })
            .toList();

    voPage.setRecords(voList);
    // 批量组装协助人（按可见性过滤）
    fillAssistUsers(voList, BaseUnit.getCurrentId());
    return voPage;
  }

  /**
   * 构建查询包装器
   *
   * @param queryDTO 查询条件
   * @return 查询包装器
   */
  public LambdaQueryWrapper<BusinessActivityEntity> getQueryWrapper(
      BusinessActivityQueryDTO queryDTO) {
    LambdaQueryWrapper<BusinessActivityEntity> queryWrapper = new LambdaQueryWrapper<>();

    // 按活动标题查询
    if (queryDTO.getActivityTitle() != null && !queryDTO.getActivityTitle().isEmpty()) {
      queryWrapper.like(BusinessActivityEntity::getActivityTitle, queryDTO.getActivityTitle());
    }
    // 按活动类型查询
    if (queryDTO.getActivityType() != null && !queryDTO.getActivityType().isEmpty()) {
      queryWrapper.eq(BusinessActivityEntity::getActivityType, queryDTO.getActivityType());
    }
    // 按活动内容查询
    if (queryDTO.getActivityContent() != null && !queryDTO.getActivityContent().isEmpty()) {
      queryWrapper.like(BusinessActivityEntity::getActivityContent, queryDTO.getActivityContent());
    }
    // 创建人名称模糊搜索
    if (queryDTO.getCreatorName() != null && !queryDTO.getCreatorName().isEmpty()) {
      Set<Long> creatorIds = userMapper.selectUserIdsByUserName(queryDTO.getCreatorName());
      if (!creatorIds.isEmpty()) {
        queryWrapper.in(BusinessActivityEntity::getCreatorId, creatorIds);
      } else {
        queryWrapper.eq(BusinessActivityEntity::getCreatorId, -1);
      }
    }
    // 销售机会名称模糊搜索
    if (queryDTO.getOpportunityName() != null && !queryDTO.getOpportunityName().isEmpty()) {
      Set<Long> opportunityIds =
          salesOpportunityMapper.selectOpportunityIdsByName(queryDTO.getOpportunityName());
      if (!opportunityIds.isEmpty()) {
        queryWrapper.in(BusinessActivityEntity::getOpportunityId, opportunityIds);
      } else {
        queryWrapper.eq(BusinessActivityEntity::getOpportunityId, -1);
      }
    }
    // 备注模糊搜索
    if (queryDTO.getRemark() != null && !queryDTO.getRemark().isEmpty()) {
      queryWrapper.like(BusinessActivityEntity::getRemark, queryDTO.getRemark());
    }
    // 创建时间范围搜索
    if (queryDTO.getMinCreateTime() != null && queryDTO.getMaxCreateTime() != null) {
      queryWrapper.between(
          BusinessActivityEntity::getCreateTime,
          queryDTO.getMinCreateTime(),
          queryDTO.getMaxCreateTime());
    }
    // 活动日期范围搜索
    if (queryDTO.getMinActivityTime() != null && queryDTO.getMaxActivityTime() != null) {
      queryWrapper.between(
          BusinessActivityEntity::getActivityTime,
          queryDTO.getMinActivityTime(),
          queryDTO.getMaxActivityTime());
    }
    // 活动时长搜索
    if (queryDTO.getActivityDuration() != null) {
      queryWrapper.eq(BusinessActivityEntity::getActivityDuration, queryDTO.getActivityDuration());
    }

    // 默认按创建时间倒序
    queryWrapper.orderByDesc(BusinessActivityEntity::getCreateTime);

    // 普通列表只按正常业务关系过滤；协助人从协助页进入单条详情，不扩大列表范围
    applyVisibilityFilter(queryWrapper);

    return queryWrapper;
  }

  /**
   * 获取商业活动相关的创建者名称和销售机会名称
   *
   * @param entity 商业活动实体
   * @return 包含创建者名称和销售机会名称的数组，顺序为：[creatorName, opportunityName]
   */
  private String[] getBusinessActivityNames(BusinessActivityEntity entity) {
    // 单个查询版本：真实查询创建人和销售机会名称（不能用空 map，否则详情永远为空）
    Map<Long, UserEntity> userMap =
        entity.getCreatorId() == null
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(Collections.singletonList(entity.getCreatorId())).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    Map<Long, SalesOpportunityEntity> opportunityMap =
        entity.getOpportunityId() == null
            ? Collections.emptyMap()
            : salesOpportunityMapper
                .selectBatchIds(Collections.singletonList(entity.getOpportunityId()))
                .stream()
                .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));
    return getBusinessActivityNamesWithMaps(entity, userMap, opportunityMap);
  }

  /** 获取商业活动相关的名称（批量查询版本） */
  private String[] getBusinessActivityNamesWithMaps(
      BusinessActivityEntity entity,
      Map<Long, UserEntity> userMap,
      Map<Long, SalesOpportunityEntity> opportunityMap) {
    String creatorName = null;
    String opportunityName = null;

    // 创建者名称
    if (entity.getCreatorId() != null) {
      UserEntity userEntity = userMap.get(entity.getCreatorId());
      if (userEntity != null) {
        creatorName = userEntity.getRealName();
      }
    }

    // 销售机会名称
    if (entity.getOpportunityId() != null) {
      SalesOpportunityEntity opportunityEntity = opportunityMap.get(entity.getOpportunityId());
      if (opportunityEntity != null) {
        opportunityName = opportunityEntity.getOpportunityName();
      }
    }

    return new String[] {creatorName, opportunityName};
  }

  /**
   * 获取名称
   *
   * @param vo 商业活动VO
   */
  public void getName(BusinessActivityVO vo) {
    // 获取销售机会名称
    if (vo.getOpportunityId() != null) {
      SalesOpportunityEntity opportunityEntity =
          salesOpportunityMapper.selectById(vo.getOpportunityId());
      if (opportunityEntity != null) {
        vo.setOpportunityName(opportunityEntity.getOpportunityName());
      }
    }
    // 获取创建人名称
    if (vo.getCreatorId() != null) {
      UserEntity userEntity = userMapper.selectById(vo.getCreatorId());
      if (userEntity != null) {
        vo.setCreatorName(userEntity.getRealName());
      }
    }
  }

  @Override
  public List<BusinessActivityVO> getAllActivity(Integer pageNum, Integer pageSize) {
    List<BusinessActivityVO> ans = new ArrayList<>();

    Page<BusinessActivityEntity> page = new Page<>(pageNum, pageSize);
    Page<BusinessActivityEntity> pageResult =
        page(page, getQueryWrapper(new BusinessActivityQueryDTO()));

    // 收集所有需要查询的ID
    Set<Long> userIds = new HashSet<>();
    Set<Long> opportunityIds = new HashSet<>();
    List<Long> activityIds = new ArrayList<>();

    for (BusinessActivityEntity entity : pageResult.getRecords()) {
      if (entity.getCreatorId() != null) userIds.add(entity.getCreatorId());
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
      activityIds.add(entity.getId());
    }

    // 批量查询
    Map<Long, UserEntity> userMap =
        userIds.isEmpty()
            ? Collections.emptyMap()
            : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    Map<Long, SalesOpportunityEntity> opportunityMap =
        opportunityIds.isEmpty()
            ? Collections.emptyMap()
            : salesOpportunityMapper.selectBatchIds(opportunityIds).stream()
                .collect(Collectors.toMap(SalesOpportunityEntity::getId, Function.identity()));

    // 批量查询联系人和用户关联
    List<BusinessActivityContactEntity> contactRelations =
        activityIds.isEmpty()
            ? Collections.emptyList()
            : businessActivityContactMapper.selectList(
                new LambdaQueryWrapper<BusinessActivityContactEntity>()
                    .in(BusinessActivityContactEntity::getActivityId, activityIds));

    List<BusinessActivityUserEntity> userRelations =
        activityIds.isEmpty()
            ? Collections.emptyList()
            : businessActivityUserMapper.selectByActivityIds(activityIds);

    // 收集联系人ID和用户ID，并按活动ID分组
    Map<Long, List<BusinessActivityContactEntity>> contactRelationMap =
        contactRelations.stream()
            .collect(Collectors.groupingBy(BusinessActivityContactEntity::getActivityId));

    Map<Long, List<BusinessActivityUserEntity>> userRelationMap =
        userRelations.stream()
            .collect(Collectors.groupingBy(BusinessActivityUserEntity::getActivityId));

    Set<Long> contactIds =
        contactRelations.stream()
            .map(BusinessActivityContactEntity::getContactId)
            .collect(Collectors.toSet());

    Set<Long> activityUserIds =
        userRelations.stream()
            .map(BusinessActivityUserEntity::getUserId)
            .collect(Collectors.toSet());

    // 批量查询联系人和用户信息
    Map<Long, CustomerContactEntity> contactMap =
        contactIds.isEmpty()
            ? Collections.emptyMap()
            : customerContactMapper.selectBatchIds(contactIds).stream()
                .collect(Collectors.toMap(CustomerContactEntity::getId, Function.identity()));

    // 复用已查询的用户信息，添加活动中关联的用户
    Map<Long, UserEntity> activityUserMap;
    if (!activityUserIds.isEmpty()) {
      Set<Long> allUserIds = new HashSet<>(userMap.keySet());
      allUserIds.addAll(activityUserIds);
      activityUserMap =
          userMapper.selectBatchIds(allUserIds).stream()
              .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    } else {
      activityUserMap = userMap;
    }

    // 构建VO列表
    for (BusinessActivityEntity businessActivityEntity : pageResult.getRecords()) {
      String[] names =
          getBusinessActivityNamesWithMaps(businessActivityEntity, userMap, opportunityMap);
      BusinessActivityVO businessActivityVO =
          BusinessActivityVO.fromEntity(businessActivityEntity, names[0], names[1]);

      // 填充联系人信息
      List<BusinessActivityContactEntity> contacts =
          contactRelationMap.getOrDefault(businessActivityEntity.getId(), Collections.emptyList());
      if (!contacts.isEmpty()) {
        List<BusinessActivityVO.ActivityContactVO> contactVOList =
            contacts.stream()
                .map(
                    contactRel -> {
                      BusinessActivityVO.ActivityContactVO contactVO =
                          new BusinessActivityVO.ActivityContactVO();
                      contactVO.setContactId(contactRel.getContactId());
                      CustomerContactEntity contactEntity =
                          contactMap.get(contactRel.getContactId());
                      if (contactEntity != null) {
                        contactVO.setContactName(contactEntity.getName());
                      }
                      contactVO.setContactRole(contactRel.getContactRole());
                      return contactVO;
                    })
                .toList();
        businessActivityVO.setContacts(contactVOList);
      }

      // 填充用户信息
      List<BusinessActivityUserEntity> users =
          userRelationMap.getOrDefault(businessActivityEntity.getId(), Collections.emptyList());
      if (!users.isEmpty()) {
        List<BusinessActivityVO.ActivityUserVO> userVOList =
            users.stream()
                .map(
                    userRel -> {
                      BusinessActivityVO.ActivityUserVO userVO =
                          new BusinessActivityVO.ActivityUserVO();
                      userVO.setUserId(userRel.getUserId());
                      UserEntity userEntity = activityUserMap.get(userRel.getUserId());
                      if (userEntity != null) {
                        userVO.setUserName(userEntity.getRealName());
                      }
                      userVO.setUserRole(userRel.getUserRole());
                      return userVO;
                    })
                .toList();
        businessActivityVO.setUsers(userVOList);
      }

      ans.add(businessActivityVO);
    }

    // 批量组装协助人（按可见性过滤）
    fillAssistUsers(ans, BaseUnit.getCurrentId());
    return ans;
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

    String[] names = getBusinessActivityNames(entity);
    BusinessActivityVO vo = BusinessActivityVO.fromEntity(entity, names[0], names[1]);
    vo.setAssistUsers(
        assistRequestService.getVisibleAssists(ModelName.BUSINESS_ACTIVITY, id, currentId));
    return vo;
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

      List<BusinessActivityUserDTO> userIdList = businessActivityDTO.getUserIdList();
      // 修改活动关联的用户
      if (userIdList != null && !userIdList.isEmpty()) {
        if (businessActivityDTO.isAddUser()) {
          // 新增关联用户
          // 使用for循环替代forEach，确保异常能正确抛出
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
          // 删除关联用户
          // 使用for循环替代forEach，确保异常能正确抛出
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

      // 修改活动关联的联系人
      List<BusinessActivityContactDTO> contactIdList = businessActivityDTO.getContactIdList();
      if (contactIdList != null && !contactIdList.isEmpty()) {
        if (businessActivityDTO.isAddContact()) {
          // 新增关联联系人
          // 使用for循环替代forEach，确保异常能正确抛出
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
          // 删除关联联系人
          // 使用for循环替代forEach，确保异常能正确抛出
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

    // 1. 先删除每个业务活动关联的附件（遍历删除）
    for (Long activityId : idList) {
      // 查询该业务活动的所有附件
      List<com.slz.crm.pojo.entity.ApprovalAttachmentEntity> attachments =
          approvalAttachmentMapper.selectList(
              new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                      com.slz.crm.pojo.entity.ApprovalAttachmentEntity>()
                  .eq(com.slz.crm.pojo.entity.ApprovalAttachmentEntity::getAndId, activityId)
                  .eq(
                      com.slz.crm.pojo.entity.ApprovalAttachmentEntity::getModelName,
                      com.slz.crm.common.enumeration.ModelName.BUSINESS_ACTIVITY));

      if (attachments != null && !attachments.isEmpty()) {
        List<Long> attachmentIds =
            attachments.stream()
                .map(com.slz.crm.pojo.entity.ApprovalAttachmentEntity::getId)
                .collect(java.util.stream.Collectors.toList());
        approvalAttachmentService.removeByIds(
            attachmentIds, com.slz.crm.common.enumeration.ModelName.BUSINESS_ACTIVITY);
      }
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
    BusinessActivityEntity activity = getById(activityId);
    if (activity == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS.getMessage());
    }

    if (contactRequestList == null || contactRequestList.isEmpty()) {
      return true;
    }

    Long currentId = BaseUnit.getCurrentId();

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
    BusinessActivityEntity activity = getById(activityId);
    if (activity == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS.getMessage());
    }

    if (userRequestList == null || userRequestList.isEmpty()) {
      return true;
    }

    Long currentId = BaseUnit.getCurrentId();

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
    BusinessActivityEntity activity = getById(activityId);
    if (activity == null) {
      throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_NOT_EXISTS.getMessage());
    }

    if (request == null) {
      return true;
    }

    // 批量删除联系人关联
    List<Long> contactIds = request.getContactIds();
    if (contactIds != null && !contactIds.isEmpty()) {
      for (Long contactId : contactIds) {
        int count =
            businessActivityContactMapper.existsByActivityIdAndContactId(activityId, contactId);
        if (count == 0) {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
        }
        businessActivityContactMapper.deleteByActivityIdAndContactId(activityId, contactId);
      }
    }

    // 批量删除用户关联
    List<Long> userIds = request.getUserIds();
    if (userIds != null && !userIds.isEmpty()) {
      for (Long userId : userIds) {
        int count = businessActivityUserMapper.existsByActivityIdAndUserId(activityId, userId);
        if (count == 0) {
          throw new BaseException(MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
        }
        businessActivityUserMapper.deleteByActivityIdAndUserId(activityId, userId);
      }
    }

    return true;
  }

  /**
   * 普通列表可见性过滤：创建人/参与人可见，超级管理员豁免。 协助关系不进入普通列表范围，协助人只能从协助页访问关联单条详情。
   *
   * @param queryWrapper 查询包装器
   */
  private void applyVisibilityFilter(LambdaQueryWrapper<BusinessActivityEntity> queryWrapper) {
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    if (isAdmin) {
      return;
    }
    Set<Long> visibleIds = new HashSet<>();
    List<BusinessActivityUserEntity> participated =
        businessActivityUserMapper.selectList(
            new LambdaQueryWrapper<BusinessActivityUserEntity>()
                .eq(BusinessActivityUserEntity::getUserId, currentId));
    participated.forEach(rel -> visibleIds.add(rel.getActivityId()));
    queryWrapper.and(
        w ->
            w.eq(BusinessActivityEntity::getCreatorId, currentId)
                .or()
                .in(!visibleIds.isEmpty(), BusinessActivityEntity::getId, visibleIds));
  }

  /**
   * 批量组装协助人列表（申请人可见全部；协助人仅可见指派给自己的；其他人不展示）
   *
   * @param voList 活动VO列表
   * @param currentId 当前用户ID
   */
  private void fillAssistUsers(List<BusinessActivityVO> voList, Long currentId) {
    if (voList == null || voList.isEmpty()) {
      return;
    }
    List<Long> recordIds = voList.stream().map(BusinessActivityVO::getId).toList();
    List<AssistVO> allAssists =
        assistRequestService.listAssistsByRecords(ModelName.BUSINESS_ACTIVITY, recordIds);
    if (allAssists.isEmpty()) {
      voList.forEach(vo -> vo.setAssistUsers(Collections.emptyList()));
      return;
    }
    Map<Long, List<AssistVO>> byRecord =
        allAssists.stream().collect(Collectors.groupingBy(AssistVO::getRecordId));
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    for (BusinessActivityVO vo : voList) {
      List<AssistVO> list = byRecord.getOrDefault(vo.getId(), Collections.emptyList());
      if (list.isEmpty()) {
        vo.setAssistUsers(Collections.emptyList());
        continue;
      }
      vo.setAssistUsers(
          assistRequestService.getVisibleAssists(
              ModelName.BUSINESS_ACTIVITY, vo.getId(), currentId));
    }
  }
}
