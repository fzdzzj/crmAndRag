package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.entity.AssistMessageEntity;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AssistMessageVO;
import com.slz.crm.server.mapper.AssistMessageMapper;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AssistMessageService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AssistMessageServiceImpl implements AssistMessageService {

  private static final String TEXT = "TEXT";
  private static final String SYSTEM = "SYSTEM";

  @Autowired private AssistMessageMapper assistMessageMapper;
  @Autowired private AssistRequestMapper assistRequestMapper;
  @Autowired private UserMapper userMapper;
  @Autowired private ContactTaskMapper contactTaskMapper;

  @Override
  public List<AssistMessageVO> listVisible(Long assistId) {
    requireVisible(assistId);
    List<AssistMessageEntity> entities =
        assistMessageMapper.selectList(
            new LambdaQueryWrapper<AssistMessageEntity>()
                .eq(AssistMessageEntity::getAssistId, assistId)
                .orderByAsc(AssistMessageEntity::getCreateTime)
                .orderByAsc(AssistMessageEntity::getId));
    List<AssistMessageVO> result;
    if (entities.isEmpty()) {
      result = Collections.emptyList();
    } else {
      Set<Long> senderIds =
          entities.stream()
              .map(AssistMessageEntity::getSenderId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      Map<Long, UserEntity> users =
          senderIds.isEmpty()
              ? Collections.emptyMap()
              : userMapper.selectBatchIds(senderIds).stream()
                  .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
      result =
          entities.stream().map(entity -> toVO(entity, users.get(entity.getSenderId()))).toList();
    }
    return result;
  }

  @Override
  public void sendText(Long assistId, String content) {
    AssistRequestEntity assist = requireVisible(assistId);
    String text = trimToNull(content);
    if (text == null) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "协助消息不能为空");
    }
    if (text.length() > 2000) {
      throw new BaseException(ErrorCode.PARAM_LENGTH_EXCEEDED, "协助消息不能超过2000个字符");
    }
    if (!Objects.equals(assist.getAssistStatus(), 0)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，消息仅可查看");
    }
    insert(assistId, BaseUnit.getCurrentId(), text, TEXT);
  }

  @Override
  public void appendSystemMessage(Long assistId, String content) {
    if (assistId == null || trimToNull(content) == null) {
      return;
    }
    insert(assistId, null, content.trim(), SYSTEM);
  }

  private AssistRequestEntity requireVisible(Long assistId) {
    if (assistId == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    AssistRequestEntity assist = assistRequestMapper.selectById(assistId);
    if (assist == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "协助记录不存在");
    }
    Long currentId = BaseUnit.getCurrentId();
    UserEntity current = userMapper.selectById(currentId);
    boolean isAdmin = current != null && Objects.equals(current.getRoleId(), 1L);
    if (!isAdmin
        && !Objects.equals(assist.getApplicantId(), currentId)
        && !Objects.equals(assist.getAssistUserId(), currentId)
        && !isContactTaskParticipant(assist, currentId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED);
    }
    return assist;
  }

  /** 联络任务创建人、指派人和执行人可以参与该任务协助的过程沟通。 */
  private boolean isContactTaskParticipant(AssistRequestEntity assist, Long userId) {
    boolean result;
    if (!ModelName.CONTACT_TASK.equals(assist.getModelName()) || assist.getRecordId() == null) {
      result = false;
    } else {
      ContactTaskEntity task = contactTaskMapper.selectById(assist.getRecordId());
      result =
          task != null
              && (Objects.equals(task.getCreatorId(), userId)
                  || Objects.equals(task.getAssignerId(), userId)
                  || Objects.equals(task.getAssigneeId(), userId));
    }
    return result;
  }

  private void insert(Long assistId, Long senderId, String content, String type) {
    AssistMessageEntity entity = new AssistMessageEntity();
    entity.setAssistId(assistId);
    entity.setSenderId(senderId);
    entity.setContent(content);
    entity.setMessageType(type);
    entity.setCreateTime(LocalDateTime.now());
    if (assistMessageMapper.insert(entity) <= 0) {
      throw new BaseException(ErrorCode.UPDATE_FAILED, "协助消息保存失败");
    }
  }

  private AssistMessageVO toVO(AssistMessageEntity entity, UserEntity sender) {
    AssistMessageVO vo = new AssistMessageVO();
    vo.setId(entity.getId());
    vo.setAssistId(entity.getAssistId());
    vo.setSenderId(entity.getSenderId());
    vo.setSenderName(
        sender == null
            ? (SYSTEM.equals(entity.getMessageType()) ? "系统" : null)
            : sender.getRealName());
    vo.setContent(entity.getContent());
    vo.setMessageType(entity.getMessageType());
    vo.setCreateTime(entity.getCreateTime());
    return vo;
  }

  private String trimToNull(String value) {
    String result = null;
    if (value != null) {
      String trimmed = value.trim();
      result = trimmed.isEmpty() ? null : trimmed;
    }
    return result;
  }
}
