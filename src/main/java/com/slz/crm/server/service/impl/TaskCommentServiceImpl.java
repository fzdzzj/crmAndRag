package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.TaskCommentDTO;
import com.slz.crm.pojo.entity.TaskCommentEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.TaskCommentVO;
import com.slz.crm.server.mapper.TaskCommentMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.TaskCommentService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCommentServiceImpl extends ServiceImpl<TaskCommentMapper, TaskCommentEntity>
    implements TaskCommentService {

  @Autowired private TaskCommentMapper taskCommentMapper;

  @Autowired private UserMapper userMapper;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean create(TaskCommentDTO taskCommentDTO) {
    if (taskCommentDTO == null
        || taskCommentDTO.getTaskId() == null
        || taskCommentDTO.getContent() == null
        || taskCommentDTO.getContent().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }
    TaskCommentEntity taskCommentEntity = new TaskCommentEntity();
    BeanUtils.copyProperties(taskCommentDTO, taskCommentEntity);

    Long currentId = BaseUnit.getCurrentId();
    taskCommentEntity.setCreatorId(currentId);
    taskCommentEntity.setCreateTime(LocalDateTime.now());

    return save(taskCommentEntity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean deleteById(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }
    return removeById(id);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Integer deleteByIds(List<Long> idList) {
    if (idList == null || idList.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
    }
    return taskCommentMapper.deleteBatchIds(idList);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean update(TaskCommentDTO taskCommentDTO) {
    if (taskCommentDTO == null || taskCommentDTO.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    TaskCommentEntity entity = new TaskCommentEntity();
    BeanUtils.copyProperties(taskCommentDTO, entity);

    return updateById(entity);
  }

  @Override
  public TaskCommentVO getById(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }

    TaskCommentEntity entity = taskCommentMapper.selectById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.DATA_NULL.getMessage());
    }

    return convertToVO(entity);
  }

  @Override
  public Page<TaskCommentVO> getAll(Integer pageNum, Integer pageSize) {
    Page<TaskCommentEntity> page = new Page<>(pageNum, pageSize);
    Page<TaskCommentEntity> pageResult =
        page(
            page,
            new LambdaQueryWrapper<TaskCommentEntity>()
                .orderByDesc(TaskCommentEntity::getCreateTime));

    Page<TaskCommentVO> resultPage = new Page<>();
    BeanUtils.copyProperties(pageResult, resultPage);

    List<TaskCommentVO> voList = new ArrayList<>();
    for (TaskCommentEntity entity : pageResult.getRecords()) {
      voList.add(convertToVO(entity));
    }
    resultPage.setRecords(voList);

    return resultPage;
  }

  @Override
  public List<TaskCommentVO> getByTaskId(Long taskId) {
    List<TaskCommentVO> result;
    if (taskId == null) {
      result = new ArrayList<>();
    } else {

      List<TaskCommentEntity> entities =
          list(
              new LambdaQueryWrapper<TaskCommentEntity>()
                  .eq(TaskCommentEntity::getTaskId, taskId)
                  .orderByAsc(TaskCommentEntity::getCreateTime));

      List<TaskCommentVO> voList = new ArrayList<>();
      for (TaskCommentEntity entity : entities) {
        voList.add(convertToVO(entity));
      }

      result = voList;
    }
    return result;
  }

  @Override
  public Page<TaskCommentVO> getByTaskId(Long taskId, Integer pageNum, Integer pageSize) {
    Page<TaskCommentVO> result;
    if (taskId == null) {
      result = new Page<>();
    } else {

      Page<TaskCommentEntity> page = new Page<>(pageNum, pageSize);
      Page<TaskCommentEntity> pageResult =
          page(
              page,
              new LambdaQueryWrapper<TaskCommentEntity>()
                  .eq(TaskCommentEntity::getTaskId, taskId)
                  .orderByAsc(TaskCommentEntity::getCreateTime));

      Page<TaskCommentVO> resultPage = new Page<>();
      BeanUtils.copyProperties(pageResult, resultPage);

      List<TaskCommentVO> voList = new ArrayList<>();
      for (TaskCommentEntity entity : pageResult.getRecords()) {
        voList.add(convertToVO(entity));
      }
      resultPage.setRecords(voList);

      result = resultPage;
    }
    return result;
  }

  /** 将Entity转换为VO，并设置评论人姓名 */
  private TaskCommentVO convertToVO(TaskCommentEntity entity) {
    TaskCommentVO vo = new TaskCommentVO();
    BeanUtils.copyProperties(entity, vo);

    // 设置评论人姓名
    if (entity.getCreatorId() != null) {
      UserEntity creator = userMapper.selectById(entity.getCreatorId());
      if (creator != null) {
        vo.setCreatorName(creator.getRealName());
      }
    }

    return vo;
  }
}
