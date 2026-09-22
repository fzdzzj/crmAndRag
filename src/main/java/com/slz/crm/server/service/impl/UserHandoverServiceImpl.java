package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.UserHandoverDTO;
import com.slz.crm.pojo.dto.UserHandoverQueryDTO;
import com.slz.crm.pojo.entity.*;
import com.slz.crm.pojo.vo.HandoverStatisticsVO;
import com.slz.crm.pojo.vo.UserHandoverVO;
import com.slz.crm.server.mapper.*;
import com.slz.crm.server.service.UserHandoverService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户交接服务实现类 */
@Slf4j
@Service
public class UserHandoverServiceImpl extends ServiceImpl<UserHandoverMapper, UserHandoverEntity>
    implements UserHandoverService {

  @Autowired private UserMapper userMapper;

  @Autowired private ContactTaskMapper contactTaskMapper;

  @Autowired private CustomerCompanyMapper customerCompanyMapper;

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public UserHandoverVO executeHandover(UserHandoverDTO dto) {
    // 1. 参数校验
    validateHandoverRequest(dto);

    // 2. 获取当前操作人
    Long operatorId = BaseUnit.getCurrentId();

    // 3. 执行三种资源的交接
    int taskCount = handoverTasks(dto.getFromUserId(), dto.getToUserId());
    int customerCount = handoverCustomers(dto.getFromUserId(), dto.getToUserId());
    int opportunityCount = handoverOpportunities(dto.getFromUserId(), dto.getToUserId());

    // 4. 创建交接记录
    UserHandoverEntity handoverRecord = new UserHandoverEntity();
    handoverRecord.setFromUserId(dto.getFromUserId());
    handoverRecord.setToUserId(dto.getToUserId());
    handoverRecord.setTaskCount(taskCount);
    handoverRecord.setCustomerCount(customerCount);
    handoverRecord.setOpportunityCount(opportunityCount);
    handoverRecord.setHandoverTime(LocalDateTime.now());
    handoverRecord.setOperatorId(operatorId);
    handoverRecord.setRemark(dto.getRemark());

    save(handoverRecord);

    log.info(
        "用户交接完成：从用户{}交接{}个任务、{}个客户、{}个销售机会给用户{}",
        dto.getFromUserId(),
        taskCount,
        customerCount,
        opportunityCount,
        dto.getToUserId());

    // 5. 返回结果
    return buildHandoverVO(handoverRecord);
  }

  /** 参数校验 */
  private void validateHandoverRequest(UserHandoverDTO dto) {
    // 校验离职用户是否存在
    UserEntity fromUser = userMapper.selectById(dto.getFromUserId());
    if (fromUser == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "离职用户不存在");
    }

    // 校验接收用户是否存在
    UserEntity toUser = userMapper.selectById(dto.getToUserId());
    if (toUser == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "接收用户不存在");
    }

    // 校验接收用户状态是否正常（status=1表示正常）
    if (toUser.getStatus() != 1) {
      throw new BaseException(ErrorCode.USER_STATUS_EXCEPTION, "接收用户状态异常，无法接收交接");
    }

    // 不能自己交接给自己
    if (dto.getFromUserId().equals(dto.getToUserId())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "不能将数据交接给自己");
    }
  }

  /** 任务交接 只交接未开始(0)和进行中(1)的任务 */
  private int handoverTasks(Long fromUserId, Long toUserId) {
    LambdaUpdateWrapper<ContactTaskEntity> wrapper = new LambdaUpdateWrapper<>();
    wrapper
        .set(ContactTaskEntity::getAssigneeId, toUserId)
        .eq(ContactTaskEntity::getAssigneeId, fromUserId)
        .in(ContactTaskEntity::getStatus, 0, 1);

    return contactTaskMapper.update(null, wrapper);
  }

  /** 客户交接 只交接未删除的数据 */
  private int handoverCustomers(Long fromUserId, Long toUserId) {
    LambdaUpdateWrapper<CustomerCompanyEntity> wrapper = new LambdaUpdateWrapper<>();
    wrapper
        .set(CustomerCompanyEntity::getOwnerId, toUserId)
        .eq(CustomerCompanyEntity::getOwnerId, fromUserId)
        .eq(CustomerCompanyEntity::getIsDeleted, false);

    return customerCompanyMapper.update(null, wrapper);
  }

  /** 销售机会交接 只交接未删除的数据 同时交接负责人(ownerId)和审批人(approverId) */
  private int handoverOpportunities(Long fromUserId, Long toUserId) {
    // 第一次更新：交接负责人(ownerId)
    LambdaUpdateWrapper<SalesOpportunityEntity> ownerWrapper = new LambdaUpdateWrapper<>();
    ownerWrapper
        .set(SalesOpportunityEntity::getOwnerId, toUserId)
        .eq(SalesOpportunityEntity::getOwnerId, fromUserId)
        .eq(SalesOpportunityEntity::getIsDeleted, 0);
    int ownerCount = salesOpportunityMapper.update(null, ownerWrapper);

    // 第二次更新：交接审批人(approverId)
    LambdaUpdateWrapper<SalesOpportunityEntity> approverWrapper = new LambdaUpdateWrapper<>();
    approverWrapper
        .set(SalesOpportunityEntity::getApproverId, toUserId)
        .eq(SalesOpportunityEntity::getApproverId, fromUserId)
        .eq(SalesOpportunityEntity::getIsDeleted, 0);
    salesOpportunityMapper.update(null, approverWrapper);

    // 返回负责人交接数量（审批人交接可能与之重复，不计入总数）
    return ownerCount;
  }

  /** 构建交接记录VO */
  private UserHandoverVO buildHandoverVO(UserHandoverEntity entity) {
    String fromUserName = getUserName(entity.getFromUserId());
    String toUserName = getUserName(entity.getToUserId());
    String operatorName = getUserName(entity.getOperatorId());

    return UserHandoverVO.fromEntity(entity, fromUserName, toUserName, operatorName);
  }

  /** 根据用户ID获取用户姓名 */
  private String getUserName(Long userId) {
    String result;
    if (userId == null) {
      result = null;
    } else {
      UserEntity user = userMapper.selectById(userId);
      result = user != null ? user.getRealName() : null;
    }
    return result;
  }

  @Override
  public Page<UserHandoverVO> queryHandoverRecords(UserHandoverQueryDTO queryDTO) {
    LambdaQueryWrapper<UserHandoverEntity> wrapper = new LambdaQueryWrapper<>();

    // 按离职用户筛选
    if (queryDTO.getFromUserId() != null) {
      wrapper.eq(UserHandoverEntity::getFromUserId, queryDTO.getFromUserId());
    }

    // 按接收用户筛选
    if (queryDTO.getToUserId() != null) {
      wrapper.eq(UserHandoverEntity::getToUserId, queryDTO.getToUserId());
    }

    // 按交接时间范围筛选
    if (queryDTO.getStartTime() != null) {
      wrapper.ge(UserHandoverEntity::getHandoverTime, queryDTO.getStartTime());
    }
    if (queryDTO.getEndTime() != null) {
      wrapper.le(UserHandoverEntity::getHandoverTime, queryDTO.getEndTime());
    }

    // 按交接时间倒序排列
    wrapper.orderByDesc(UserHandoverEntity::getHandoverTime);

    // 分页查询
    Page<UserHandoverEntity> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
    Page<UserHandoverEntity> result = page(page, wrapper);

    // 转换为VO
    return convertToVOPage(result);
  }

  /** 将实体分页结果转换为VO分页结果 */
  private Page<UserHandoverVO> convertToVOPage(Page<UserHandoverEntity> entityPage) {
    Page<UserHandoverVO> voPage =
        new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());

    List<UserHandoverVO> voList = new ArrayList<>();
    for (UserHandoverEntity entity : entityPage.getRecords()) {
      voList.add(buildHandoverVO(entity));
    }
    voPage.setRecords(voList);

    return voPage;
  }

  @Override
  public List<HandoverStatisticsVO> getHandoverStatistics(Long userId) {
    List<HandoverStatisticsVO> stats = new ArrayList<>();

    // 统计任务数量（未开始和进行中）
    Long taskCount =
        contactTaskMapper.selectCount(
            new LambdaQueryWrapper<ContactTaskEntity>()
                .eq(ContactTaskEntity::getAssigneeId, userId)
                .in(ContactTaskEntity::getStatus, 0, 1));
    stats.add(createStatItem("task", "任务", taskCount));

    // 统计客户数量（未删除）
    Long customerCount =
        customerCompanyMapper.selectCount(
            new LambdaQueryWrapper<CustomerCompanyEntity>()
                .eq(CustomerCompanyEntity::getOwnerId, userId)
                .eq(CustomerCompanyEntity::getIsDeleted, false));
    stats.add(createStatItem("customer", "客户", customerCount));

    // 统计销售机会数量（未删除）
    Long opportunityCount =
        salesOpportunityMapper.selectCount(
            new LambdaQueryWrapper<SalesOpportunityEntity>()
                .eq(SalesOpportunityEntity::getOwnerId, userId)
                .eq(SalesOpportunityEntity::getIsDeleted, 0));
    stats.add(createStatItem("opportunity", "销售机会", opportunityCount));

    return stats;
  }

  /** 创建统计项 */
  private HandoverStatisticsVO createStatItem(String type, String typeName, Long count) {
    HandoverStatisticsVO item = new HandoverStatisticsVO();
    item.setType(type);
    item.setTypeName(typeName);
    item.setCount(count.intValue());
    return item;
  }
}
