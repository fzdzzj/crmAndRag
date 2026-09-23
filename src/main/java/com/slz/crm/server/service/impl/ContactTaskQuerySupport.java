package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import java.util.Objects;

/** 联络任务查询条件构建支持类：优先级/状态字符串转换与检索包装器装配，纯静态、无状态。 */
final class ContactTaskQuerySupport {

  private ContactTaskQuerySupport() {}

  /** 将字符串优先级转换为数字 */
  static Integer convertPriorityStringToInteger(String priorityStr) {
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
  static String convertPriorityToString(Integer priority) {
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
  static Integer convertStatusStringToInteger(String statusStr) {
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
  static String convertStatusToString(Integer status) {
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

  /** 按查询条件装配联络任务的检索包装器（标题/归属/类型/优先级/状态/执行人，DTO 为空时不加条件） */
  static LambdaQueryWrapper<ContactTaskEntity> buildTaskQueryWrapper(
      ContactTaskDTO contactTaskDTO) {
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
      applyPriorityFilter(wrapper, contactTaskDTO);
      applyStatusFilter(wrapper, contactTaskDTO);
      if (contactTaskDTO.getAssigneeId() != null) {
        wrapper.eq(ContactTaskEntity::getAssigneeId, contactTaskDTO.getAssigneeId());
      }
    }
    return wrapper;
  }

  /** 优先级过滤：传入字符串优先级时优先转换，否则用数值优先级 */
  private static void applyPriorityFilter(
      LambdaQueryWrapper<ContactTaskEntity> wrapper, ContactTaskDTO contactTaskDTO) {
    if (contactTaskDTO.getPriorityStr() != null
        && !contactTaskDTO.getPriorityStr().trim().isEmpty()) {
      Integer priority = convertPriorityStringToInteger(contactTaskDTO.getPriorityStr());
      if (priority != null) {
        wrapper.eq(ContactTaskEntity::getPriority, priority);
      }
    } else if (contactTaskDTO.getPriority() != null) {
      wrapper.eq(ContactTaskEntity::getPriority, contactTaskDTO.getPriority());
    }
  }

  /** 状态过滤：传入字符串状态时优先转换，否则用数值状态 */
  private static void applyStatusFilter(
      LambdaQueryWrapper<ContactTaskEntity> wrapper, ContactTaskDTO contactTaskDTO) {
    if (contactTaskDTO.getStatusStr() != null && !contactTaskDTO.getStatusStr().trim().isEmpty()) {
      Integer status = convertStatusStringToInteger(contactTaskDTO.getStatusStr());
      if (status != null) {
        wrapper.eq(ContactTaskEntity::getStatus, status);
      }
    } else if (contactTaskDTO.getStatus() != null) {
      wrapper.eq(ContactTaskEntity::getStatus, contactTaskDTO.getStatus());
    }
  }

  /**
   * 普通列表可见性过滤：创建人/执行人/指派人可见，超级管理员豁免。 协助关系不进入普通列表范围，协助人只能从协助页访问关联单条详情。
   *
   * @param wrapper 查询包装器
   */
  static void applyVisibilityFilter(
      LambdaQueryWrapper<ContactTaskEntity> wrapper, UserMapper userMapper) {
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
}
