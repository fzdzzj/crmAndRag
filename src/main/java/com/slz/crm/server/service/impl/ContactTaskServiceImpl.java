package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.ContactTaskService;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactTaskServiceImpl extends ServiceImpl<ContactTaskMapper, ContactTaskEntity>
    implements ContactTaskService {

  @Autowired private ContactTaskMapper contactTaskMapper;

  @Autowired private DataConvertService dataConvertService;

  @Autowired private BusinessActivityMapper businessActivityMapper;

  @Autowired private UserMapper userMapper;

  @Autowired private AssistRequestService assistRequestService;

  @Autowired private ApprovalAttachmentService approvalAttachmentService;

  /** 将字符串优先级转换为数字 */
  private Integer convertPriorityStringToInteger(String priorityStr) {
    Integer result = null;
    if (priorityStr != null && !priorityStr.trim().isEmpty()) {
      String trimmed = priorityStr.trim();
      result =
          switch (trimmed) {
            case "低" -> 1; // 0-2 对应低，取中间值 1
            case "中" -> 4; // 3-5 对应中，取中间值 4
            case "高" -> 7; // 6-8 对应高，取中间值 7
            case "紧急" -> 9; // 9 对应紧急
            default -> null; // 无效值返回 null
          };
    }
    return result;
  }

  /** 将数字优先级转换为字符串 */
  private String convertPriorityToString(Integer priority) {
    String result = "未设置";
    if (priority != null) {
      result =
          switch (priority) {
            case 0, 1, 2 -> "低";
            case 3, 4, 5 -> "中";
            case 6, 7, 8 -> "高";
            case 9 -> "紧急";
            default -> "未设置";
          };
    }
    return result;
  }

  /** 将字符串状态转换为数字 */
  private Integer convertStatusStringToInteger(String statusStr) {
    Integer result = null;
    if (statusStr != null && !statusStr.trim().isEmpty()) {
      String trimmed = statusStr.trim();
      result =
          switch (trimmed) {
            case "未开始" -> 0;
            case "进行中" -> 1;
            case "已完成" -> 2;
            case "已取消" -> 3;
            default -> null; // 无效值返回 null
          };
    }
    return result;
  }

  /** 将数字状态转换为字符串 */
  private String convertStatusToString(Integer status) {
    String result = "未知状态";
    if (status != null) {
      result =
          switch (status) {
            case 0 -> "未开始";
            case 1 -> "进行中";
            case 2 -> "已完成";
            case 3 -> "已取消";
            default -> "未知状态";
          };
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean create(ContactTaskDTO contactTaskDTO) {
    if (contactTaskDTO == null || contactTaskDTO.getTaskTitle() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    ContactTaskEntity contactTaskEntity = new ContactTaskEntity();
    BeanUtils.copyProperties(contactTaskDTO, contactTaskEntity);
    applyTaskTimes(contactTaskDTO, contactTaskEntity);

    Long currentId = BaseUnit.getCurrentId();
    contactTaskEntity.setCreatorId(currentId);
    contactTaskEntity.setAssignerId(currentId); // 指派人默认为当前用户
    contactTaskEntity.setCreateTime(new Date());
    contactTaskEntity.setUpdateTime(new Date());

    // 设置默认状态为未开始
    if (contactTaskEntity.getStatus() == null) {
      contactTaskEntity.setStatus(0);
    }

    // 处理优先级：如果传入了字符串优先级，优先转换字符串
    if (contactTaskDTO.getPriorityStr() != null
        && !contactTaskDTO.getPriorityStr().trim().isEmpty()) {
      Integer priority = convertPriorityStringToInteger(contactTaskDTO.getPriorityStr());
      if (priority != null) {
        contactTaskEntity.setPriority(priority);
      } else {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "优先级参数错误，只支持：低、中、高、紧急");
      }
    } else if (contactTaskEntity.getPriority() == null) {
      // 设置默认优先级为中
      contactTaskEntity.setPriority(4);
    }

    boolean saved = save(contactTaskEntity);
    if (saved) {
      // 保存协助人记录（可空，空则跳过）
      assistRequestService.createAssists(
          ModelName.CONTACT_TASK,
          contactTaskEntity.getId(),
          currentId,
          contactTaskDTO.getAssistApplyList());
    }
    return saved;
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
  @Transactional(rollbackFor = Exception.class)
  public Integer deleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }
    // 级联删除协助记录
    assistRequestService.deleteByRecords(ModelName.CONTACT_TASK, idList);
    // 级联删除任务附件（含磁盘文件）
    approvalAttachmentService.removeByAndIds(idList, ModelName.CONTACT_TASK);
    // 级联删除任务评论
    deleteCommentByTaskIds(idList);
    return contactTaskMapper.deleteBatchIds(idList);
  }

  private void deleteCommentByTaskIds(List<Long> taskIds) {
    if (taskIds == null || taskIds.isEmpty()) {
      return;
    }
    contactTaskMapper.deleteCommentByTaskIds(taskIds);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean deleteById(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }
    // 级联删除协助记录
    assistRequestService.deleteByRecords(ModelName.CONTACT_TASK, List.of(id));
    approvalAttachmentService.removeByAndIds(List.of(id), ModelName.CONTACT_TASK);
    deleteCommentByTaskIds(List.of(id));
    return removeById(id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer updateList(List<ContactTaskDTO> contactTaskDTOList) {
    if (contactTaskDTOList == null || contactTaskDTOList.isEmpty()) {
      throw new BaseException(ErrorCode.DATA_NULL.getMessage());
    }

    List<ContactTaskEntity> entityList = new ArrayList<>();
    for (ContactTaskDTO dto : contactTaskDTOList) {
      ContactTaskEntity entity = new ContactTaskEntity();
      BeanUtils.copyProperties(dto, entity);
      entity.setUpdateTime(new Date());

      // 处理优先级：如果传入了字符串优先级，优先转换字符串
      if (dto.getPriorityStr() != null && !dto.getPriorityStr().trim().isEmpty()) {
        Integer priority = convertPriorityStringToInteger(dto.getPriorityStr());
        if (priority != null) {
          entity.setPriority(priority);
        } else {
          throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "优先级参数错误，只支持：低、中、高、紧急");
        }
      }

      entityList.add(entity);
    }

    return updateBatchById(entityList) ? entityList.size() : 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean update(ContactTaskDTO contactTaskDTO) {
    if (contactTaskDTO == null || contactTaskDTO.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    ContactTaskEntity entity = new ContactTaskEntity();
    BeanUtils.copyProperties(contactTaskDTO, entity);
    applyTaskTimes(contactTaskDTO, entity);
    entity.setUpdateTime(new Date());

    // 处理优先级：如果传入了字符串优先级，优先转换字符串
    if (contactTaskDTO.getPriorityStr() != null
        && !contactTaskDTO.getPriorityStr().trim().isEmpty()) {
      Integer priority = convertPriorityStringToInteger(contactTaskDTO.getPriorityStr());
      if (priority != null) {
        entity.setPriority(priority);
      } else {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "优先级参数错误，只支持：低、中、高、紧急");
      }
    }

    return updateById(entity);
  }

  /** DTO 的时间字段为 LocalDateTime、Entity 为 Date，BeanUtils 类型不匹配会跳过复制， 这里手动转换，保证创建/编辑时开始、结束时间能写入 */
  private void applyTaskTimes(ContactTaskDTO dto, ContactTaskEntity entity) {
    if (dto.getStartTime() != null) {
      entity.setStartTime(
          Date.from(dto.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant()));
    }
    if (dto.getEndTime() != null) {
      entity.setEndTime(
          Date.from(dto.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant()));
    }
  }

  @Override
  public ContactTaskVO getById(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    ContactTaskEntity entity = contactTaskMapper.selectById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.DATA_NULL.getMessage());
    }

    // 相关人校验：创建人/执行人/指派人/协助人可见，超管豁免
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    if (!isAdmin) {
      boolean visible =
          Objects.equals(entity.getCreatorId(), currentId)
              || Objects.equals(entity.getAssigneeId(), currentId)
              || Objects.equals(entity.getAssignerId(), currentId)
              || assistRequestService
                  .getRelatedRecordIdsByUser(ModelName.CONTACT_TASK, currentId)
                  .contains(id);
      if (!visible) {
        throw new BaseException(ErrorCode.PERMISSION_DENIED);
      }
    }

    // 查询单个任务时，也需要查询关联数据，确保返回完整的 VO 信息
    ContactTaskVO vo = convertToVOListWithBatchQuery(Collections.singletonList(entity)).get(0);

    // 查询相关的业务活动列表
    List<BusinessActivityEntity> businessActivities = businessActivityMapper.selectByTaskId(id);

    if (!businessActivities.isEmpty()) {
      List<BusinessActivityVO> activityVOs = convertBusinessActivitiesToVOs(businessActivities);
      vo.setBusinessActivities(activityVOs);
    }

    vo.setAssistUsers(
        assistRequestService.getVisibleAssists(ModelName.CONTACT_TASK, id, currentId));
    return vo;
  }

  /** 将商业活动实体列表转换为VO列表 */
  private List<BusinessActivityVO> convertBusinessActivitiesToVOs(
      List<BusinessActivityEntity> entities) {
    if (entities == null || entities.isEmpty()) {
      return new ArrayList<>();
    }

    // 收集所有需要查询的ID
    Set<Long> creatorIds = new HashSet<>();
    Set<Long> opportunityIds = new HashSet<>();

    for (BusinessActivityEntity entity : entities) {
      if (entity.getCreatorId() != null) creatorIds.add(entity.getCreatorId());
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
    }

    // 使用 DataConvertService 批量查询用户名称和销售机会名称
    Map<Long, String> creatorNameMap = dataConvertService.getUserNames(creatorIds);
    Map<Long, String> opportunityNameMap = dataConvertService.getOpportunityNames(opportunityIds);

    // 构建VO列表
    List<BusinessActivityVO> voList = new ArrayList<>();
    for (BusinessActivityEntity entity : entities) {
      BusinessActivityVO vo = new BusinessActivityVO();
      vo.setId(entity.getId());
      vo.setActivityTitle(entity.getActivityTitle());
      vo.setActivityContent(entity.getActivityContent());
      vo.setActivityTime(entity.getActivityTime());
      vo.setActivityType(entity.getActivityType());
      vo.setActivityDuration(entity.getActivityDuration());
      vo.setOpportunityId(entity.getOpportunityId());
      vo.setCreateTime(entity.getCreateTime());
      vo.setRemark(entity.getRemark());
      vo.setCreatorId(entity.getCreatorId());
      vo.setTaskId(entity.getTaskId());

      // 设置创建者名称
      vo.setCreatorName(creatorNameMap.get(entity.getCreatorId()));

      // 设置销售机会名称
      vo.setOpportunityName(opportunityNameMap.get(entity.getOpportunityId()));

      voList.add(vo);
    }

    return voList;
  }

  @Override
  public Page<ContactTaskVO> getAll(Integer pageNum, Integer pageSize) {
    Page<ContactTaskEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<ContactTaskEntity> wrapper =
        new LambdaQueryWrapper<ContactTaskEntity>().orderByDesc(ContactTaskEntity::getCreateTime);
    applyVisibilityFilter(wrapper);
    Page<ContactTaskEntity> pageResult = page(page, wrapper);

    Page<ContactTaskVO> resultPage = new Page<>();
    BeanUtils.copyProperties(pageResult, resultPage);

    // 使用批量查询优化性能
    List<ContactTaskVO> voList = convertToVOListWithBatchQuery(pageResult.getRecords());
    fillAssistUsers(voList, BaseUnit.getCurrentId());
    resultPage.setRecords(voList);

    return resultPage;
  }

  @Override
  public Page<ContactTaskVO> query(
      Integer pageNum, Integer pageSize, ContactTaskDTO contactTaskDTO) {
    Page<ContactTaskEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<ContactTaskEntity> wrapper = new LambdaQueryWrapper<>();

    if (contactTaskDTO != null) {
      if (contactTaskDTO.getTaskTitle() != null) {
        wrapper.like(ContactTaskEntity::getTaskTitle, contactTaskDTO.getTaskTitle());
      }
      if (contactTaskDTO.getCompanyId() != null) {
        wrapper.eq(ContactTaskEntity::getCompanyId, contactTaskDTO.getCompanyId());
      }
      if (contactTaskDTO.getContactId() != null) {
        wrapper.eq(ContactTaskEntity::getContactId, contactTaskDTO.getContactId());
      }
      if (contactTaskDTO.getOpportunityId() != null) {
        wrapper.eq(ContactTaskEntity::getOpportunityId, contactTaskDTO.getOpportunityId());
      }
      if (contactTaskDTO.getTaskType() != null) {
        wrapper.eq(ContactTaskEntity::getTaskType, contactTaskDTO.getTaskType());
      }
      // 处理优先级：如果传入了字符串优先级，优先转换字符串
      if (contactTaskDTO.getPriorityStr() != null
          && !contactTaskDTO.getPriorityStr().trim().isEmpty()) {
        Integer priority = convertPriorityStringToInteger(contactTaskDTO.getPriorityStr());
        if (priority != null) {
          wrapper.eq(ContactTaskEntity::getPriority, priority);
        }
      } else if (contactTaskDTO.getPriority() != null) {
        wrapper.eq(ContactTaskEntity::getPriority, contactTaskDTO.getPriority());
      }
      // 处理状态：如果传入了字符串状态，优先转换字符串
      if (contactTaskDTO.getStatusStr() != null
          && !contactTaskDTO.getStatusStr().trim().isEmpty()) {
        Integer status = convertStatusStringToInteger(contactTaskDTO.getStatusStr());
        if (status != null) {
          wrapper.eq(ContactTaskEntity::getStatus, status);
        }
      } else if (contactTaskDTO.getStatus() != null) {
        wrapper.eq(ContactTaskEntity::getStatus, contactTaskDTO.getStatus());
      }
      if (contactTaskDTO.getAssigneeId() != null) {
        wrapper.eq(ContactTaskEntity::getAssigneeId, contactTaskDTO.getAssigneeId());
      }
    }

    wrapper.orderByDesc(ContactTaskEntity::getCreateTime);
    applyVisibilityFilter(wrapper);
    Page<ContactTaskEntity> pageResult = page(page, wrapper);

    Page<ContactTaskVO> resultPage = new Page<>();
    BeanUtils.copyProperties(pageResult, resultPage);

    // 使用批量查询优化性能
    List<ContactTaskVO> voList = convertToVOListWithBatchQuery(pageResult.getRecords());
    fillAssistUsers(voList, BaseUnit.getCurrentId());
    resultPage.setRecords(voList);

    return resultPage;
  }

  @Override
  public List<ContactTaskVO> getByAssigneeId(Long assigneeId) {
    List<ContactTaskVO> voList = new ArrayList<>();
    if (assigneeId != null) {
      LambdaQueryWrapper<ContactTaskEntity> wrapper =
          new LambdaQueryWrapper<ContactTaskEntity>()
              .eq(ContactTaskEntity::getAssigneeId, assigneeId)
              .orderByDesc(ContactTaskEntity::getCreateTime);
      applyVisibilityFilter(wrapper);
      List<ContactTaskEntity> entities = list(wrapper);

      // 使用批量查询优化性能
      voList = convertToVOListWithBatchQuery(entities);
      fillAssistUsers(voList, BaseUnit.getCurrentId());
    }
    return voList;
  }

  @Override
  public List<ContactTaskVO> getByCompanyId(Long companyId) {
    List<ContactTaskVO> voList = new ArrayList<>();
    if (companyId != null) {
      LambdaQueryWrapper<ContactTaskEntity> wrapper =
          new LambdaQueryWrapper<ContactTaskEntity>()
              .eq(ContactTaskEntity::getCompanyId, companyId)
              .orderByDesc(ContactTaskEntity::getCreateTime);
      applyVisibilityFilter(wrapper);
      List<ContactTaskEntity> entities = list(wrapper);

      // 使用批量查询优化性能
      voList = convertToVOListWithBatchQuery(entities);
      fillAssistUsers(voList, BaseUnit.getCurrentId());
    }
    return voList;
  }

  @Override
  public List<ContactTaskVO> getByContactId(Long contactId) {
    List<ContactTaskVO> voList = new ArrayList<>();
    if (contactId != null) {
      LambdaQueryWrapper<ContactTaskEntity> wrapper =
          new LambdaQueryWrapper<ContactTaskEntity>()
              .eq(ContactTaskEntity::getContactId, contactId)
              .orderByDesc(ContactTaskEntity::getCreateTime);
      applyVisibilityFilter(wrapper);
      List<ContactTaskEntity> entities = list(wrapper);

      // 使用批量查询优化性能
      voList = convertToVOListWithBatchQuery(entities);
      fillAssistUsers(voList, BaseUnit.getCurrentId());
    }
    return voList;
  }

  @Override
  public List<ContactTaskVO> getByOpportunityId(Long opportunityId) {
    List<ContactTaskVO> voList = new ArrayList<>();
    if (opportunityId != null) {
      LambdaQueryWrapper<ContactTaskEntity> wrapper =
          new LambdaQueryWrapper<ContactTaskEntity>()
              .eq(ContactTaskEntity::getOpportunityId, opportunityId)
              .orderByDesc(ContactTaskEntity::getCreateTime);
      applyVisibilityFilter(wrapper);
      List<ContactTaskEntity> entities = list(wrapper);

      // 使用批量查询优化性能
      voList = convertToVOListWithBatchQuery(entities);
      fillAssistUsers(voList, BaseUnit.getCurrentId());
    }
    return voList;
  }

  /** 将Entity转换为VO，使用批量查询结果（性能优化版本） */
  private ContactTaskVO convertToVOWithMaps(
      ContactTaskEntity entity,
      Map<Long, String> userNameMap,
      Map<Long, String> companyNameMap,
      Map<Long, String> contactNameMap,
      Map<Long, String> opportunityNameMap) {
    ContactTaskVO vo = new ContactTaskVO();

    // 复制基础字段（排除 status 和 priority，因为类型不匹配）
    vo.setId(entity.getId());
    vo.setTaskTitle(entity.getTaskTitle());
    vo.setCompanyId(entity.getCompanyId());
    vo.setContactId(entity.getContactId());
    vo.setOpportunityId(entity.getOpportunityId());
    vo.setTaskType(entity.getTaskType());
    vo.setTaskContent(entity.getTaskContent());
    vo.setAssigneeId(entity.getAssigneeId());
    vo.setCreatorId(entity.getCreatorId());

    // 设置创建人姓名
    vo.setCreatorName(userNameMap.get(entity.getCreatorId()));

    // 设置执行人姓名
    vo.setAssigneeName(userNameMap.get(entity.getAssigneeId()));

    // 设置指派人ID和姓名
    vo.setAssignerId(entity.getAssignerId());
    vo.setAssignerName(userNameMap.get(entity.getAssignerId()));

    // 设置公司名称
    vo.setCompanyName(companyNameMap.get(entity.getCompanyId()));

    // 设置联系人姓名
    vo.setContactName(contactNameMap.get(entity.getContactId()));

    // 设置销售机会标题
    vo.setOpportunityTitle(opportunityNameMap.get(entity.getOpportunityId()));

    // 设置优先级（直接设置为字符串）
    vo.setPriority(convertPriorityToString(entity.getPriority()));

    // 设置状态（直接设置为字符串）
    vo.setStatus(convertStatusToString(entity.getStatus()));

    // 设置时间字段（批量转换版本此前遗漏，导致详情时间为空）
    vo.setStartTime(
        entity.getStartTime() != null
            ? entity
                .getStartTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);
    vo.setEndTime(
        entity.getEndTime() != null
            ? entity
                .getEndTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);
    vo.setCreateTime(
        entity.getCreateTime() != null
            ? entity
                .getCreateTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);
    vo.setUpdateTime(
        entity.getUpdateTime() != null
            ? entity
                .getUpdateTime()
                .toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
            : null);

    return vo;
  }

  /** 批量查询关联数据并转换为VO列表（性能优化） */
  private List<ContactTaskVO> convertToVOListWithBatchQuery(List<ContactTaskEntity> entities) {
    if (entities == null || entities.isEmpty()) {
      return new ArrayList<>();
    }

    // 收集所有需要查询的ID
    Set<Long> userIds = new HashSet<>();
    Set<Long> companyIds = new HashSet<>();
    Set<Long> contactIds = new HashSet<>();
    Set<Long> opportunityIds = new HashSet<>();

    for (ContactTaskEntity entity : entities) {
      if (entity.getCreatorId() != null) userIds.add(entity.getCreatorId());
      if (entity.getAssigneeId() != null) userIds.add(entity.getAssigneeId());
      if (entity.getCompanyId() != null) companyIds.add(entity.getCompanyId());
      if (entity.getContactId() != null) contactIds.add(entity.getContactId());
      if (entity.getOpportunityId() != null) opportunityIds.add(entity.getOpportunityId());
    }

    // 使用 DataConvertService 批量查询
    Map<Long, String> userNameMap = dataConvertService.getUserNames(userIds);
    Map<Long, String> companyNameMap = dataConvertService.getCompanyNames(companyIds);
    Map<Long, String> contactNameMap = dataConvertService.getContactNames(contactIds);
    Map<Long, String> opportunityNameMap = dataConvertService.getOpportunityNames(opportunityIds);

    // 构建VO列表
    List<ContactTaskVO> voList = new ArrayList<>();
    for (ContactTaskEntity entity : entities) {
      voList.add(
          convertToVOWithMaps(
              entity, userNameMap, companyNameMap, contactNameMap, opportunityNameMap));
    }

    return voList;
  }

  /**
   * 普通列表可见性过滤：创建人/执行人/指派人可见，超级管理员豁免。 协助关系不进入普通列表范围，协助人只能从协助页访问关联单条详情。
   *
   * @param wrapper 查询包装器
   */
  private void applyVisibilityFilter(LambdaQueryWrapper<ContactTaskEntity> wrapper) {
    Long currentId = BaseUnit.getCurrentId();
    UserEntity currentUser = userMapper.selectById(currentId);
    boolean isAdmin = currentUser != null && Objects.equals(currentUser.getRoleId(), 1L);
    if (isAdmin) {
      return;
    }
    wrapper.and(
        w ->
            w.eq(ContactTaskEntity::getCreatorId, currentId)
                .or()
                .eq(ContactTaskEntity::getAssigneeId, currentId)
                .or()
                .eq(ContactTaskEntity::getAssignerId, currentId));
  }

  /**
   * 批量组装协助人列表（申请人可见全部；协助人仅可见指派给自己的；其他人不展示）
   *
   * @param voList 任务VO列表
   * @param currentId 当前用户ID
   */
  private void fillAssistUsers(List<ContactTaskVO> voList, Long currentId) {
    if (voList == null || voList.isEmpty()) {
      return;
    }
    List<Long> recordIds = voList.stream().map(ContactTaskVO::getId).toList();
    List<AssistVO> allAssists =
        assistRequestService.listAssistsByRecords(ModelName.CONTACT_TASK, recordIds);
    if (allAssists.isEmpty()) {
      voList.forEach(vo -> vo.setAssistUsers(Collections.emptyList()));
      return;
    }
    Map<Long, List<AssistVO>> byRecord =
        allAssists.stream().collect(Collectors.groupingBy(AssistVO::getRecordId));
    for (ContactTaskVO vo : voList) {
      List<AssistVO> list = byRecord.getOrDefault(vo.getId(), Collections.emptyList());
      if (list.isEmpty()) {
        vo.setAssistUsers(Collections.emptyList());
        continue;
      }
      vo.setAssistUsers(
          assistRequestService.getVisibleAssists(ModelName.CONTACT_TASK, vo.getId(), currentId));
    }
  }
}
