package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.ContactTaskService;
import com.slz.crm.server.service.DataConvertService;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
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
      Integer priority =
          ContactTaskQuerySupport.convertPriorityStringToInteger(contactTaskDTO.getPriorityStr());
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
    if (taskIds != null && !taskIds.isEmpty()) {
      contactTaskMapper.deleteCommentByTaskIds(taskIds);
    }
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
        Integer priority =
            ContactTaskQuerySupport.convertPriorityStringToInteger(dto.getPriorityStr());
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
      Integer priority =
          ContactTaskQuerySupport.convertPriorityStringToInteger(contactTaskDTO.getPriorityStr());
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

    return ContactTaskVoAssembler.buildDetailVo(
        entity, id, currentId, dataConvertService, businessActivityMapper, assistRequestService);
  }

  @Override
  public Page<ContactTaskVO> getAll(Integer pageNum, Integer pageSize) {
    Page<ContactTaskEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<ContactTaskEntity> wrapper =
        new LambdaQueryWrapper<ContactTaskEntity>().orderByDesc(ContactTaskEntity::getCreateTime);
    ContactTaskQuerySupport.applyVisibilityFilter(wrapper, userMapper);
    Page<ContactTaskEntity> pageResult = page(page, wrapper);

    Page<ContactTaskVO> resultPage = new Page<>();
    BeanUtils.copyProperties(pageResult, resultPage);

    // 使用批量查询优化性能
    List<ContactTaskVO> voList =
        ContactTaskVoAssembler.convertToVOListWithBatchQuery(
            pageResult.getRecords(), dataConvertService);
    ContactTaskVoAssembler.fillAssistUsers(assistRequestService, voList, BaseUnit.getCurrentId());
    resultPage.setRecords(voList);

    return resultPage;
  }

  @Override
  public Page<ContactTaskVO> query(
      Integer pageNum, Integer pageSize, ContactTaskDTO contactTaskDTO) {
    Page<ContactTaskEntity> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<ContactTaskEntity> wrapper =
        ContactTaskQuerySupport.buildTaskQueryWrapper(contactTaskDTO);

    wrapper.orderByDesc(ContactTaskEntity::getCreateTime);
    ContactTaskQuerySupport.applyVisibilityFilter(wrapper, userMapper);
    Page<ContactTaskEntity> pageResult = page(page, wrapper);

    Page<ContactTaskVO> resultPage = new Page<>();
    BeanUtils.copyProperties(pageResult, resultPage);

    // 使用批量查询优化性能
    List<ContactTaskVO> voList =
        ContactTaskVoAssembler.convertToVOListWithBatchQuery(
            pageResult.getRecords(), dataConvertService);
    ContactTaskVoAssembler.fillAssistUsers(assistRequestService, voList, BaseUnit.getCurrentId());
    resultPage.setRecords(voList);

    return resultPage;
  }

  @Override
  public List<ContactTaskVO> getByAssigneeId(Long assigneeId) {
    return listByField(assigneeId, ContactTaskEntity::getAssigneeId);
  }

  @Override
  public List<ContactTaskVO> getByCompanyId(Long companyId) {
    return listByField(companyId, ContactTaskEntity::getCompanyId);
  }

  @Override
  public List<ContactTaskVO> getByContactId(Long contactId) {
    return listByField(contactId, ContactTaskEntity::getContactId);
  }

  @Override
  public List<ContactTaskVO> getByOpportunityId(Long opportunityId) {
    return listByField(opportunityId, ContactTaskEntity::getOpportunityId);
  }

  /** 四个 getByXxxId 的公共实现：按单列等值 + 创建时间倒序检索，套用可见性过滤后批量装配 VO。 */
  private List<ContactTaskVO> listByField(Long fieldValue, SFunction<ContactTaskEntity, ?> column) {
    List<ContactTaskVO> voList = new ArrayList<>();
    if (fieldValue != null) {
      LambdaQueryWrapper<ContactTaskEntity> wrapper =
          new LambdaQueryWrapper<ContactTaskEntity>()
              .eq(column, fieldValue)
              .orderByDesc(ContactTaskEntity::getCreateTime);
      ContactTaskQuerySupport.applyVisibilityFilter(wrapper, userMapper);
      List<ContactTaskEntity> entities = list(wrapper);

      // 使用批量查询优化性能
      voList = ContactTaskVoAssembler.convertToVOListWithBatchQuery(entities, dataConvertService);
      ContactTaskVoAssembler.fillAssistUsers(assistRequestService, voList, BaseUnit.getCurrentId());
    }
    return voList;
  }
}
