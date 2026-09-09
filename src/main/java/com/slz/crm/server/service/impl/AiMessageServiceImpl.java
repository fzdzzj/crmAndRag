package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.pojo.vo.AiMessageVO;
import com.slz.crm.server.mapper.AiMessageMapper;
import com.slz.crm.server.service.AiMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j

@Service
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessageEntity> implements AiMessageService {


    @Override
    public AiMessageEntity saveMessage(Long sessionId, String role, String msgType, String content, String payload) {
        return doSaveMessage(sessionId, role, msgType, content, payload, 0);
    }

    @Override
    public AiMessageEntity saveMessage(Long sessionId, String role, String msgType, String content, String payload,
                                       Integer tokenCount) {
        return doSaveMessage(sessionId, role, msgType, content, payload,
                tokenCount == null || tokenCount < 0 ? 0 : tokenCount);
    }

    private AiMessageEntity doSaveMessage(Long sessionId, String role, String msgType, String content,
                                          String payload, Integer tokenCount) {
        AiMessageEntity message = new AiMessageEntity();

        message.setSessionId(sessionId);

        message.setRole(role);

        message.setMsgType(msgType == null || msgType.isBlank() ? "text" : msgType);

        message.setContent(content);

        message.setPayload(payload);

        message.setTokenCount(tokenCount);

        message.setCreatedTime(LocalDateTime.now());

        save(message);

        return message;

    }

    @Override
    public List<AiMessageVO> scrollMessages(Long sessionId, Long afterId, int limit) {
        LambdaQueryWrapper<AiMessageEntity> wrapper = new LambdaQueryWrapper<AiMessageEntity>()

                .eq(AiMessageEntity::getSessionId, sessionId)

                .orderByAsc(AiMessageEntity::getId)

                .last("LIMIT " + limit);

        // 游标：只返回 id > afterId 的消息
        if (afterId != null && afterId > 0) {

            wrapper.gt(AiMessageEntity::getId, afterId);

        }
        List<AiMessageEntity> entities = list(wrapper);


        List<AiMessageVO> result = new ArrayList<>(entities.size());

        for (AiMessageEntity entity : entities) {

            AiMessageVO vo = new AiMessageVO();

            BeanUtils.copyProperties(entity, vo);

            result.add(vo);

        }

        return result;

    }

    @Override
    public List<AiMessageEntity> listRecentContextMessages(Long sessionId, int limit) {
        return list(new LambdaQueryWrapper<AiMessageEntity>()

                .eq(AiMessageEntity::getSessionId, sessionId)

                .in(AiMessageEntity::getRole, List.of("user", "assistant"))

                .orderByDesc(AiMessageEntity::getId)

                .last("LIMIT " + limit));

    }
}
