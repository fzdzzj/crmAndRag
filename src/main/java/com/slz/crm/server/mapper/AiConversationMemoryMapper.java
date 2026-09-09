package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import org.apache.ibatis.annotations.Mapper;

/** AI 会话持久记忆 Mapper。 */
@Mapper
public interface AiConversationMemoryMapper extends BaseMapper<AiConversationMemoryEntity> {
}
